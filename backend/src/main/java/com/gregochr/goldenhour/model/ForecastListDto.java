package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gregochr.goldenhour.entity.TargetType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Slim projection of a forecast evaluation for the map/list load path.
 *
 * <p>The map's {@code GET /api/forecast} returns hundreds of these (every enabled location × the
 * date window). It carries only what the markers, cluster averages, azimuth lines, date grouping,
 * and comfort rows actually read — the ~40 heavier popup-only fields (atmospherics, directional
 * cloud, tide/surge/inversion detail, bluebell, the full Claude {@code summary} text, …) are fetched
 * lazily per location via {@code GET /api/forecast/{id}} when a popup opens. Slimming this list
 * dramatically shrinks the payload, its serialization cost, and the frontend's localStorage cache
 * footprint at 200+ locations.
 *
 * <p>{@code summary} and {@code triageMessage} are nullable and populated <em>only for sparse
 * cached-only rows</em> (which have no persisted {@code id} and therefore no by-id detail fetch
 * path). For rich persisted rows they are {@code null} here and served by the detail endpoint —
 * that is where the big {@code summary} text is moved off the list. Comfort fields are populated on
 * every row so the wildlife/waterfall hourly comfort table and the popup's comfort line render
 * without a detail round-trip.
 *
 * @param id the forecast_evaluation id, or {@code null} for a sparse cached-only row
 * @param locationName location name (grouping key)
 * @param locationLat location latitude (discarded on the client, which uses /api/locations lat/lon)
 * @param locationLon location longitude (discarded on the client)
 * @param targetDate the forecast date
 * @param targetType SUNRISE / SUNSET / HOURLY
 * @param forecastRunAt when this slot was scored (most-recent-run dedup + footer)
 * @param solarEventTime the solar event instant (hourly slot key + auto-selection)
 * @param azimuthDeg solar azimuth for the selected-marker line
 * @param rating 1–5 star rating (marker medallion + filter)
 * @param fierySkyPotential role-selected Fiery Sky score (marker arc + cluster average)
 * @param goldenHourPotential role-selected Golden Hour score (marker arc + cluster average)
 * @param triageReason stand-down reason, or {@code null} when Claude evaluated the slot
 * @param summary the Claude "why" text — populated only for sparse rows (rich rows fetch it lazily)
 * @param triageMessage the triage detail text — populated only for sparse rows
 * @param temperatureCelsius air temperature (comfort)
 * @param apparentTemperatureCelsius "feels like" temperature (comfort)
 * @param windSpeed wind speed (comfort)
 * @param windDirection wind bearing (comfort)
 * @param precipitation precipitation amount (comfort)
 * @param precipitationProbabilityPercent precipitation probability (comfort)
 */
public record ForecastListDto(
        Long id,
        String locationName,
        BigDecimal locationLat,
        BigDecimal locationLon,
        LocalDate targetDate,
        TargetType targetType,
        LocalDateTime forecastRunAt,
        LocalDateTime solarEventTime,
        Integer azimuthDeg,
        Integer rating,
        Integer fierySkyPotential,
        Integer goldenHourPotential,
        @JsonInclude(JsonInclude.Include.NON_NULL) TriageReason triageReason,
        @JsonInclude(JsonInclude.Include.NON_NULL) String summary,
        @JsonInclude(JsonInclude.Include.NON_NULL) String triageMessage,
        Double temperatureCelsius,
        Double apparentTemperatureCelsius,
        BigDecimal windSpeed,
        Integer windDirection,
        BigDecimal precipitation,
        Integer precipitationProbabilityPercent) {
}
