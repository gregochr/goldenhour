package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.gregochr.goldenhour.entity.EvaluationModel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared assertions and fixtures for the per-model request-shape tests. Expected values are
 * written out here as literals (model ids, token ceilings), never derived from the code under
 * test, so a regression in {@link ModelRequestSupport} or {@link EvaluationModel} cannot make
 * its own expectation agree with it.
 */
final class ModelRequestAssertions {

    /** Literal API ids, independent of {@link EvaluationModel#getModelId()}. */
    static final Map<EvaluationModel, String> MODEL_IDS = Map.of(
            EvaluationModel.HAIKU, "claude-haiku-4-5-20251001",
            EvaluationModel.SONNET, "claude-sonnet-4-6",
            EvaluationModel.SONNET_55, "claude-sonnet-5-5");

    /** The thinking allowance added to an answer budget for Sonnet 5.5. */
    static final int THINKING_ALLOWANCE = 4096;

    private ModelRequestAssertions() {
    }

    /**
     * Asserts a message request: model id, token ceiling, effort and the absence of every
     * parameter Sonnet 5.5 rejects. For Sonnet 5.5 the output config carries effort LOW (and the
     * given format when there is one); for any other model there is no effort, and the output
     * config is exactly the format (or absent when there is none).
     */
    static void assertMessage(MessageCreateParams params, EvaluationModel model, long maxTokens,
            Optional<JsonOutputFormat> expectedFormat) {
        assertThat(params.model().asString()).isEqualTo(MODEL_IDS.get(model));
        assertThat(params.maxTokens()).isEqualTo(maxTokens);
        assertThat(params.thinking()).isEmpty();
        assertThat(params.temperature()).isEmpty();
        assertThat(params.toolChoice()).isEmpty();
        assertOutputConfig(params.outputConfig(), model, expectedFormat);
    }

    /** Batch-request counterpart of {@link #assertMessage}. */
    static void assertBatch(BatchCreateParams.Request.Params params, EvaluationModel model,
            long maxTokens, Optional<JsonOutputFormat> expectedFormat) {
        assertThat(params.model().asString()).isEqualTo(MODEL_IDS.get(model));
        assertThat(params.maxTokens()).isEqualTo(maxTokens);
        assertThat(params.thinking()).isEmpty();
        assertThat(params.temperature()).isEmpty();
        assertThat(params.toolChoice()).isEmpty();
        assertOutputConfig(params.outputConfig(), model, expectedFormat);
    }

    private static void assertOutputConfig(Optional<OutputConfig> actual, EvaluationModel model,
            Optional<JsonOutputFormat> expectedFormat) {
        if (model == EvaluationModel.SONNET_55) {
            assertThat(actual).isPresent();
            assertThat(actual.get().effort()).contains(OutputConfig.Effort.LOW);
            assertThat(actual.get().format()).isEqualTo(expectedFormat);
        } else if (expectedFormat.isPresent()) {
            assertThat(actual).isPresent();
            assertThat(actual.get().effort()).isEmpty();
            assertThat(actual.get().format()).isEqualTo(expectedFormat);
        } else {
            assertThat(actual).isEmpty();
        }
    }

    /** A text content block. */
    static ContentBlock text(String value) {
        return ContentBlock.ofText(TextBlock.builder().text(value).citations(List.of()).build());
    }

    /** A thinking content block, as a thinking model returns ahead of its text. */
    static ContentBlock thinking(String value) {
        return ContentBlock.ofThinking(ThinkingBlock.builder().thinking(value).signature("sig").build());
    }

    /** A real message with the given blocks and stop reason, and fixed usage of 10 in / 20 out. */
    static Message message(List<ContentBlock> content, StopReason stopReason) {
        return Message.builder()
                .id("msg_test")
                .model(Model.of("claude-sonnet-5-5"))
                .content(content)
                .stopReason(stopReason)
                .stopSequence(Optional.empty())
                .stopDetails(Optional.empty())
                .usage(Usage.builder()
                        .inputTokens(10)
                        .outputTokens(20)
                        .cacheCreation(CacheCreation.builder()
                                .ephemeral5mInputTokens(0)
                                .ephemeral1hInputTokens(0)
                                .build())
                        .cacheCreationInputTokens(0)
                        .cacheReadInputTokens(0)
                        .inferenceGeo("us")
                        .serverToolUse(ServerToolUsage.builder()
                                .webSearchRequests(0)
                                .webFetchRequests(0)
                                .build())
                        .serviceTier(Usage.ServiceTier.of("standard"))
                        .outputTokensDetails(OutputTokensDetails.builder().thinkingTokens(0).build())
                        .build())
                .build();
    }
}
