package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.AlertLevel;

/**
 * Response body for {@code POST /api/aurora/admin/simulate}.
 *
 * @param level              derived alert level based on the simulated Kp
 * @param message            human-readable confirmation with instructions for next steps
 * @param eligibleLocations  count of Bortle-eligible locations available for a forecast run
 */
public record AuroraSimulationResponse(
        AlertLevel level,
        String message,
        int eligibleLocations) {
}
