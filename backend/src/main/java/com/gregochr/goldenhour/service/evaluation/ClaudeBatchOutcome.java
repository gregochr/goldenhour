package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.TokenUsage;

/**
 * Drained-of-SDK-types view of one Anthropic Batch API individual response, handed
 * to a {@link ResultHandler#handleBatchResult} call.
 *
 * <p>The two shapes (success and error) are unified into a single record so the
 * handler can dispatch on {@link #succeeded()} without juggling sealed-interface
 * boilerplate. {@code rawText} is set on success only; {@code errorType} and
 * {@code errorMessage} on failure only. Token fields are populated whenever
 * Anthropic returned them (success or error).
 *
 * <p>Outcomes are constructed by {@link BatchResultProcessor} immediately after
 * the SDK individual-response stream is read, so handlers never see Anthropic SDK
 * objects directly — improves testability of {@link ForecastResultHandler} and
 * {@link AuroraResultHandler}.
 *
 * @param customId      the Anthropic per-request custom id
 * @param succeeded     true when this row produced a parseable Claude response
 * @param status        short status string ({@code "SUCCESS"}, {@code "OVERLOADED_ERROR"},
 *                      {@code "EXPIRED"}, ...)
 * @param errorType     Anthropic error type on failure, else {@code null}
 * @param errorMessage  human-readable error detail on failure, else {@code null}
 * @param rawText       raw Claude text on success, else {@code null}
 * @param tokenUsage    token counts when Anthropic returned them, else {@code null}
 * @param model         {@link EvaluationModel} parsed from the response, else {@code null}
 */
public record ClaudeBatchOutcome(
        String customId,
        boolean succeeded,
        String status,
        String errorType,
        String errorMessage,
        String rawText,
        TokenUsage tokenUsage,
        EvaluationModel model
) {

    /**
     * Builds a success outcome.
     */
    public static ClaudeBatchOutcome success(String customId, String rawText,
            TokenUsage tokenUsage, EvaluationModel model) {
        return new ClaudeBatchOutcome(customId, true, "SUCCESS",
                null, null, rawText, tokenUsage, model);
    }

    /**
     * Builds a failure outcome (errored / expired / canceled / parse failure).
     */
    public static ClaudeBatchOutcome failure(String customId, String status,
            String errorType, String errorMessage) {
        return new ClaudeBatchOutcome(customId, false, status,
                errorType, errorMessage, null, null, null);
    }
}
