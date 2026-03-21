package com.gregochr.goldenhour.model;

/**
 * DTO representing a stored aurora forecast result for the map view.
 *
 * <p>Returned by {@code GET /api/aurora/forecast/results?date=...} so the frontend
 * can display aurora ratings on the map for any date that has been forecast.
 *
 * @param locationId    database ID of the location
 * @param locationName  human-readable location name (matches the location entity name)
 * @param lat           latitude in decimal degrees
 * @param lon           longitude in decimal degrees
 * @param bortleClass   Bortle dark-sky class (1 = darkest, 9 = most light-polluted), or null
 * @param stars         aurora photography rating, 1–5 stars
 * @param summary       one-line summary for the map popup
 * @param detail        multi-line factor breakdown (✓/–/✗ bullets) or null if triaged
 * @param triaged       true if this result was produced by weather triage template (not Claude)
 * @param triageReason  reason for triage rejection, or null if Claude-scored
 * @param alertLevel    geomagnetic alert level (QUIET, MINOR, MODERATE, STRONG)
 * @param maxKp         highest Kp value forecast for this night
 */
public record AuroraForecastResultDto(
        Long locationId,
        String locationName,
        double lat,
        double lon,
        Integer bortleClass,
        int stars,
        String summary,
        String detail,
        boolean triaged,
        String triageReason,
        String alertLevel,
        double maxKp) {}
