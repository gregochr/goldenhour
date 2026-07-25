package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Forecast-vs-observed cloud accuracy for one group of {@link CloudVerificationPair}s.
 *
 * <p>Reports the gap and the canvas separately, because they fail differently and a single blended
 * error would hide both. The counts at the end are the actionable ones:
 * <ul>
 *   <li>{@code gapActuallyOpen} — the forecast's blocker never materialised. Within a
 *       veto-fired bucket this is the count of skies the veto suppressed that were, in fact,
 *       clear at the horizon: the direct measure of the veto over-firing.</li>
 *   <li>{@code gapActuallyBlocked} — the horizon really did close. Within a veto-fired bucket
 *       this is the veto earning its keep.</li>
 * </ul>
 *
 * <p>Thresholds are the system's own, taken from the scoring prompt rather than invented here:
 * low cloud above 60% is "blocked", below 20% is "strong light penetration".
 *
 * @param key                 the group this bucket describes
 * @param sampleCount         number of verified forecasts in the group
 * @param meanGapError        signed mean of (forecast − observed) horizon low cloud
 * @param meanAbsGapError     mean absolute horizon low cloud error, in percentage points
 * @param meanCanvasError     signed mean of (forecast − observed) canvas strength
 * @param gapActuallyOpen     count where observed horizon low cloud was ≤ 20%
 * @param gapActuallyBlocked  count where observed horizon low cloud was ≥ 60%
 */
public record CloudVerificationBucket(
        String key,
        int sampleCount,
        Double meanGapError,
        Double meanAbsGapError,
        Double meanCanvasError,
        int gapActuallyOpen,
        int gapActuallyBlocked) {

    /** Observed low cloud (%) at or below which the sun demonstrably had a gap. */
    private static final int GAP_OPEN_MAX_PERCENT = 20;

    /** Observed low cloud (%) at or above which the horizon was demonstrably blocked. */
    private static final int GAP_BLOCKED_MIN_PERCENT = 60;

    /** Decimal places retained on reported errors. */
    private static final double ROUNDING_SCALE = 100.0;

    /**
     * Aggregates a group of verified pairs into a bucket.
     *
     * @param key   the group name
     * @param pairs the pairs in this group
     * @return the aggregated metrics, with null means when nothing in the group was comparable
     */
    public static CloudVerificationBucket of(String key, List<CloudVerificationPair> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            return new CloudVerificationBucket(key, 0, null, null, null, 0, 0);
        }

        int gapSum = 0;
        int gapAbsSum = 0;
        int gapCount = 0;
        int canvasSum = 0;
        int canvasCount = 0;
        int open = 0;
        int blocked = 0;

        for (CloudVerificationPair pair : pairs) {
            Integer gapError = pair.gapError();
            if (gapError != null) {
                gapSum += gapError;
                gapAbsSum += Math.abs(gapError);
                gapCount++;
            }
            Integer canvasError = pair.canvasError();
            if (canvasError != null) {
                canvasSum += canvasError;
                canvasCount++;
            }
            Integer observedGap = pair.observedGapLow();
            if (observedGap != null) {
                if (observedGap <= GAP_OPEN_MAX_PERCENT) {
                    open++;
                } else if (observedGap >= GAP_BLOCKED_MIN_PERCENT) {
                    blocked++;
                }
            }
        }

        return new CloudVerificationBucket(
                key,
                pairs.size(),
                gapCount == 0 ? null : round((double) gapSum / gapCount),
                gapCount == 0 ? null : round((double) gapAbsSum / gapCount),
                canvasCount == 0 ? null : round((double) canvasSum / canvasCount),
                open,
                blocked);
    }

    /**
     * Rounds to two decimal places so before/after reports diff cleanly.
     *
     * @param value the raw value
     * @return the rounded value
     */
    private static double round(double value) {
        return Math.round(value * ROUNDING_SCALE) / ROUNDING_SCALE;
    }
}
