package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.service.evaluation.PromptBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end guard that submits a single request using the production
 * {@link PromptBuilder#buildOutputConfig()} schema through the Anthropic
 * Message Batches API, and fails if Anthropic rejects the schema at
 * compile time (as it did for commit {@code 4e7691a}, which added
 * {@code minimum}/{@code maximum} keywords that structured outputs rejects).
 *
 * <p>Auto-skips when {@code ANTHROPIC_API_KEY} is not set, so it costs
 * nothing in normal local dev or CI; runs only when the key is present
 * (developer laptops with the key exported, or a dedicated CI job).
 *
 * <p>Success criteria: {@code errored == 0}, {@code succeeded == 1}. The
 * content of the reply is not asserted — this test only cares that the
 * schema is accepted and at least one request produces a structured
 * response.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class BatchSchemaIntegrationTest {

    /** Maximum time to wait for the batch to reach ENDED. */
    private static final Duration POLL_TIMEOUT = Duration.ofMinutes(10);

    /** Delay between poll attempts. */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(15);

    @Test
    void productionSchemaIsAcceptedByAnthropicBatchApi() throws InterruptedException {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();

        PromptBuilder promptBuilder = new PromptBuilder();
        AtmosphericData data = TestAtmosphericData.defaults();

        BatchCreateParams.Request request = BatchCreateParams.Request.builder()
                .customId("schema-guard-1")
                .params(BatchCreateParams.Request.Params.builder()
                        .model(EvaluationModel.HAIKU.getModelId())
                        .maxTokens(EvaluationModel.HAIKU.getMaxTokens())
                        .systemOfTextBlockParams(List.of(
                                TextBlockParam.builder()
                                        .text(promptBuilder.getSystemPrompt())
                                        .cacheControl(CacheControlEphemeral.builder().build())
                                        .build()))
                        .outputConfig(promptBuilder.buildOutputConfig())
                        .addUserMessage(promptBuilder.buildUserMessage(data))
                        .build())
                .build();

        BatchCreateParams params = BatchCreateParams.builder()
                .requests(List.of(request))
                .build();

        MessageBatch batch = client.messages().batches().create(params);
        assertThat(batch.id()).as("Batch creation returned an id").isNotBlank();

        MessageBatch finalStatus = pollUntilEnded(client, batch.id());

        long errored = finalStatus.requestCounts().errored();
        long succeeded = finalStatus.requestCounts().succeeded();
        long expired = finalStatus.requestCounts().expired();
        long canceled = finalStatus.requestCounts().canceled();

        assertThat(errored)
                .as("No request should fail — schema rejection would "
                        + "show up as errored. batchId=%s", batch.id())
                .isZero();
        assertThat(expired).as("Batch should not have expired").isZero();
        assertThat(canceled).as("Batch should not have been canceled").isZero();
        assertThat(succeeded)
                .as("Exactly one request was submitted and should succeed")
                .isEqualTo(1L);
    }

    private MessageBatch pollUntilEnded(AnthropicClient client, String batchId)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(POLL_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            MessageBatch status = client.messages().batches().retrieve(batchId);
            if (status.processingStatus()
                    .equals(MessageBatch.ProcessingStatus.ENDED)) {
                return status;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new AssertionError(
                "Batch " + batchId + " did not reach ENDED within "
                        + POLL_TIMEOUT);
    }
}
