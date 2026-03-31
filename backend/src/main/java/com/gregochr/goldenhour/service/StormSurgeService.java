package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.CoastalParameters;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideRiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Calculates weather-driven storm surge for coastal locations.
 *
 * <h3>Model overview:</h3>
 * <p>Combines two independently calculated components:</p>
 * <ol>
 *   <li><b>Inverse barometer effect</b> — 0.01 m per hPa below 1013.25.
 *       Textbook physics confirmed by Met Office, NTSLF, and peer-reviewed
 *       literature. HIGH confidence.</li>
 *   <li><b>Wind setup</b> — quadratic wind stress model using bulk aerodynamic
 *       formula. Setup is proportional to wind stress × fetch / (water density
 *       × gravity × depth), projected onto the shore-normal bearing.
 *       Based on Pugh &amp; Woodworth (2014) and validated by NTSLF's observed
 *       ⅔ wind / ⅓ pressure contribution to UK skew surges.
 *       MEDIUM confidence — needs per-location empirical calibration.</li>
 * </ol>
 *
 * <h3>Important caveats:</h3>
 * <ul>
 *   <li>The total is an <b>upper-bound</b> additive estimate. Horsburgh &amp; Wilson
 *       (2007) demonstrate that tide-surge interaction in the North Sea causes
 *       surge peaks to cluster 3–5 hours before high water, meaning the actual
 *       combined water level at high tide is typically less than simple addition.</li>
 *   <li>Wave setup (breaking wave contribution) is NOT modelled — this adds
 *       an additional 0.1–0.5m in storm conditions but requires wave model data.</li>
 *   <li>The drag coefficient C_D is wind-speed-dependent in reality. This
 *       implementation uses a fixed moderate-wind value.</li>
 * </ul>
 *
 * @see <a href="https://ntslf.org/storm-surges/skew-surges">NTSLF Skew Surges</a>
 * @see <a href="https://doi.org/10.1029/2006JC004033">Horsburgh &amp; Wilson (2007)</a>
 */
@Service
public class StormSurgeService {

    private static final Logger LOG = LoggerFactory.getLogger(StormSurgeService.class);

    // ── Physical constants ───────────────────────────────────────────────

    /** Standard mean sea-level pressure (hPa). */
    static final double STANDARD_PRESSURE_HPA = 1013.25;

    /** Inverse barometer coefficient: 0.01 m rise per 1 hPa drop. */
    private static final double PRESSURE_COEFFICIENT_M_PER_HPA = 0.01;

    /** Air density at sea level (kg/m³). ISA standard atmosphere. */
    private static final double RHO_AIR = 1.225;

    /** Sea water density (kg/m³). North Sea average. */
    private static final double RHO_WATER = 1025.0;

    /** Gravitational acceleration (m/s²). */
    private static final double G = 9.81;

    /**
     * Drag coefficient for moderate winds (10–20 m/s) over sea.
     * Source: Smith (1988), J. Geophys. Res., 93(C12), 15467–15472.
     */
    private static final double DRAG_COEFFICIENT = 1.2e-3;

    /**
     * Empirical calibration factor for the simplified wind setup model.
     *
     * <p>The raw physics tends to overestimate setup for open coastlines
     * because it assumes all wind energy converts to setup along a single
     * 1D profile. In reality, water escapes laterally along the coast.
     * This factor accounts for 3D spreading losses.
     *
     * <p>Initial value 0.5 is a conservative starting point. Calibrate
     * empirically by comparing predictions against NTSLF surge model
     * output for North Shields over 30–50 events.
     */
    private static final double WIND_SETUP_CALIBRATION_FACTOR = 0.5;

    /** Minimum surge (m) to be considered significant / worth reporting. */
    private static final double SIGNIFICANCE_THRESHOLD_M = 0.05;

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Calculate storm surge breakdown for a coastal location.
     *
     * @param pressureHpa          mean sea-level pressure from Open-Meteo (hPa)
     * @param windSpeedMs          10m wind speed from Open-Meteo (m/s)
     * @param windDirectionDegrees wind direction, meteorological convention:
     *                             direction wind is blowing FROM (0=N, 90=E, etc.)
     * @param coastalParams        per-location coastal parameters
     * @param lunarTideType        lunar tide classification:
     *                             KING_TIDE, SPRING_TIDE, or REGULAR_TIDE
     * @return breakdown of pressure + wind surge components with risk level
     */
    public StormSurgeBreakdown calculate(
            double pressureHpa,
            double windSpeedMs,
            double windDirectionDegrees,
            CoastalParameters coastalParams,
            String lunarTideType) {

        if (!coastalParams.isCoastalTidal()) {
            return StormSurgeBreakdown.none();
        }

        double pressureRise = calculatePressureRise(pressureHpa);

        double onshoreComponent = calculateOnshoreComponent(
                windDirectionDegrees, coastalParams.shoreNormalBearingDegrees());
        double windRise = calculateWindSetup(
                windSpeedMs, onshoreComponent, coastalParams);

        // Additive upper bound — tide-surge interaction typically reduces combined peak
        double totalSurge = Math.max(0, pressureRise + windRise);

        TideRiskLevel risk = classifyRisk(totalSurge, lunarTideType);

        String explanation = buildExplanation(
                pressureRise, windRise, totalSurge, pressureHpa,
                windSpeedMs, onshoreComponent, risk);

        LOG.debug("Surge calc: pressure={}m, wind={}m, total={}m, risk={}",
                round3(pressureRise), round3(windRise), round3(totalSurge), risk);

        return new StormSurgeBreakdown(
                round3(pressureRise),
                round3(windRise),
                round3(totalSurge),
                pressureHpa,
                windSpeedMs,
                windDirectionDegrees,
                round3(onshoreComponent),
                risk,
                explanation
        );
    }

    // ── Pressure calculation ─────────────────────────────────────────

    /**
     * Inverse barometer effect: 1 cm rise per 1 hPa below standard pressure.
     *
     * @param pressureHpa observed MSLP in hPa
     * @return water level change in metres (positive = rise)
     */
    double calculatePressureRise(double pressureHpa) {
        return (STANDARD_PRESSURE_HPA - pressureHpa) * PRESSURE_COEFFICIENT_M_PER_HPA;
    }

    // ── Wind setup calculation ───────────────────────────────────────

    /**
     * Calculate the onshore component of wind as a fraction (0.0–1.0).
     *
     * <p>Projects the wind vector onto the shore-normal bearing using cos(angle).
     * Wind blowing directly from the shore-normal direction (i.e., straight
     * onshore) gives 1.0. Offshore wind is clamped to 0.0.
     *
     * @param windFromDegrees    wind direction (meteorological, degrees FROM)
     * @param shoreNormalDegrees shore-normal bearing (seaward, degrees)
     * @return onshore fraction 0.0–1.0
     */
    double calculateOnshoreComponent(double windFromDegrees, double shoreNormalDegrees) {
        double angleDifference = Math.toRadians(windFromDegrees - shoreNormalDegrees);
        double cosine = Math.cos(angleDifference);
        return Math.max(0.0, cosine);
    }

    /**
     * Simplified wind setup using bulk aerodynamic wind stress formula.
     *
     * <p>τ = ρ_air × C_D × U₁₀², setup = (τ × F × cos(θ)) / (ρ_water × g × D) × k
     *
     * @param windSpeedMs      10m wind speed (m/s)
     * @param onshoreComponent cosine projection onto shore-normal (0–1)
     * @param params           location-specific coastal parameters
     * @return wind-driven water level rise in metres
     */
    double calculateWindSetup(double windSpeedMs, double onshoreComponent,
                              CoastalParameters params) {
        if (windSpeedMs <= 0 || onshoreComponent <= 0) {
            return 0.0;
        }

        double windStress = RHO_AIR * DRAG_COEFFICIENT * windSpeedMs * windSpeedMs;

        double setup = (windStress * params.effectiveFetchMetres() * onshoreComponent)
                / (RHO_WATER * G * params.avgShelfDepthMetres())
                * WIND_SETUP_CALIBRATION_FACTOR;

        return Math.max(0.0, setup);
    }

    // ── Risk classification ──────────────────────────────────────────

    /**
     * Classify surge risk based on magnitude and lunar tide type.
     *
     * <p>Risk escalates when surge coincides with spring or king tides.
     *
     * @param totalSurgeMetres combined surge magnitude
     * @param lunarTideType    KING_TIDE, SPRING_TIDE, or REGULAR_TIDE
     * @return risk classification
     */
    TideRiskLevel classifyRisk(double totalSurgeMetres, String lunarTideType) {
        if (totalSurgeMetres < 0.10) {
            return TideRiskLevel.NONE;
        }

        boolean isSpringOrKing = "KING_TIDE".equals(lunarTideType)
                || "SPRING_TIDE".equals(lunarTideType);

        if (totalSurgeMetres >= 0.60) {
            return TideRiskLevel.HIGH;
        }

        if (totalSurgeMetres >= 0.30) {
            return isSpringOrKing ? TideRiskLevel.HIGH : TideRiskLevel.MODERATE;
        }

        // 0.10–0.30m range
        return isSpringOrKing ? TideRiskLevel.MODERATE : TideRiskLevel.LOW;
    }

    // ── Explanation builder ──────────────────────────────────────────

    private String buildExplanation(double pressureRise, double windRise,
                                    double totalSurge, double pressureHpa,
                                    double windSpeedMs, double onshoreComponent,
                                    TideRiskLevel risk) {
        if (totalSurge < SIGNIFICANCE_THRESHOLD_M) {
            return "No significant surge expected";
        }

        var sb = new StringBuilder();

        if (Math.abs(pressureRise) >= 0.02) {
            if (pressureRise > 0) {
                sb.append(String.format("Low pressure (%.0f hPa) raising water +%.2fm",
                        pressureHpa, pressureRise));
            } else {
                sb.append(String.format("High pressure (%.0f hPa) suppressing water %.2fm",
                        pressureHpa, pressureRise));
            }
        }

        if (windRise >= 0.02) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            double windKnots = windSpeedMs * 1.94384;
            String onshoreDesc = onshoreComponent > 0.7 ? "strong onshore"
                    : onshoreComponent > 0.3 ? "moderate onshore"
                    : "weak onshore";
            sb.append(String.format("%s wind (%.0f kn) adding +%.2fm",
                    onshoreDesc, windKnots, windRise));
        }

        sb.append(String.format(". Total surge estimate: +%.2fm", totalSurge));
        if (risk == TideRiskLevel.HIGH) {
            sb.append(" — elevated risk, exercise caution at exposed locations");
        } else if (risk == TideRiskLevel.MODERATE) {
            sb.append(" — notable conditions for coastal photography");
        }

        return sb.toString();
    }

    // ── Utility ──────────────────────────────────────────────────────

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
