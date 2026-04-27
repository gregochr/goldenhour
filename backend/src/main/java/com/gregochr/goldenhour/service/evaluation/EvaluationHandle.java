package com.gregochr.goldenhour.service.evaluation;

/**
 * Returned by {@link EvaluationService#submit} once tasks have been packaged into an
 * Anthropic batch and submitted. The caller can use {@code batchId} to correlate later
 * polling/result-processing telemetry; {@code submittedCount} is the number of requests
 * inside the batch (not the number of locations — for aurora this is always 1).
 *
 * <p>An empty task list (or an Anthropic submission failure) returns a handle with
 * {@code batchId=null} and {@code submittedCount=0}; the engine never throws when there is
 * nothing to do.
 *
 * @param jobRunId        job run row created for the batch, or {@code null} when no
 *                        submission occurred
 * @param batchId         Anthropic batch id, or {@code null} when no submission occurred
 * @param submittedCount  number of requests successfully submitted; {@code 0} on no-op
 */
public record EvaluationHandle(Long jobRunId, String batchId, int submittedCount) {

    /**
     * Convenience factory for the no-op case (empty task list or Anthropic submission
     * failure that the underlying {@link com.gregochr.goldenhour.service.batch.BatchSubmissionService}
     * has already logged).
     */
    public static EvaluationHandle empty() {
        return new EvaluationHandle(null, null, 0);
    }
}
