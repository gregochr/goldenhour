package com.gregochr.goldenhour.model;

import java.time.LocalDateTime;

/**
 * Progress of a cloud-verification backfill run.
 *
 * <p>The backfill works through a backlog of tens of thousands of evaluations, which is far too
 * long for one HTTP request — a synchronous call is killed by the reverse proxy long before it
 * finishes, and the caller cannot tell a timeout from a failure. The run is therefore started in
 * the background and polled through this status.
 *
 * <p>Held in memory only. A restart mid-run loses the status but not the work: every batch commits
 * as it completes and candidate selection is an anti-join, so a fresh run simply resumes where the
 * last one stopped.
 *
 * @param running       whether a run is in progress
 * @param verified      evaluations verified so far in this run
 * @param batches       batches completed in this run
 * @param remaining     unverified evaluations left at the last count, or {@code null} if unknown
 * @param startedAt     when the run started, or {@code null} if none has run
 * @param finishedAt    when the run ended, or {@code null} while running
 * @param lastError     the error that stopped the run, or {@code null} if it ended cleanly
 */
public record BackfillStatus(
        boolean running,
        int verified,
        int batches,
        Integer remaining,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String lastError) {

    /**
     * Returns the idle status used before any run has been started.
     *
     * @return an idle status with no history
     */
    public static BackfillStatus idle() {
        return new BackfillStatus(false, 0, 0, null, null, null, null);
    }

    /**
     * Returns a copy marking the run as started.
     *
     * @param startedAt when the run began
     * @param remaining unverified evaluations at the start, or {@code null} if unknown
     * @return a running status
     */
    public static BackfillStatus started(LocalDateTime startedAt, Integer remaining) {
        return new BackfillStatus(true, 0, 0, remaining, startedAt, null, null);
    }

    /**
     * Returns a copy advanced by one completed batch.
     *
     * @param batchVerified evaluations verified in the batch
     * @param remainingNow  unverified evaluations left, or {@code null} if unknown
     * @return the updated running status
     */
    public BackfillStatus withBatch(int batchVerified, Integer remainingNow) {
        return new BackfillStatus(true, verified + batchVerified, batches + 1,
                remainingNow, startedAt, null, null);
    }

    /**
     * Returns a copy marking the run finished.
     *
     * @param finishedAt when the run ended
     * @param error      the error that stopped it, or {@code null} if it ended cleanly
     * @return the finished status
     */
    public BackfillStatus finished(LocalDateTime finishedAt, String error) {
        return new BackfillStatus(false, verified, batches, remaining,
                startedAt, finishedAt, error);
    }
}
