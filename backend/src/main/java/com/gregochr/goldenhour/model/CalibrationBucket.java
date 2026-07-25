package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Forecast-accuracy metrics for one group of {@link CalibrationPair}s.
 *
 * <p>A bucket is whatever the caller grouped by — a forecast horizon ({@code "T+2"}), a model
 * ({@code "HAIKU"}), or everything ({@code "ALL"}).
 *
 * <p>The two counts at the end are the ones that matter for prompt changes, because they name the
 * two ways a forecast fails a photographer rather than just measuring distance from truth:
 * a <em>missed opportunity</em> told them to stay home on a sky that turned out excellent, and a
 * <em>wasted trip</em> sent them out for one that did not. An absolute rating ceiling in the
 * prompt (the cloud-approach veto) can only ever create missed opportunities, so that count is the
 * direct before/after gate on relaxing it.
 *
 * @param key                     the group this bucket describes
 * @param sampleCount             number of scored forecast/outcome pairs
 * @param meanRatingError         signed mean of (predicted − actual); positive = over-forecasting
 * @param meanAbsoluteRatingError mean absolute rating error, in stars
 * @param exactMatchRate          fraction of pairs where predicted == actual (0–1)
 * @param withinOneRate           fraction of pairs within one star (0–1)
 * @param missedOpportunities     predicted ≤ 2 but actual ≥ 4 — told to stay home on a good sky
 * @param wastedTrips             predicted ≥ 4 but actual ≤ 2 — sent out for a dud
 * @param meanAbsoluteFierySkyError mean absolute fiery-sky error (0–100), or {@code null}
 */
public record CalibrationBucket(
        String key,
        int sampleCount,
        Double meanRatingError,
        Double meanAbsoluteRatingError,
        Double exactMatchRate,
        Double withinOneRate,
        int missedOpportunities,
        int wastedTrips,
        Double meanAbsoluteFierySkyError) {

    /** Predicted rating at or below which the forecast is telling the photographer to stay home. */
    private static final int STAY_HOME_MAX = 2;

    /** Actual rating at or above which the sky was worth going out for. */
    private static final int WORTH_IT_MIN = 4;

    /** Rating distance still counted as agreement for {@link #withinOneRate()}. */
    private static final int WITHIN_ONE_STAR = 1;

    /** Decimal places retained on reported rates and errors. */
    private static final double ROUNDING_SCALE = 100.0;

    /**
     * Aggregates a group of pairs into a bucket.
     *
     * @param key   the group name
     * @param pairs the pairs in this group
     * @return the aggregated metrics, with null rates when the group is empty
     */
    public static CalibrationBucket of(String key, List<CalibrationPair> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            return new CalibrationBucket(key, 0, null, null, null, null, 0, 0, null);
        }

        int count = pairs.size();
        int signedSum = 0;
        int absoluteSum = 0;
        int exact = 0;
        int withinOne = 0;
        int missed = 0;
        int wasted = 0;
        int fierySum = 0;
        int fieryCount = 0;

        for (CalibrationPair pair : pairs) {
            int error = pair.ratingError();
            signedSum += error;
            absoluteSum += Math.abs(error);
            if (error == 0) {
                exact++;
            }
            if (Math.abs(error) <= WITHIN_ONE_STAR) {
                withinOne++;
            }
            if (pair.predictedRating() <= STAY_HOME_MAX && pair.actualRating() >= WORTH_IT_MIN) {
                missed++;
            }
            if (pair.predictedRating() >= WORTH_IT_MIN && pair.actualRating() <= STAY_HOME_MAX) {
                wasted++;
            }
            if (pair.predictedFierySky() != null && pair.actualFierySky() != null) {
                fierySum += Math.abs(pair.predictedFierySky() - pair.actualFierySky());
                fieryCount++;
            }
        }

        return new CalibrationBucket(
                key,
                count,
                round((double) signedSum / count),
                round((double) absoluteSum / count),
                round((double) exact / count),
                round((double) withinOne / count),
                missed,
                wasted,
                fieryCount == 0 ? null : round((double) fierySum / fieryCount));
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
