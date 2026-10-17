package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.exception.ClaudeRefusalException;

/**
 * Per-model request tuning shared by every path that sends an evaluation request to Claude.
 *
 * <p>The two 5.5 models differ on one rule, and the difference is deliberate. Sonnet 5.5 thinks
 * by default and rejects {@code thinking: disabled}, so it is sent {@code output_config.effort =
 * low} alongside any existing output format, and a max-token ceiling large enough for the
 * thinking tokens that count against it. Haiku 5.5 also thinks by default but accepts
 * {@code thinking: disabled} (at effort low, medium or high), so it is sent thinking disabled plus
 * effort low, which makes it behave like Haiku 4.5: no thinking tokens, so its ceiling is
 * unchanged. Neither accepts a non-default temperature, top-p, top-k, a {@code budget_tokens}
 * thinking config or an assistant prefill, and none of these paths sends one. Every other model
 * gets exactly the request it always got: each method here returns its input unchanged for them.
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
     * @return true for Sonnet 5.5 and Haiku 5.5
     */
    public static boolean usesLowEffort(EvaluationModel model) {
        return model == EvaluationModel.SONNET_55 || model == EvaluationModel.HAIKU_55;
    }

    /**
     * Whether requests for this model carry {@code thinking: disabled}. Sonnet 5.5 rejects it, so
     * only Haiku 5.5 does.
     *
     * @param model the evaluation model
     * @return true only for Haiku 5.5
     */
    public static boolean disablesThinking(EvaluationModel model) {
        return model == EvaluationModel.HAIKU_55;
    }

    /**
     * Whether this model thinks adaptively in its requests, so that thinking tokens count against
     * {@code max_tokens} and the ceiling must leave room for them.
     *
     * @param model the evaluation model
     * @return true only for Sonnet 5.5
     */
    public static boolean thinksAdaptively(EvaluationModel model) {
        return model == EvaluationModel.SONNET_55;
    }

    /**
     * Adds low effort to an existing output config for models that need it.
     *
     * @param base  the output config (format) built by a prompt builder
     * @param model the evaluation model
     * @return {@code base} itself for every model but the 5.5 pair, else a copy with effort low
     */
    public static OutputConfig withEffort(OutputConfig base, EvaluationModel model) {
        if (!usesLowEffort(model)) {
            return base;
        }
        return base.toBuilder().effort(OutputConfig.Effort.LOW).build();
    }

    /**
     * Sets {@code thinking: disabled} on a message request for models that need it, leaving the
     * output config alone. For request builders that already set their own output format through
     * {@link #withEffort}; {@link #tune(MessageCreateParams.Builder, EvaluationModel)} calls it too.
     *
     * @param builder the message request builder
     * @param model   the evaluation model
     * @return the same builder
     */
    public static MessageCreateParams.Builder withThinkingSetting(MessageCreateParams.Builder builder,
            EvaluationModel model) {
        if (disablesThinking(model)) {
            builder.thinking(ThinkingConfigDisabled.builder().build());
        }
        return builder;
    }

    /**
     * Batch-request counterpart of
     * {@link #withThinkingSetting(MessageCreateParams.Builder, EvaluationModel)}.
     *
     * @param builder the batch request params builder
     * @param model   the evaluation model
     * @return the same builder
     */
    public static BatchCreateParams.Request.Params.Builder withThinkingSetting(
            BatchCreateParams.Request.Params.Builder builder, EvaluationModel model) {
        if (disablesThinking(model)) {
            builder.thinking(ThinkingConfigDisabled.builder().build());
        }
        return builder;
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
        return withThinkingSetting(builder, model);
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
        return withThinkingSetting(builder, model);
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
        return thinksAdaptively(model) ? requested + THINKING_ALLOWANCE : requested;
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
