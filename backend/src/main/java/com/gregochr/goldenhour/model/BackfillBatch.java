package com.gregochr.goldenhour.model;

/**
 * Outcome of one cloud-verification batch.
 *
 * <p>Two counts, not one, because they mean different things and the difference is exactly what a
 * silent outage looks like. A batch that writes 100 rows carrying no observations is
 * indistinguishable from a healthy one if you only count rows — which is how a throttled backfill
 * could blank its entire backlog while reporting a clean finish. Candidate selection is an
 * anti-join, so those rows are then treated as verified forever.
 *
 * @param rowsWritten      verification rows persisted
 * @param withObservations rows that actually carry archive data
 */
public record BackfillBatch(int rowsWritten, int withObservations) {

    /** An empty batch — nothing left to verify. */
    public static final BackfillBatch EMPTY = new BackfillBatch(0, 0);

    /**
     * Returns whether the batch did work but obtained no observations at all.
     *
     * <p>The signature of an upstream outage — throttling, an archive outage, a bad date range.
     * Continuing past this would burn through the backlog writing blanks that are never revisited,
     * so the runner treats it as a stop condition rather than progress.
     *
     * @return true when rows were written but none carry data
     */
    public boolean isBlank() {
        return rowsWritten > 0 && withObservations == 0;
    }
}
