package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.CacheDiagnostics;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.EvaluationFailure;

/**
 * Drained-of-SDK-types view of one Anthropic Messages API response, handed to a
 * {@link ResultHandler#handleSyncResult} call.
 *
 * <p>Mirrors the shape of {@link ClaudeBatchOutcome} so handlers can keep their
 * batch and sync logic close together. {@code durationMs} is populated by the
 * engine because the sync path measures end-to-end latency for cost/observability;
 * the batch path doesn't have a per-request duration to surface.
 *
 * @param succeeded     true when Claude returned a parseable response
 * @param errorType     short Anthropic error type on failure, else {@code null}
 * @param statusCode    HTTP status Anthropic answered with when the failure was an Anthropic service
 *                      error, else {@code null} (a success, a connection failure, an unreadable reply,
 *                      a refusal, a breaker or bulkhead refusal: none has an HTTP status of its own)
 * @param errorMessage  human-readable error detail on failure, else {@code null}
 * @param rawText       raw Claude text on success, else {@code null}
 * @param tokenUsage    token counts on success, else {@code null}
 * @param model         {@link EvaluationModel} this call was issued against
 * @param durationMs    wall-clock time in milliseconds for the Anthropic call
 * @param cacheDiagnostics the prompt-cache diagnostics the response carried, else {@code null} or
 *                      {@link CacheDiagnostics#EMPTY}; kept beside the token counts, never in them
 */
public record ClaudeSyncOutcome(
        boolean succeeded,
        String errorType,
        Integer statusCode,
        String errorMessage,
        String rawText,
        TokenUsage tokenUsage,
        EvaluationModel model,
        long durationMs,
        CacheDiagnostics cacheDiagnostics
) {

    /**
     * Constructs an outcome whose response carried no cache diagnostics.
     *
     * @param succeeded    true when Claude returned a parseable response
     * @param errorType    short Anthropic error type on failure, else {@code null}
     * @param statusCode   HTTP status on an Anthropic service error, else {@code null}
     * @param errorMessage human-readable error detail on failure, else {@code null}
     * @param rawText      raw Claude text on success, else {@code null}
     * @param tokenUsage   token counts on success, else {@code null}
     * @param model        the model this call was issued against
     * @param durationMs   wall-clock time in milliseconds for the Anthropic call
     */
    public ClaudeSyncOutcome(boolean succeeded, String errorType, Integer statusCode,
            String errorMessage, String rawText, TokenUsage tokenUsage, EvaluationModel model,
            long durationMs) {
        this(succeeded, errorType, statusCode, errorMessage, rawText, tokenUsage, model, durationMs, null);
    }

    /** The HTTP status a successful call is logged with. */
    private static final int HTTP_OK = 200;

    /**
     * The status the call is written to {@code api_call_log} with: 200 for a success, otherwise the
     * HTTP status Anthropic answered with, or {@code null} when the failure had none (see
     * {@link #statusCode}). A {@code null} status on a row with {@code succeeded = false} is how every
     * non-HTTP failure is already recorded (the batch path, the weather clients); nothing reads the
     * status to decide whether a call failed, they all read {@code succeeded}.
     *
     * @return the status to log
     */
    public Integer loggedStatusCode() {
        return succeeded ? Integer.valueOf(HTTP_OK) : statusCode;
    }

    /**
     * Builds a success outcome.
     */
    public static ClaudeSyncOutcome success(String rawText, TokenUsage tokenUsage,
            EvaluationModel model, long durationMs) {
        return new ClaudeSyncOutcome(true, null, null, null, rawText, tokenUsage, model, durationMs);
    }

    /**
     * Builds a success outcome carrying the response's prompt-cache diagnostics.
     */
    public static ClaudeSyncOutcome success(String rawText, TokenUsage tokenUsage,
            EvaluationModel model, long durationMs, CacheDiagnostics cacheDiagnostics) {
        return new ClaudeSyncOutcome(true, null, null, null, rawText, tokenUsage, model, durationMs,
                cacheDiagnostics);
    }

    /**
     * Builds a failure outcome for a failure that has no exception to classify (a reply the parser
     * rejected) and so no HTTP status.
     */
    public static ClaudeSyncOutcome failure(String errorType, String errorMessage,
            EvaluationModel model, long durationMs) {
        return new ClaudeSyncOutcome(false, errorType, null, errorMessage,
                null, null, model, durationMs);
    }

    /**
     * Builds a failure outcome from what the Claude call or its reply handling threw. The
     * {@code errorType} and the HTTP status both come from the exception through
     * {@link EvaluationFailure}, so what is logged and what the run panel reads cannot drift apart.
     */
    public static ClaudeSyncOutcome failure(Exception cause, EvaluationModel model, long durationMs) {
        return new ClaudeSyncOutcome(false, EvaluationFailure.errorTypeOf(cause),
                EvaluationFailure.httpStatusOf(cause), cause.getMessage(),
                null, null, model, durationMs);
    }
}
