package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.AlertLevel;

import java.time.ZonedDateTime;

/**
 * REST response for {@code GET /api/aurora/status}.
 *
 * <p>Exposes the current derived alert level (from NOAA SWPC Kp + OVATION data)
 * plus the state-machine state to the frontend. Only returned to users with
 * {@code ADMIN} or {@code PRO_USER} roles.
 *
 * @param level              current alert level derived from NOAA SWPC data
 * @param hexColour          colour code for this level (e.g. {@code "#ff9900"})
 * @param description        human-readable level description
 * @param active             {@code true} when the state machine is in the ACTIVE state
 *                           (aurora event in progress)
 * @param eligibleLocations  number of locations with aurora scores from the last NOTIFY
 * @param kp                 most recent real-time Kp index value, or {@code null} if unavailable
 * @param forecastKp         the Kp value that triggered the current alert (forecast max Kp
 *                           for lookahead alerts, latest Kp for real-time alerts), or
 *                           {@code null} if the state machine is IDLE
 * @param triggerType        {@code "forecast"} when the alert was raised by the daytime forecast
 *                           lookahead path, {@code "realtime"} when raised by the night-time
 *                           real-time path, or {@code null} when IDLE
 * @param ovationProbability OVATION aurora probability at 55°N, or {@code null} if unavailable
 * @param dataSource         source of the space weather data (e.g. {@code "NOAA SWPC"})
 * @param updatedAt          when the most recent NOAA data was fetched
 */
public record AuroraStatusResponse(
        AlertLevel level,
        String hexColour,
        String description,
        boolean active,
        int eligibleLocations,
        Double kp,
        Double forecastKp,
        String triggerType,
        Double ovationProbability,
        String dataSource,
        ZonedDateTime updatedAt) {
}
