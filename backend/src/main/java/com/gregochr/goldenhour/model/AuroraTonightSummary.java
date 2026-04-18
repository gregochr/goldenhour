package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.AlertLevel;

import java.util.List;

/**
 * Tonight's aurora summary, derived from the cached aurora forecast scores and cloud triage.
 * Present in the briefing response only when the aurora state machine is active.
 *
 * @param alertLevel              current alert level (MINOR / MODERATE / STRONG)
 * @param kp                      Kp index that triggered the current alert, or {@code null}
 * @param clearLocationCount      number of locations that passed cloud triage
 * @param regions                 aurora-eligible locations grouped by geographic region
 * @param solarWindSpeedKmPerSec  latest NOAA solar wind speed in km/s, or {@code null}
 * @param moonPhase               lunar phase name (e.g. {@code "WAXING_GIBBOUS"}), or {@code null}
 * @param moonIlluminationPct     lunar illumination percentage (0–100), or {@code null}
 * @param moonAboveHorizon        whether the moon is above the horizon at window start, or {@code null}
 * @param windowQuality           moon window quality (e.g. {@code "DARK_THEN_MOONLIT"}), or {@code null}
 * @param moonRiseTime            moonrise time within the window as ISO UTC datetime, or {@code null}
 * @param moonSetTime             moonset time within the window as ISO UTC datetime, or {@code null}
 */
public record AuroraTonightSummary(
        AlertLevel alertLevel,
        Double kp,
        int clearLocationCount,
        List<AuroraRegionSummary> regions,
        Double solarWindSpeedKmPerSec,
        String moonPhase,
        Double moonIlluminationPct,
        Boolean moonAboveHorizon,
        String windowQuality,
        String moonRiseTime,
        String moonSetTime) {

    /** Defensive compact constructor. */
    public AuroraTonightSummary {
        regions = List.copyOf(regions);
    }
}
