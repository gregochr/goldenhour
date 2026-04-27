package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.service.batch.BatchTriggerSource;

import java.util.List;

/**
 * Single orchestration layer for Anthropic-backed evaluations.
 *
 * <p>Two methods, one for each Anthropic transport:
 * <ul>
 *   <li>{@link #submit} — asynchronous Anthropic Batch API path. Used by the overnight
 *       scheduled forecast batch and the aurora batch. ~50% cost discount vs the
 *       Messages API. Latency: minutes (subject to {@link
 *       com.gregochr.goldenhour.service.batch.BatchPollingService}'s 60s polling).</li>
 *   <li>{@link #evaluateNow} — synchronous Anthropic Messages API path. Used by aurora
 *       real-time and (in Pass 3.3) by the admin "Run Forecast" pipeline. Pays full per-token
 *       rate (~2x batch cost), but returns within seconds. Intentionally low-volume.</li>
 * </ul>
 *
 * <p>Both methods consume the same {@link EvaluationTask} sealed type and route results
 * through the same {@link ResultHandler} per task type, so cost and latency are transport
 * concerns rather than result-shape concerns.
 *
 * <p>The engine is intentionally narrow: it doesn't know what a "forecast" is or what
 * "aurora" is — it dispatches by sealed-type pattern matching to per-task strategies and
 * handlers. Adding a new {@link EvaluationTask} variant requires a new {@link ResultHandler}
 * implementation but no engine changes.
 */
public interface EvaluationService {

    /**
     * Submits a homogeneous list of tasks (all the same concrete subtype) to the
     * Anthropic Batch API. Returns a handle the caller can use to correlate later
     * polling/result-processing telemetry.
     *
     * <p>Empty task list returns {@link EvaluationHandle#empty}; an Anthropic submission
     * failure returns {@link EvaluationHandle#empty} (the underlying
     * {@link com.gregochr.goldenhour.service.batch.BatchSubmissionService} has already
     * logged the failure). The engine never throws when the submission cannot proceed.
     *
     * <p>Mixed-type lists are rejected with {@link IllegalArgumentException} —
     * forecast and aurora batches use different prompt builders and Anthropic models,
     * so collapsing them into one batch makes no business sense.
     *
     * @param tasks    one or more tasks, all the same concrete type
     * @param trigger  what triggered this submission (logged in {@code job_run})
     * @return a handle with the submitted batch id, or {@link EvaluationHandle#empty}
     *         if no submission occurred
     */
    EvaluationHandle submit(List<? extends EvaluationTask> tasks, BatchTriggerSource trigger);

    /**
     * Synchronously evaluates a single task via the Anthropic Messages API and dispatches
     * the result through the per-task-type {@link ResultHandler}.
     *
     * <p>Cost: full per-token rate (no batch discount). Latency: typically 5-25 seconds.
     * Errors do not throw — they are wrapped in {@link EvaluationResult.Errored} so the
     * caller can decide whether to retry or fall back.
     *
     * @param task     the task to evaluate
     * @param trigger  what triggered this evaluation (logged in {@code job_run})
     * @return the structured evaluation result
     */
    EvaluationResult evaluateNow(EvaluationTask task, BatchTriggerSource trigger);
}
