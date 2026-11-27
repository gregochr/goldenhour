package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopicFact;

/**
 * The one place the sub-zero air reading is rounded for a "frost likely" claim, shared by the SNOW_MIST frost
 * variant ({@link SnowFreshHotTopicStrategy}) and {@link FrostMistHotTopicStrategy} so the two facts read
 * identically.
 */
final class FrostAir {

    /** The shown value is never warmer than this, so a frost claim never prints "0 °C". */
    private static final long WARMEST_SHOWN_C = -1L;

    private FrostAir() {
    }

    /**
     * The whole-degree air temperature to print beside a "frost likely" claim. The gate is a strict
     * {@code temperature < 0}, so the displayed integer must never read "0 °C" (the freezing point): a reading in
     * [-0.5, 0) rounds to 0, so the shown value is clamped to at most -1 °C. It stays genuinely sub-zero and honest.
     *
     * @param temperature a sub-zero 2 m air temperature in °C
     * @return the rounded value, at most -1
     */
    static long shownCelsius(double temperature) {
        return Math.min(WARMEST_SHOWN_C, Math.round(temperature));
    }

    /**
     * The {@code air -N °C · frost likely} fact line.
     *
     * @param temperature a sub-zero 2 m air temperature in °C
     * @return the fact, keyed "air"
     */
    static HotTopicFact fact(double temperature) {
        return new HotTopicFact("air", shownCelsius(temperature) + " °C · frost likely", null, false, false);
    }
}
