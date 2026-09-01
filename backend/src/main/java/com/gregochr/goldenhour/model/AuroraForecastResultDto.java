package com.gregochr.goldenhour.model;

import java.time.LocalDateTime;

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
 * @param nightStart    start of the dark window this result was scored over — nautical dusk, UTC
 *                      (naive). Derived per this result's own {@code forecastDate} via
 *                      {@code AuroraForecastRunService.computeWindowForDate(date)} at serve time —
 *                      the same date-aware calculation the run itself was scored with. Never the
 *                      clock-based {@code AuroraPollingJob.calculateTonightWindow()}, which reads
 *                      no date at all and would silently pin tonight's window onto a T+1 or
 *                      historical row (the night-vs-date trap
 *                      {@code docs/engineering/aurora-night-selection.md} records).
 * @param nightEnd      end of the dark window this result was scored over — nautical dawn (the
 *                      following morning), UTC. Same provenance as {@code nightStart}.
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
        double maxKp,
        LocalDateTime nightStart,
        LocalDateTime nightEnd) {}
