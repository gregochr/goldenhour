package com.gregochr.goldenhour.service.batch;

/**
 * Result of {@link BatchRetryService#submitRetry} — how many requests were reconstructed and
 * submitted, and whether the retry batch itself failed to reach Anthropic.
 *
 * <p>Replaces that method's previous plain {@code int} return (the submitted count alone), which
 * could not tell "nothing to retry" (nothing reconstructed, or an idempotent re-entry) apart from
 * "genuinely tried and the submission itself failed" — both read as {@code 0}. {@link
 * com.gregochr.goldenhour.service.pipeline.PipelineOrchestrator} needs that distinction to decide
 * whether the {@code RETRY_FAILED} phase should be marked FAILED (which, alongside {@code
 * FORECAST_BATCH_SUBMIT}, feeds the {@code DEGRADED} run status).
 *
 * @param submittedCount how many requests were actually submitted — {@code 0} whenever
 *                        {@code batchId} is null
 * @param taskCount      how many requests were reconstructed and attempted, whether or not the
 *                        submission itself then succeeded — {@code 0} when nothing could be
 *                        reconstructed, the selection was not RETRY, or a retry batch already
 *                        existed for the cycle (idempotent re-entry); this is what distinguishes
 *                        "nothing to retry" from "tried and failed"
 * @param batchId        the Anthropic batch id, or {@code null} when nothing was submitted or the
 *                        submission failed
 */
public record RetrySubmitResult(int submittedCount, int taskCount, String batchId) {

    /** @return the result for every case where no submission was even attempted. */
    public static RetrySubmitResult none() {
        return new RetrySubmitResult(0, 0, null);
    }

    /**
     * @return {@code true} when there were genuine requests to retry but none reached Anthropic —
     *         a real submission failure, as opposed to {@code taskCount == 0} ("nothing to
     *         retry", which is not a failure of anything)
     */
    public boolean submissionFailed() {
        return taskCount > 0 && batchId == null;
    }
}
