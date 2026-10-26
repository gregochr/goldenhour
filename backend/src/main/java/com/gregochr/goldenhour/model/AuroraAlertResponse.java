package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.AlertLevel;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The role-free aurora alert: just enough for a Lite reader's banner, and nothing a Pro reader pays for.
 *
 * <p>Carries no Kp, OVATION probability, Bz, G-scale, location counts, detection time or description.
 * A simulation is never reported: it answers QUIET and inactive.
 *
 * @param level              the alert level; QUIET when none is cached or a simulation stands
 * @param active             whether an alert is active
 * @param triggerType        {@code "forecast"} for a lookahead trigger, {@code "realtime"} for any other,
 *                           null when there is none (mapped exactly as {@code /api/aurora/status})
 * @param allOvercast        true when there are dark-sky sites and none of them has a clear sky
 * @param currentNightDate   the civil date of the night in progress, as in {@code AuroraStatusResponse}
 * @param currentNightEndsAt the instant that night stops being the current one, from the same read
 */
public record AuroraAlertResponse(
        AlertLevel level,
        boolean active,
        String triggerType,
        boolean allOvercast,
        LocalDate currentNightDate,
        Instant currentNightEndsAt) {
}
