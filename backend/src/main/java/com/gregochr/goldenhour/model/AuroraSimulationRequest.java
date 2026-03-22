package com.gregochr.goldenhour.model;

/**
 * Request body for {@code POST /api/aurora/admin/simulate}.
 *
 * <p>The admin provides fake NOAA space weather values. The backend derives the alert level
 * from {@code kp} and activates the state machine without making any Claude API calls.
 *
 * @param kp                 simulated Kp index (0–9)
 * @param ovationProbability simulated OVATION aurora probability at 55°N (0–100)
 * @param bzNanoTesla        simulated solar wind Bz component in nanoTesla
 * @param gScale             simulated NOAA G-scale label ("G1"–"G5"), or null
 */
public record AuroraSimulationRequest(
        double kp,
        double ovationProbability,
        double bzNanoTesla,
        String gScale) {
}
