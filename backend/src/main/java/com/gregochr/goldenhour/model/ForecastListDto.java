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
 * date window). It carries only what the map's rating filters, the heat field, azimuth lines,
 * date grouping, and comfort rows actually read — the ~40 heavier popup-only fields (atmospherics, directional
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
 * @param rating 1–5 star rating (map rating filter + label chips)
 * @param fierySkyPotential role-selected Fiery Sky score
 * @param goldenHourPotential role-selected Golden Hour score
 * @param triageReason stand-down reason, or {@code null} when Claude evaluated the slot
 * @param summary the Claude "why" text — populated only for sparse rows (rich rows fetch it lazily)
 * @param triageMessage the triage detail text — populated only for sparse rows
 * @param temperatureCelsius air temperature (comfort)
 * @param apparentTemperatureCelsius "feels like" temperature (comfort)
 * @param windSpeed wind speed (comfort)
 * @param windDirection wind bearing (comfort)
 * @param precipitation precipitation amount (comfort)
 * @param precipitationProbabilityPercent precipitation probability (comfort)
 * @param gated {@code TRUE} when the row is outside the reader's sub-region picks and its rating fields were
 *              removed by {@code LitePickShaper}, otherwise null (never {@code FALSE}). Gated is not unrated
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
        Integer precipitationProbabilityPercent,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean gated) {

    /**
     * Twenty-one-component form, defaulting {@code gated} to null (an unshaped row). Retained so every call
     * site that predates the field keeps compiling unchanged; the withers thread {@code gated} through.
     *
     * @param id the forecast_evaluation id, or null for a sparse row
     * @param locationName location name
     * @param locationLat location latitude
     * @param locationLon location longitude
     * @param targetDate the forecast date
     * @param targetType SUNRISE / SUNSET / HOURLY
     * @param forecastRunAt when this slot was scored
     * @param solarEventTime the solar event instant
     * @param azimuthDeg solar azimuth
     * @param rating 1-5 star rating
     * @param fierySkyPotential role-selected Fiery Sky score
     * @param goldenHourPotential role-selected Golden Hour score
     * @param triageReason stand-down reason, or null
     * @param summary the Claude "why" text (sparse rows only)
     * @param triageMessage the triage detail text (sparse rows only)
     * @param temperatureCelsius air temperature
     * @param apparentTemperatureCelsius feels-like temperature
     * @param windSpeed wind speed
     * @param windDirection wind bearing
     * @param precipitation precipitation amount
     * @param precipitationProbabilityPercent precipitation probability
     */
    public ForecastListDto(Long id, String locationName, BigDecimal locationLat, BigDecimal locationLon,
            LocalDate targetDate, TargetType targetType, LocalDateTime forecastRunAt,
            LocalDateTime solarEventTime, Integer azimuthDeg, Integer rating, Integer fierySkyPotential,
            Integer goldenHourPotential, TriageReason triageReason, String summary, String triageMessage,
            Double temperatureCelsius, Double apparentTemperatureCelsius, BigDecimal windSpeed,
            Integer windDirection, BigDecimal precipitation, Integer precipitationProbabilityPercent) {
        this(id, locationName, locationLat, locationLon, targetDate, targetType, forecastRunAt,
                solarEventTime, azimuthDeg, rating, fierySkyPotential, goldenHourPotential, triageReason,
                summary, triageMessage, temperatureCelsius, apparentTemperatureCelsius, windSpeed,
                windDirection, precipitation, precipitationProbabilityPercent, null);
    }

    /**
     * A copy of this projection with a different star rating — how the as-of-event serve path puts
     * the star a reader actually saw on a row whose own {@code rating} is a later or sky-only figure.
     *
     * @param newRating the rating to serve, or {@code null} for an unscored slot
     * @return this projection with only {@code rating} replaced
     */
    public ForecastListDto withRating(Integer newRating) {
        return new ForecastListDto(id, locationName, locationLat, locationLon, targetDate, targetType,
                forecastRunAt, solarEventTime, azimuthDeg, newRating, fierySkyPotential,
                goldenHourPotential, triageReason, summary, triageMessage, temperatureCelsius,
                apparentTemperatureCelsius, windSpeed, windDirection, precipitation,
                precipitationProbabilityPercent, gated);
    }

    /**
     * A copy of this projection with no star and no scores — a slot whose star is not recoverable.
     * The scores go with the star because the map reads them alongside it whenever they are present,
     * so a null rating beside live scores would paint a scored place over an unscored window.
     * The stand-down reason is kept: it is a decision the reader saw, not a score.
     *
     * @return this projection with {@code rating}, {@code fierySkyPotential} and
     *         {@code goldenHourPotential} cleared
     */
    public ForecastListDto unscored() {
        return new ForecastListDto(id, locationName, locationLat, locationLon, targetDate, targetType,
                forecastRunAt, solarEventTime, azimuthDeg, null, null, null, triageReason, summary,
                triageMessage, temperatureCelsius, apparentTemperatureCelsius, windSpeed, windDirection,
                precipitation, precipitationProbabilityPercent, gated);
    }

    /**
     * A copy for a reader whose sub-region picks do not cover this location: the star, both potentials, the
     * written why, the stand-down reason and its message are removed, and {@link #gated} is set. The place,
     * the date, the event, the times and the weather inputs stay.
     *
     * @return a gated copy
     */
    public ForecastListDto forGated() {
        return new ForecastListDto(id, locationName, locationLat, locationLon, targetDate, targetType,
                forecastRunAt, solarEventTime, azimuthDeg, null, null, null, null, null, null,
                temperatureCelsius, apparentTemperatureCelsius, windSpeed, windDirection, precipitation,
                precipitationProbabilityPercent, Boolean.TRUE);
    }
}
