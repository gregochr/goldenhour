package com.gregochr.goldenhour.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.evaluation.ClaudeBatchOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin, testable wrapper over the three Anthropic Batch API calls the sky-rating eval needs:
 * submit, check completion, and collect results. It exists so {@link SkyRatingEvalBatchService}
 * can orchestrate against plain records ({@link ClaudeBatchOutcome}) and a boolean, mocking this
 * seam rather than the deep SDK batch types.
 *
 * <p>Deliberately lighter than the forecast pipeline's {@code BatchSubmissionService} /
 * {@code BatchPollingService} / {@code BatchResultProcessor}: those are coupled to
 * {@code ForecastBatchEntity} persistence, dispositions, and pipeline runs. The eval is a small,
 * self-contained weekly job (fixtures × runs × models = a few hundred requests). Like the forecast
 * poller, its batch id is persisted on the run and reconciled by a scheduled job (see
 * {@link SkyRatingEvalBatchService}), so the completion check here is a single non-blocking call.
 */
@Service
public class SkyRatingEvalBatchClient {

    private static final Logger LOG = LoggerFactory.getLogger(SkyRatingEvalBatchClient.class);

    private final AnthropicClient anthropicClient;

    /**
     * Constructs the client.
     *
     * @param anthropicClient the raw Anthropic SDK client
     */
    public SkyRatingEvalBatchClient(AnthropicClient anthropicClient) {
        this.anthropicClient = anthropicClient;
    }

    /**
     * Submits the requests as a single Anthropic message batch.
     *
     * @param requests the batch requests (must be non-empty)
     * @return the Anthropic batch id
     */
    public String submit(List<BatchCreateParams.Request> requests) {
        MessageBatch batch = anthropicClient.messages().batches().create(
                BatchCreateParams.builder().requests(requests).build());
        LOG.info("Sky-rating eval batch submitted: batchId={}, {} request(s), expires={}",
                batch.id(), requests.size(), batch.expiresAt());
        return batch.id();
    }

    /**
     * Checks — in a single, non-blocking status call — whether the batch has finished processing.
     *
     * <p>Anthropic always drives a batch to {@code ENDED} once processing finishes (even if every
     * request errored), so this one terminal state is sufficient to decide the batch is ready to
     * collect. The scheduled reconciler ({@code SkyRatingEvalBatchService}) calls this once per tick
     * instead of blocking a thread on an await loop, so a restart cannot lose an in-flight batch.
     *
     * @param batchId the Anthropic batch id
     * @return {@code true} if the batch's processing status is {@code ENDED}
     */
    public boolean isEnded(String batchId) {
        MessageBatch.ProcessingStatus status =
                anthropicClient.messages().batches().retrieve(batchId).processingStatus();
        LOG.debug("Sky-rating eval batch {} processing status {}", batchId, status);
        return status.equals(MessageBatch.ProcessingStatus.ENDED);
    }

    /**
     * Streams the batch's individual responses and drains each into a {@link ClaudeBatchOutcome}.
     *
     * @param batchId the Anthropic batch id (must have ended)
     * @return one outcome per response, in stream order
     */
    public List<ClaudeBatchOutcome> collectResults(String batchId) {
        List<ClaudeBatchOutcome> outcomes = new ArrayList<>();
        try (var streamResp = anthropicClient.messages().batches().resultsStreaming(batchId)) {
            for (MessageBatchIndividualResponse response : (Iterable<MessageBatchIndividualResponse>)
                    streamResp.stream()::iterator) {
                outcomes.add(toOutcome(response));
            }
        }
        return outcomes;
    }

    private static ClaudeBatchOutcome toOutcome(MessageBatchIndividualResponse response) {
        String customId = response.customId();
        if (!response.result().isSucceeded()) {
            return ClaudeBatchOutcome.failure(customId, "ERRORED", "batch_error",
                    "request did not succeed");
        }
        Message message = response.result().succeeded()
                .map(succeeded -> succeeded.message())
                .orElse(null);
        if (message == null) {
            return ClaudeBatchOutcome.failure(customId, "NO_MESSAGE", "extraction_error",
                    "succeeded but no message");
        }
        String text = message.content().stream()
                .filter(ContentBlock::isText)
                .map(ContentBlock::asText)
                .map(TextBlock::text)
                .findFirst()
                .orElse(null);
        if (text == null) {
            return ClaudeBatchOutcome.failure(customId, "NO_TEXT", "extraction_error",
                    "no text content blocks");
        }
        Usage usage = message.usage();
        TokenUsage tokens = new TokenUsage(
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L));
        return ClaudeBatchOutcome.success(customId, text, tokens, null);
    }
}
