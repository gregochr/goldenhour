package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.AlertLevel;

import java.time.ZonedDateTime;

/**
 * REST response for {@code GET /api/aurora/status}.
 *
 * <p>Exposes the current AuroraWatch alert level plus state-machine state to the frontend.
 * Only returned to users with {@code ADMIN} or {@code PRO_USER} roles.
 *
 * @param level              current AuroraWatch alert level
 * @param hexColour          AuroraWatch colour code for this level (e.g. {@code "#ff9900"})
 * @param description        human-readable level description
 * @param station            AuroraWatch station name
 * @param active             {@code true} when the state machine is in the ACTIVE state
 *                           (aurora event in progress)
 * @param eligibleLocations  number of locations with aurora scores from the last NOTIFY
 * @param updatedAt          when AuroraWatch last updated the status
 */
public record AuroraStatusResponse(
        AlertLevel level,
        String hexColour,
        String description,
        String station,
        boolean active,
        int eligibleLocations,
        ZonedDateTime updatedAt) {
}
