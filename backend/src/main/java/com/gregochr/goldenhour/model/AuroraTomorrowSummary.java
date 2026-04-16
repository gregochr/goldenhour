package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Tomorrow night's aurora forecast, derived from NOAA's 3-day Kp forecast.
 *
 * @param peakKp              peak forecast Kp during tomorrow night's dark window
 * @param label               human-readable label: {@code "Quiet"} if Kp &lt; 3,
 *                            {@code "Worth watching"} if Kp &ge; 4,
 *                            {@code "Potentially strong"} if Kp &ge; 6
 * @param alertLevel          alert level derived from {@code peakKp},
 *                            e.g. {@code "MINOR"}, {@code "MODERATE"}
 * @param regions             per-region weather and verdict for tomorrow night,
 *                            or {@code null} if unavailable
 * @param moonPhase           lunar phase name for tomorrow night
 *                            (e.g. {@code "WAXING_GIBBOUS"}), or {@code null}
 * @param moonIlluminationPct lunar illumination percentage (0–100), or {@code null}
 */
public record AuroraTomorrowSummary(
        double peakKp,
        String label,
        String alertLevel,
        List<AuroraRegionSummary> regions,
        String moonPhase,
        Double moonIlluminationPct) {

    /** Defensive compact constructor. */
    public AuroraTomorrowSummary {
        regions = regions == null ? null : List.copyOf(regions);
    }
}
