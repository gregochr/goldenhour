package com.gregochr.goldenhour.service.evaluation;

/**
 * Per-task-type strategy that turns a Claude response into the writes the rest of the
 * system expects (cache rows, {@code api_call_log}, {@code evaluation_delta_log},
 * in-memory state cache, ...).
 *
 * <p>This interface only declares the {@link #handleSyncResult} entry point because the
 * batch-path entry points have type-specific signatures: forecast batch responses arrive
 * once-per-location and feed into a per-cache-key aggregator
 * ({@link ForecastResultHandler#parseBatchResponse} +
 * {@link ForecastResultHandler#flushCacheKey}); aurora batch responses arrive once total
 * and trigger an at-result-time re-triage
 * ({@link AuroraResultHandler#processBatchResponse}). Forcing both into a uniform method
 * shape would erase the asymmetry that the orchestration logic in
 * {@link com.gregochr.goldenhour.service.batch.BatchResultProcessor} legitimately needs.
 *
 * <p>{@link EvaluationServiceImpl#evaluateNow} dispatches by {@link #taskType} to find
 * the right handler at runtime — so adding a future task type is a single new handler
 * implementation, no engine changes.
 *
 * <p>Handlers must be exception-safe — any failure during a handler invocation should
 * be logged and contained, never propagated to the engine. The engine treats one
 * task's handler failure as independent of the others in a batch.
 *
 * @param <T> concrete task subtype this handler accepts
 */
public interface ResultHandler<T extends EvaluationTask> {

    /**
     * Returns the concrete task class this handler dispatches on. Used by
     * {@link EvaluationServiceImpl} to route synchronous tasks to the right handler at
     * runtime without {@code instanceof} chains in the engine.
     *
     * @return the concrete task subclass
     */
    Class<T> taskType();

    /**
     * Handles one Anthropic Messages API (synchronous) response.
     *
     * <p>Performs the same writes the batch path would (subject to the per-handler
     * specifics): typically {@code api_call_log} with {@code is_batch=false}, plus the
     * per-task-type result destination ({@code cached_evaluation},
     * {@link com.gregochr.goldenhour.service.aurora.AuroraStateCache}, ...).
     *
     * @param task     original task
     * @param outcome  drained-of-SDK-types view of the synchronous Anthropic response
     * @param context  per-call context (job run id) used for observability writes
     * @return the structured result for the caller (Scored / Errored)
     */
    EvaluationResult handleSyncResult(T task, ClaudeSyncOutcome outcome, ResultContext context);
}
