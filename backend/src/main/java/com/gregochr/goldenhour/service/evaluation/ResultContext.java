package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.service.batch.BatchTriggerSource;

import java.time.Instant;

/**
 * Per-call observability context passed to {@link ResultHandler} invocations.
 *
 * <p>Carries the linked {@link com.gregochr.goldenhour.entity.JobRunEntity} id (or
 * {@code null} when not available — e.g. a {@code BatchSubmissionService.submit}
 * failure landed before the job-run row was created) plus the Anthropic batch id
 * (only set on the batch path) and the trigger source.
 *
 * <p>The handler uses this to write {@code api_call_log} rows with the right
 * {@code job_run_id} / {@code batch_id} / {@code is_batch} fields without the
 * task itself having to know about job-run plumbing.
 *
 * @param jobRunId          linked job run id, or {@code null}
 * @param batchId           Anthropic batch id (batch path only), else {@code null}
 * @param pipelineRunId     the orchestrated pipeline run id (batch path only), else {@code null} —
 *                          threaded to the Pass 2 {@code forecast_score} dual-write as provenance
 * @param submissionInstant round 12: the instant this result's EVALUATION was submitted — for a
 *                          batch belonging to an orchestrated cycle, that cycle's own {@code
 *                          PipelineRunEntity.triggerTime} (shared identically by every batch the
 *                          cycle submits, including a retry, and stable across the whole cycle);
 *                          for an ad-hoc batch outside any cycle, that batch's own {@code
 *                          ForecastBatchEntity.submittedAt}; for a synchronous/admin evaluation,
 *                          the instant the call started. {@code null} for a context that predates
 *                          this field or has no meaningful submission instant to report (e.g. the
 *                          aurora path, which does not write into {@code cached_evaluation}'s
 *                          per-location merge and has no staleness rule to feed). Threaded onto
 *                          {@link com.gregochr.goldenhour.model.BriefingEvaluationResult
 *                          #submittedAt} by {@code ForecastResultHandler}'s three {@code buildXxx}
 *                          methods — see that field's own javadoc for the full staleness rule this
 *                          value ultimately backs
 * @param triggerSource     what triggered the underlying submission/call
 */
public record ResultContext(Long jobRunId, String batchId, Long pipelineRunId,
                            Instant submissionInstant, BatchTriggerSource triggerSource) {

    /**
     * Convenience factory for the batch path with no pipeline run linkage (ad-hoc submissions)
     * and no submission instant — retained so every pre-round-12 call site and test fixture keeps
     * compiling unchanged. A result built through this context carries a {@code null submittedAt},
     * which the staleness rule treats as "unknown, never stale" — see {@code
     * BriefingEvaluationService}'s own javadoc.
     *
     * @param jobRunId      the linked job run id
     * @param batchId       the Anthropic batch id
     * @param triggerSource what triggered the batch submission
     * @return a context configured for batch result handling, with a {@code null} pipeline run id
     */
    public static ResultContext forBatch(Long jobRunId, String batchId,
            BatchTriggerSource triggerSource) {
        return new ResultContext(jobRunId, batchId, null, null, triggerSource);
    }

    /**
     * Convenience factory for the batch path linked to an orchestrated pipeline run, with no
     * submission instant — retained for the same reason as the three-arg overload above.
     *
     * @param jobRunId      the linked job run id
     * @param batchId       the Anthropic batch id
     * @param pipelineRunId the orchestrated pipeline run id (forecast_score provenance), or null
     * @param triggerSource what triggered the batch submission
     * @return a context configured for batch result handling
     */
    public static ResultContext forBatch(Long jobRunId, String batchId, Long pipelineRunId,
            BatchTriggerSource triggerSource) {
        return new ResultContext(jobRunId, batchId, pipelineRunId, null, triggerSource);
    }

    /**
     * Convenience factory for the batch path linked to an orchestrated pipeline run, carrying the
     * round-12 submission instant — the shape {@code BatchResultProcessor} uses for a real
     * forecast batch.
     *
     * @param jobRunId          the linked job run id
     * @param batchId           the Anthropic batch id
     * @param pipelineRunId     the orchestrated pipeline run id, or null for an ad-hoc submission
     * @param submissionInstant this result's submission instant — see the field's own javadoc
     * @param triggerSource     what triggered the batch submission
     * @return a context configured for batch result handling
     */
    public static ResultContext forBatch(Long jobRunId, String batchId, Long pipelineRunId,
            Instant submissionInstant, BatchTriggerSource triggerSource) {
        return new ResultContext(jobRunId, batchId, pipelineRunId, submissionInstant, triggerSource);
    }

    /**
     * Convenience factory for the synchronous path with no submission instant — retained so every
     * pre-round-12 call site and test fixture keeps compiling unchanged. {@code batchId} and
     * {@code pipelineRunId} are always {@code null} for sync calls — the admin/sync path has no
     * pipeline run.
     *
     * @param jobRunId      the linked job run id
     * @param triggerSource what triggered the synchronous evaluation
     * @return a context configured for sync result handling
     */
    public static ResultContext forSync(Long jobRunId, BatchTriggerSource triggerSource) {
        return new ResultContext(jobRunId, null, null, null, triggerSource);
    }

    /**
     * Convenience factory for the synchronous path, carrying the round-12 submission instant —
     * the instant the evaluation call started, captured by the caller before it dispatches to
     * Claude.
     *
     * @param jobRunId          the linked job run id
     * @param submissionInstant the instant this synchronous evaluation started
     * @param triggerSource     what triggered the synchronous evaluation
     * @return a context configured for sync result handling
     */
    public static ResultContext forSync(Long jobRunId, Instant submissionInstant,
            BatchTriggerSource triggerSource) {
        return new ResultContext(jobRunId, null, null, submissionInstant, triggerSource);
    }
}
