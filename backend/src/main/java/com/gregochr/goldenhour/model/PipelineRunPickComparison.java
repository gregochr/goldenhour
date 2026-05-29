package com.gregochr.goldenhour.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Cross-run comparison of an INTRADAY cycle's best-bet picks against the same
 * day's baseline NIGHTLY run — the value-proving signal the intraday refresh
 * exists for. It answers, without leaving the browser: <em>did Plan A or Plan B
 * change since this morning's nightly forecast?</em>
 *
 * <p>Populated only for INTRADAY runs that have a same-day nightly baseline.
 * Null on the {@link PipelineRunDetail} of a nightly run (it is itself the
 * baseline) or an intraday run with no baseline to compare against.
 *
 * @param baselineRunId       the nightly run compared against
 * @param baselineTriggerTime when that nightly run started
 * @param diffs               per-rank comparison (rank 1 = Plan A, rank 2 = Plan B)
 */
public record PipelineRunPickComparison(
        Long baselineRunId,
        Instant baselineTriggerTime,
        List<PickDiff> diffs) {

    /**
     * The comparison of a single pick rank between the intraday run and the
     * nightly baseline.
     *
     * @param rank              pick rank (1 = Plan A, 2 = Plan B)
     * @param changed           whether this rank differs between the two runs
     * @param changedDimensions which aspects differ — any of {@code REGION},
     *                          {@code DATE}, {@code EVENT}, {@code RATING},
     *                          {@code PRESENCE} (one run has a pick the other lacks)
     * @param intraday          this intraday run's pick at this rank (null if absent)
     * @param nightly           the baseline nightly run's pick at this rank (null if absent)
     */
    public record PickDiff(
            int rank,
            boolean changed,
            List<String> changedDimensions,
            PickView intraday,
            PickView nightly) {
    }

    /**
     * A flattened view of one persisted pick, carrying just the fields the
     * cross-run comparison surfaces.
     *
     * @param headline            the pick's headline prose
     * @param region              the region (null for stay-home picks)
     * @param eventDate           the event date (null if not parseable)
     * @param eventType           the event type (sunrise/sunset/aurora)
     * @param confidence          the advisor's confidence level
     * @param claudeAverageRating snapshotted region-average Claude rating, or null
     */
    public record PickView(
            String headline,
            String region,
            LocalDate eventDate,
            String eventType,
            String confidence,
            Double claudeAverageRating) {
    }
}
