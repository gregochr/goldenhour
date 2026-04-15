package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Hourly pressure tendency around the solar event window.
 *
 * <p>Used to signal approaching fronts and synoptic-scale change
 * to Claude for calibrated forecast confidence.
 *
 * <p>Window: T-3h to T+3h relative to the solar event time.
 * Values are mean sea level pressure in hPa.
 *
 * @param pressureHpa   T-3h through T+3h MSL pressure values (up to 7 values)
 * @param tendencyHpa6h delta: last value minus first value (negative = falling)
 * @param tendencyLabel one of "FALLING_RAPIDLY", "FALLING", "STEADY", "RISING"
 */
public record PressureTrend(
        List<Double> pressureHpa,
        double tendencyHpa6h,
        String tendencyLabel) {

    /** Rapid fall threshold — 3+ hPa drop in 6 hours signals approaching deep low. */
    public static final double RAPID_FALL_THRESHOLD = -3.0;

    /** Moderate fall threshold — 1.5+ hPa drop in 6 hours signals frontal approach. */
    public static final double MODERATE_FALL_THRESHOLD = -1.5;

    /** Rise threshold — 1+ hPa rise in 6 hours signals building high or post-frontal clearing. */
    public static final double RISE_THRESHOLD = 1.0;

    /**
     * Compact constructor — defensive copy for immutability.
     */
    public PressureTrend {
        pressureHpa = List.copyOf(pressureHpa);
    }

    /**
     * Derives a human-readable tendency label from the 6-hour pressure delta.
     *
     * @param tendencyHpa6h pressure change over 6 hours (negative = falling)
     * @return one of "FALLING_RAPIDLY", "FALLING", "STEADY", "RISING"
     */
    public static String labelFromTendency(double tendencyHpa6h) {
        if (tendencyHpa6h <= RAPID_FALL_THRESHOLD) {
            return "FALLING_RAPIDLY";
        }
        if (tendencyHpa6h <= MODERATE_FALL_THRESHOLD) {
            return "FALLING";
        }
        if (tendencyHpa6h >= RISE_THRESHOLD) {
            return "RISING";
        }
        return "STEADY";
    }
}
