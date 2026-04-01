package com.gregochr.goldenhour.model;

import java.time.LocalDate;

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
        Double moonIlluminationPct
) {
}
