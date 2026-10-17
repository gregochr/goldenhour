package com.gregochr.goldenhour.service.batch;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Per-bucket submission accounting for one forecast batch cycle — how many buckets the cycle
 * attempted to submit to the Anthropic Batch API, how many actually reached it, and which ones
 * did not (label + request count).
 *
 * <p>Born from the 2026-09-29 incident (pipeline run 249): the {@code [BATCH DIAG]} log line
 * already distinguished "Submitted" from "NOT submitted" per bucket, but nothing surfaced that
 * fact past the log — the orchestrator had no way to know, so it recorded the cycle
 * {@code COMPLETED} even though every one of its three batches had failed to submit. This is the
 * seam that carries the fact from {@link ScheduledBatchEvaluationService#submitBuckets} up to
 * {@code PipelineOrchestrator}, which uses {@link #allSucceeded()} to decide between completing
 * the {@code FORECAST_BATCH_SUBMIT} phase and failing it (and, at the end of the cycle, between
 * {@code PipelineRunStatus#COMPLETED} and {@code DEGRADED}).
 *
 * @param bucketsAttempted every non-empty bucket this cycle tried to submit
 * @param bucketsSubmitted the subset that actually reached Anthropic (non-null batch id) —
 *                         {@code bucketsAttempted - bucketsSubmitted == failedBuckets.size()}
 * @param failedBuckets    one entry per bucket that did NOT reach Anthropic, naming the bucket
 *                         and how many requests it held; empty when every attempted bucket
 *                         succeeded (including the trivial case of no buckets attempted at all)
 */
public record BatchSubmissionSummary(
        int bucketsAttempted, int bucketsSubmitted, List<FailedBucket> failedBuckets) {

    /**
     * One bucket whose submission failed.
     *
     * @param label        the bucket's human label (e.g. {@code "near-term inland"})
     * @param requestCount how many requests were in the bucket
     */
    public record FailedBucket(String label, int requestCount) {
    }

    /** @return the summary for a cycle where no bucket had any tasks to submit. */
    public static BatchSubmissionSummary allEmpty() {
        return new BatchSubmissionSummary(0, 0, List.of());
    }

    /**
     * @return {@code true} when every attempted bucket reached Anthropic (including the trivial
     *         zero-bucket case — nothing to submit is not a failure)
     */
    public boolean allSucceeded() {
        return failedBuckets.isEmpty();
    }

    /**
     * @return the total requests lost across every failed bucket — none of these reached Claude
     */
    public int lostRequestCount() {
        return failedBuckets.stream().mapToInt(FailedBucket::requestCount).sum();
    }

    /**
     * Human summary for the {@code FORECAST_BATCH_SUBMIT} phase's failure detail and the run's
     * {@code DEGRADED} {@code failureReason}, e.g. {@code "2 of 3 forecast batch submissions
     * failed (340 requests not submitted — near-term inland, far-term coastal)"}.
     *
     * @return the detail string, or {@code null} when nothing failed (nothing to say)
     */
    public String detail() {
        if (allSucceeded()) {
            return null;
        }
        String buckets = failedBuckets.stream()
                .map(FailedBucket::label)
                .collect(Collectors.joining(", "));
        return failedBuckets.size() + " of " + bucketsAttempted
                + " forecast batch submissions failed (" + lostRequestCount()
                + " requests not submitted — " + buckets + ")";
    }
}
