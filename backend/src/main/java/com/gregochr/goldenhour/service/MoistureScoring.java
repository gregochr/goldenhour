package com.gregochr.goldenhour.service;

/**
 * The one place the temperature–dew point gap is turned into a moisture score.
 *
 * <p>Shared by {@link InversionScoreCalculator} (mist seen from above) and
 * {@link ValleyMistCalculator} (mist seen from beside it) so that "moisture is counted once" has a
 * single home: relative humidity is the same measurement as the gap (Magnus: a gap of 1 °C is
 * RH 92.7–94.2 % across −5 to 25 °C), so neither calculator reads RH as a second moisture term.
 * Changing a step here moves both calculators, which is the point.
 */
final class MoistureScoring {

    /** The score a gap at or inside {@link #SATURATED_GAP_CELSIUS} earns; the scale's top. */
    static final double MAX_SCORE = 5.0;

    /** Gap at or below which the air is, for scoring, saturated. */
    private static final double SATURATED_GAP_CELSIUS = 0.5;

    private MoistureScoring() {
    }

    /**
     * Scores available moisture from the temperature–dew point gap (0–5).
     *
     * @param tempDewGap temperature minus dew point, in °C
     * @return 0–{@value #MAX_SCORE}, higher the nearer the surface air is to saturation
     */
    static double gapScore(double tempDewGap) {
        if (tempDewGap <= SATURATED_GAP_CELSIUS) {
            return MAX_SCORE;
        }
        if (tempDewGap <= 1.0) {
            return 4.0;
        }
        if (tempDewGap <= 2.0) {
            return 3.0;
        }
        if (tempDewGap <= 3.0) {
            return 2.0;
        }
        if (tempDewGap <= 5.0) {
            return 1.0;
        }
        return 0.0;
    }
}
