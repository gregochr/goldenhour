package com.gregochr.goldenhour.model;

/**
 * The WMO sea-state code / Douglas scale — a named band for significant wave height (Hs).
 *
 * <p>This is the anomaly context that makes a bare wave figure meaningful: {@code 4.2 m} on its own
 * says little, but {@code 4.2 m · very rough} tells a photographer the sea is genuinely dramatic.
 * Bands are lower-inclusive / upper-exclusive, so exactly {@code 4.00 m} classifies as
 * {@link #VERY_ROUGH}, not {@link #ROUGH}.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Douglas_sea_scale">Douglas sea scale</a>
 */
public enum SeaState {

    /** Hs &lt; 0.10 m. */
    CALM("calm"),
    /** 0.10 m ≤ Hs &lt; 0.50 m. */
    SMOOTH("smooth"),
    /** 0.50 m ≤ Hs &lt; 1.25 m. */
    SLIGHT("slight"),
    /** 1.25 m ≤ Hs &lt; 2.50 m. */
    MODERATE("moderate"),
    /** 2.50 m ≤ Hs &lt; 4.00 m. */
    ROUGH("rough"),
    /** 4.00 m ≤ Hs &lt; 6.00 m. */
    VERY_ROUGH("very rough"),
    /** 6.00 m ≤ Hs &lt; 9.00 m. */
    HIGH("high"),
    /** 9.00 m ≤ Hs &lt; 14.00 m. */
    VERY_HIGH("very high"),
    /** Hs ≥ 14.00 m. */
    PHENOMENAL("phenomenal");

    private static final double CALM_MAX_M = 0.10;
    private static final double SMOOTH_MAX_M = 0.50;
    private static final double SLIGHT_MAX_M = 1.25;
    private static final double MODERATE_MAX_M = 2.50;
    private static final double ROUGH_MAX_M = 4.00;
    private static final double VERY_ROUGH_MAX_M = 6.00;
    private static final double HIGH_MAX_M = 9.00;
    private static final double VERY_HIGH_MAX_M = 14.00;

    /** Hs (m) at or above which a photographer would call the sea notably dramatic. */
    public static final double NOTABLE_THRESHOLD_M = MODERATE_MAX_M;

    private final String label;

    SeaState(String label) {
        this.label = label;
    }

    /**
     * The lower-case display label for this band (e.g. {@code "very rough"}).
     *
     * @return the band label
     */
    public String label() {
        return label;
    }

    /**
     * Classifies a significant wave height into its sea-state band.
     *
     * @param significantWaveHeightMetres Hs in metres
     * @return the matching band ({@link #PHENOMENAL} for very large or non-finite values)
     */
    public static SeaState fromHs(double significantWaveHeightMetres) {
        double hs = significantWaveHeightMetres;
        if (hs < CALM_MAX_M) {
            return CALM;
        }
        if (hs < SMOOTH_MAX_M) {
            return SMOOTH;
        }
        if (hs < SLIGHT_MAX_M) {
            return SLIGHT;
        }
        if (hs < MODERATE_MAX_M) {
            return MODERATE;
        }
        if (hs < ROUGH_MAX_M) {
            return ROUGH;
        }
        if (hs < VERY_ROUGH_MAX_M) {
            return VERY_ROUGH;
        }
        if (hs < HIGH_MAX_M) {
            return HIGH;
        }
        if (hs < VERY_HIGH_MAX_M) {
            return VERY_HIGH;
        }
        return PHENOMENAL;
    }
}
