package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.CacheDiagnostics;
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
 * @param cacheDiagnostics the prompt-cache diagnostics the response carried, else {@code null}
 *                      or {@link CacheDiagnostics#EMPTY}; kept beside the token counts, never in them
 */
public record ClaudeBatchOutcome(
        String customId,
        boolean succeeded,
        String status,
        String errorType,
        String errorMessage,
        String rawText,
        TokenUsage tokenUsage,
        EvaluationModel model,
        CacheDiagnostics cacheDiagnostics
) {

    /**
     * Constructs an outcome whose response carried no cache diagnostics.
     *
     * @param customId     the Anthropic per-request custom id
     * @param succeeded    true when this row produced a parseable Claude response
     * @param status       short status string
     * @param errorType    Anthropic error type on failure, else {@code null}
     * @param errorMessage human-readable error detail on failure, else {@code null}
     * @param rawText      raw Claude text on success, else {@code null}
     * @param tokenUsage   token counts when Anthropic returned them, else {@code null}
     * @param model        the model parsed from the response, else {@code null}
     */
    public ClaudeBatchOutcome(String customId, boolean succeeded, String status, String errorType,
            String errorMessage, String rawText, TokenUsage tokenUsage, EvaluationModel model) {
        this(customId, succeeded, status, errorType, errorMessage, rawText, tokenUsage, model, null);
    }

    /**
     * Builds a success outcome.
     */
    public static ClaudeBatchOutcome success(String customId, String rawText,
            TokenUsage tokenUsage, EvaluationModel model) {
        return success(customId, rawText, tokenUsage, model, null);
    }

    /**
     * Builds a success outcome carrying the response's prompt-cache diagnostics.
     */
    public static ClaudeBatchOutcome success(String customId, String rawText,
            TokenUsage tokenUsage, EvaluationModel model, CacheDiagnostics cacheDiagnostics) {
        return new ClaudeBatchOutcome(customId, true, "SUCCESS",
                null, null, rawText, tokenUsage, model, cacheDiagnostics);
    }

    /**
     * Builds a failure outcome for a response Anthropic did bill (refusal, truncation, no text),
     * carrying that response's usage so the failed attempt can still be costed.
     */
    public static ClaudeBatchOutcome failure(String customId, String status,
            String errorType, String errorMessage, TokenUsage billedUsage) {
        return new ClaudeBatchOutcome(customId, false, status,
                errorType, errorMessage, null, billedUsage, null);
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
