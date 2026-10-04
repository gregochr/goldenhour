package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.exception.ClaudeRefusalException;

/**
 * Per-model request tuning shared by every path that sends an evaluation request to Claude.
 *
 * <p>Sonnet 5.5 thinks by default (it rejects {@code thinking: disabled}), so it is sent
 * {@code output_config.effort = low} alongside any existing output format, and a max-token
 * ceiling large enough for the thinking tokens that count against it. Every other model gets
 * exactly the request it always got: each method here returns its input unchanged for them.
 * Routing all request building through this one class is what keeps the batch primer's request
 * and the real request for a model identical in their output config.
 */
public final class ModelRequestSupport {

    /** Output tokens reserved for adaptive thinking, on top of the answer's own budget. */
    static final int THINKING_ALLOWANCE = 4096;

    private ModelRequestSupport() {
    }

    /**
     * Whether requests for this model carry an explicit low effort.
     *
     * @param model the evaluation model
     * @return true only for Sonnet 5.5
     */
    public static boolean usesLowEffort(EvaluationModel model) {
        return model == EvaluationModel.SONNET_55;
    }

    /**
     * Adds low effort to an existing output config for models that need it.
     *
     * @param base  the output config (format) built by a prompt builder
     * @param model the evaluation model
     * @return {@code base} itself for every model but Sonnet 5.5, else a copy with effort low
     */
    public static OutputConfig withEffort(OutputConfig base, EvaluationModel model) {
        if (!usesLowEffort(model)) {
            return base;
        }
        return base.toBuilder().effort(OutputConfig.Effort.LOW).build();
    }

    /**
     * Adds an effort-only output config to a request that has no output format, for models that
     * need it; a no-op for every other model.
     *
     * @param builder the message request builder
     * @param model   the evaluation model
     * @return the same builder
     */
    public static MessageCreateParams.Builder tune(MessageCreateParams.Builder builder,
            EvaluationModel model) {
        if (usesLowEffort(model)) {
            builder.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build());
        }
        return builder;
    }

    /**
     * Batch-request counterpart of {@link #tune(MessageCreateParams.Builder, EvaluationModel)}.
     *
     * @param builder the batch request params builder
     * @param model   the evaluation model
     * @return the same builder
     */
    public static BatchCreateParams.Request.Params.Builder tune(
            BatchCreateParams.Request.Params.Builder builder, EvaluationModel model) {
        if (usesLowEffort(model)) {
            builder.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build());
        }
        return builder;
    }

    /**
     * Returns the max-token ceiling to send: the answer's own budget plus, for Sonnet 5.5, a
     * thinking allowance (its thinking tokens count against the ceiling, so a budget sized for
     * the answer alone would truncate it). Unchanged for every other model.
     *
     * @param model     the evaluation model
     * @param requested the ceiling the caller would send for any other model
     * @return the ceiling to send
     */
    public static int maxTokens(EvaluationModel model, int requested) {
        return usesLowEffort(model) ? requested + THINKING_ALLOWANCE : requested;
    }

    /**
     * Fails the evaluation when Claude refused or was cut off at {@code max_tokens}: a truncated
     * answer is wrong for any model, so it must never reach a parser that might salvage it.
     *
     * @param response the Claude response
     * @throws ClaudeRefusalException when the stop reason is a refusal
     * @throws com.gregochr.goldenhour.exception.ClaudeReplyUnreadableException when truncated
     */
    public static void checkStopReason(Message response) {
        ClaudeEvaluationStrategy.checkStopReason(response);
    }

    /**
     * Fails the evaluation with a recognisable error when Claude refused (HTTP 200,
     * {@code stop_reason: refusal}).
     *
     * @param response the Claude response
     * @throws ClaudeRefusalException when the stop reason is a refusal
     */
    public static void checkRefusal(Message response) {
        if (StopReason.REFUSAL.equals(response.stopReason().orElse(null))) {
            throw new ClaudeRefusalException("Claude refused the request (stop_reason=refusal)");
        }
    }
}
