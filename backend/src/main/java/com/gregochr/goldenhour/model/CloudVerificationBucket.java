package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Forecast-vs-observed cloud accuracy for one group of {@link CloudVerificationPair}s.
 *
 * <p>Reports the gap and the canvas separately, because they fail differently and a single blended
 * error would hide both.
 *
 * <p><strong>Deliberately no absolute thresholds.</strong> An earlier version counted observations
 * either side of the prompt's own 20% / 60% cuts. That was invalid: the reanalysis baseline reads
 * systematically lower than the forecast model — measured at ~15pp at the observer and ~25pp at the
 * horizon, and <em>flat across forecast horizons</em>, which is the signature of a model offset
 * rather than forecast error. Against a baseline shifted by that much, a fixed cut mislabels: a
 * horizon genuinely at 60% reads nearer 40% and counts as "open". The counts were biased toward
 * finding the forecast at fault.
 *
 * <p>What survives that offset is <em>comparison between buckets</em>. The offset applies equally
 * to all of them, so it cancels in a difference. Hence {@code meanObservedGapLow}: on its own it is
 * not interpretable in absolute terms, but the gap between two buckets is.
 *
 * @param key                 the group this bucket describes
 * @param sampleCount         number of verified forecasts in the group
 * @param meanGapError        signed mean of (forecast − observed) horizon low cloud
 * @param meanAbsGapError     mean absolute horizon low cloud error, in percentage points
 * @param meanCanvasError     signed mean of (forecast − observed) canvas strength
 * @param meanForecastGapLow  mean forecast solar-horizon low cloud (%)
 * @param meanObservedGapLow  mean reanalysis solar-horizon low cloud (%) — compare between
 *                            buckets, never against an absolute threshold
 * @param meanConeSpread      mean analysed cone spread (max − min low cloud, pp) — reanalysis-
 *                            internal, so offset-immune
 * @param meanFarDrop         mean analysed near-minus-far low cloud (pp) — reanalysis-internal,
 *                            so offset-immune
 * @param meanFarError        signed mean of (forecast − observed) far-solar low cloud
 */
public record CloudVerificationBucket(
        String key,
        int sampleCount,
        Double meanGapError,
        Double meanAbsGapError,
        Double meanCanvasError,
        Double meanForecastGapLow,
        Double meanObservedGapLow,
        Double meanConeSpread,
        Double meanFarDrop,
        Double meanFarError) {

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
            return new CloudVerificationBucket(key, 0, null, null, null, null, null,
                    null, null, null);
        }

        int gapSum = 0;
        int gapAbsSum = 0;
        int gapCount = 0;
        int canvasSum = 0;
        int canvasCount = 0;
        int forecastSum = 0;
        int forecastCount = 0;
        int observedSum = 0;
        int observedCount = 0;
        int spreadSum = 0;
        int spreadCount = 0;
        int farDropSum = 0;
        int farDropCount = 0;
        int farErrorSum = 0;
        int farErrorCount = 0;

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
            if (pair.forecastGapLow() != null) {
                forecastSum += pair.forecastGapLow();
                forecastCount++;
            }
            if (pair.observedGapLow() != null) {
                observedSum += pair.observedGapLow();
                observedCount++;
            }
            Integer coneSpread = pair.coneSpread();
            if (coneSpread != null) {
                spreadSum += coneSpread;
                spreadCount++;
            }
            Integer farDrop = pair.farDrop();
            if (farDrop != null) {
                farDropSum += farDrop;
                farDropCount++;
            }
            Integer farError = pair.farError();
            if (farError != null) {
                farErrorSum += farError;
                farErrorCount++;
            }
        }

        return new CloudVerificationBucket(
                key,
                pairs.size(),
                gapCount == 0 ? null : round((double) gapSum / gapCount),
                gapCount == 0 ? null : round((double) gapAbsSum / gapCount),
                canvasCount == 0 ? null : round((double) canvasSum / canvasCount),
                forecastCount == 0 ? null : round((double) forecastSum / forecastCount),
                observedCount == 0 ? null : round((double) observedSum / observedCount),
                spreadCount == 0 ? null : round((double) spreadSum / spreadCount),
                farDropCount == 0 ? null : round((double) farDropSum / farDropCount),
                farErrorCount == 0 ? null : round((double) farErrorSum / farErrorCount));
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
