package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.AlertLevel;

import java.time.Instant;
import java.time.LocalDate;
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
 * @param darkSkyLocationCount number of Bortle-eligible dark sky locations, or 0 if scoring
 *                             has not run; the frontend omits the count when this is 0
 * @param clearLocationCount   number of dark sky locations that passed cloud triage (clear skies),
 *                             or {@code null} if the triage has not yet run
 * @param kp                 most recent real-time Kp index value, or {@code null} if unavailable
 * @param forecastKp         the Kp value that drove the last NOTIFY: the highest Kp forecast for
 *                           the rest of tonight for a forecast alert, the Kp for now (NOAA's value
 *                           for the most recently completed 3-hour block) for a real-time one.
 *                           {@code null} until a NOTIFY or a simulation sets it. ⚠️ A CLEAR does
 *                           not reset it, so read it together with {@code active}
 * @param triggerType        {@code "forecast"} when the last NOTIFY's level came from the forecast
 *                           for tonight, {@code "realtime"} when only the conditions now reached it
 *                           (after dark). {@code null} until a NOTIFY or a simulation sets it, and,
 *                           like {@code forecastKp}, not reset by a CLEAR
 * @param ovationProbability OVATION aurora probability at 55°N, or {@code null} if unavailable
 * @param bzNanoTesla        most recent solar wind Bz component in nanoTesla (negative = favourable
 *                           southward field coupling energy into Earth's magnetosphere), or
 *                           {@code null} if unavailable
 * @param dataSource         source of the space weather data (e.g. {@code "NOAA SWPC"})
 * @param updatedAt          when the most recent NOAA data was fetched
 * @param simulated          {@code true} when the active alert was injected by the admin
 *                           simulation endpoint rather than real NOAA data
 * @param detectedAt         when the current alert level was first detected (state machine
 *                           entered ACTIVE or escalated), or {@code null} when IDLE
 * @param gScale             NOAA geomagnetic storm scale label ({@code "G1"}–{@code "G5"}), or
 *                           {@code null} below the G1 storm threshold; drives the banner's
 *                           severity index ({@code Strong · G4})
 * @param currentNightDate   the date naming the dark window we are in, or the next one if it is
 *                           daylight — <b>not</b> today's date. A night runs from dusk on {@code D}
 *                           to dawn on {@code D+1}, so between midnight and dawn this is
 *                           <em>yesterday</em>, and that is the date aurora results for the night in
 *                           progress are stored under. Carried here so the map can default to the
 *                           night the user just ran instead of deriving a calendar date of its own;
 *                           see {@code AuroraForecastRunService.currentNight()}, which owns the
 *                           rule and is the only place it lives
 * @param currentNightEndsAt the instant that night stops being the current one — the nautical dawn
 *                           closing its window — taken from the same read of the clock as
 *                           {@code currentNightDate}. The client keeps its last status when a later
 *                           fetch fails, so a status can outlive its night; this is how the map
 *                           knows when {@code currentNightDate} has stopped being true
 */
public record AuroraStatusResponse(
        AlertLevel level,
        String hexColour,
        String description,
        boolean active,
        int eligibleLocations,
        int darkSkyLocationCount,
        Integer clearLocationCount,
        Double kp,
        Double forecastKp,
        String triggerType,
        Double ovationProbability,
        Double bzNanoTesla,
        String dataSource,
        ZonedDateTime updatedAt,
        boolean simulated,
        Instant detectedAt,
        String gScale,
        LocalDate currentNightDate,
        Instant currentNightEndsAt) {
}
