package com.gregochr.goldenhour.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DTO for astro observing conditions at a single location for a given night.
 * Returned by the {@code /api/astro/conditions} endpoint. Available to all users.
 *
 * @param locationId           database ID of the location
 * @param locationName         human-readable location name
 * @param lat                  latitude in decimal degrees
 * @param lon                  longitude in decimal degrees
 * @param bortleClass          Bortle dark-sky class (1–8)
 * @param stars                overall rating (1–5)
 * @param summary              combined summary sentence
 * @param cloudExplanation     template explanation for cloud factor
 * @param visibilityExplanation template explanation for visibility factor
 * @param moonExplanation      template explanation for moon factor
 * @param forecastDate         the scored night
 * @param moonPhase            lunar phase name (e.g. "Waxing Crescent")
 * @param moonIlluminationPct  moon illumination percentage (0–100)
 * @param nightStart           start of the night window this score was evaluated over — nautical
 *                             dusk, UTC (naive, matching the entity's own {@code _utc}-suffixed
 *                             columns). Served from the row's own stored
 *                             {@code nauticalDuskUtc}/{@code nauticalDawnUtc} (persisted since V64
 *                             at the fixed reference point {@code AstroConditionsService} scores
 *                             every night from), so this is the window the row's rating actually
 *                             reflects — never a value recomputed against a solar calculation that
 *                             may have since changed. Only a legacy row with null stored columns
 *                             falls back to a recompute, and that fallback is explicit and
 *                             documented at {@code AstroConditionsService.resolveNightWindow}.
 * @param nightEnd             end of the night window this score was evaluated over — nautical
 *                             dawn (the following morning), UTC. Same provenance as
 *                             {@code nightStart}.
 */
public record AstroConditionsDto(
        Long locationId,
        String locationName,
        double lat,
        double lon,
        Integer bortleClass,
        int stars,
        String summary,
        String cloudExplanation,
        String visibilityExplanation,
        String moonExplanation,
        LocalDate forecastDate,
        String moonPhase,
        Double moonIlluminationPct,
        LocalDateTime nightStart,
        LocalDateTime nightEnd
) {
}
