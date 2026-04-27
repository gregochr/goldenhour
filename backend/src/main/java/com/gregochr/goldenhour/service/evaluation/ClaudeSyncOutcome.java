package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.TokenUsage;

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
 * @param errorMessage  human-readable error detail on failure, else {@code null}
 * @param rawText       raw Claude text on success, else {@code null}
 * @param tokenUsage    token counts on success, else {@code null}
 * @param model         {@link EvaluationModel} this call was issued against
 * @param durationMs    wall-clock time in milliseconds for the Anthropic call
 */
public record ClaudeSyncOutcome(
        boolean succeeded,
        String errorType,
        String errorMessage,
        String rawText,
        TokenUsage tokenUsage,
        EvaluationModel model,
        long durationMs
) {

    /**
     * Builds a success outcome.
     */
    public static ClaudeSyncOutcome success(String rawText, TokenUsage tokenUsage,
            EvaluationModel model, long durationMs) {
        return new ClaudeSyncOutcome(true, null, null, rawText, tokenUsage, model, durationMs);
    }

    /**
     * Builds a failure outcome.
     */
    public static ClaudeSyncOutcome failure(String errorType, String errorMessage,
            EvaluationModel model, long durationMs) {
        return new ClaudeSyncOutcome(false, errorType, errorMessage,
                null, null, model, durationMs);
    }
}
