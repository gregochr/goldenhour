package com.gregochr.goldenhour.model;

/**
 * Detailed breakdown of storm surge components for a coastal location.
 *
 * <h3>Physics summary (sourced coefficients):</h3>
 * <ul>
 *   <li><b>Pressure effect:</b> Inverse barometer, 0.01 m/hPa deviation from 1013.25 hPa.
 *       Confirmed by Met Office, NTSLF, and peer-reviewed literature.
 *       HIGH confidence.</li>
 *   <li><b>Wind effect:</b> Quadratic wind stress model: τ = ρ_air × C_D × U₁₀².
 *       Setup proportional to (τ × fetch) / (ρ_water × g × depth) × cos(θ).
 *       Based on Pugh &amp; Woodworth (2014), NTSLF two-thirds/one-third split.
 *       MEDIUM confidence — location-dependent, needs empirical calibration.</li>
 *   <li><b>Total surge:</b> Additive (pressure + wind). This is an UPPER BOUND
 *       estimate. Horsburgh &amp; Wilson (2007) show that tide-surge interaction
 *       typically reduces the combined extreme at high water.</li>
 * </ul>
 *
 * @param pressureRiseMetres       water level rise from inverse barometer effect (m)
 * @param windRiseMetres           water level rise from wind setup (m)
 * @param totalSurgeMetres         combined surge estimate — upper bound (m)
 * @param pressureHpa              atmospheric pressure used in calculation (hPa)
 * @param windSpeedMs              10m wind speed used in calculation (m/s)
 * @param windDirectionDegrees     wind direction (meteorological convention, degrees FROM)
 * @param onshoreComponentFraction cosine projection of wind onto shore-normal (0.0–1.0)
 * @param riskLevel                surge risk classification accounting for lunar tide type
 * @param surgeExplanation         human-readable explanation for Claude prompt / UI
 */
public record StormSurgeBreakdown(
        double pressureRiseMetres,
        double windRiseMetres,
        double totalSurgeMetres,
        double pressureHpa,
        double windSpeedMs,
        double windDirectionDegrees,
        double onshoreComponentFraction,
        TideRiskLevel riskLevel,
        String surgeExplanation
) {

    /**
     * Zero-surge breakdown for non-tidal or calm-weather locations.
     */
    public static StormSurgeBreakdown none() {
        return new StormSurgeBreakdown(0, 0, 0, 1013.25, 0, 0, 0,
                TideRiskLevel.NONE, "No significant surge expected");
    }

    /**
     * Whether this surge is worth mentioning in the briefing.
     * Surges below 5cm are within noise/model error.
     */
    public boolean isSignificant() {
        return totalSurgeMetres >= 0.05;
    }
}
