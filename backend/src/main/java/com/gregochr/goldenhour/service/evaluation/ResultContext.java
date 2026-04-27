package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.service.batch.BatchTriggerSource;

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
 * @param jobRunId       linked job run id, or {@code null}
 * @param batchId        Anthropic batch id (batch path only), else {@code null}
 * @param triggerSource  what triggered the underlying submission/call
 */
public record ResultContext(Long jobRunId, String batchId,
                            BatchTriggerSource triggerSource) {

    /**
     * Convenience factory for the batch path.
     *
     * @param jobRunId      the linked job run id
     * @param batchId       the Anthropic batch id
     * @param triggerSource what triggered the batch submission
     * @return a context configured for batch result handling
     */
    public static ResultContext forBatch(Long jobRunId, String batchId,
            BatchTriggerSource triggerSource) {
        return new ResultContext(jobRunId, batchId, triggerSource);
    }

    /**
     * Convenience factory for the synchronous path. {@code batchId} is always
     * {@code null} for sync calls.
     *
     * @param jobRunId      the linked job run id
     * @param triggerSource what triggered the synchronous evaluation
     * @return a context configured for sync result handling
     */
    public static ResultContext forSync(Long jobRunId, BatchTriggerSource triggerSource) {
        return new ResultContext(jobRunId, null, triggerSource);
    }
}
