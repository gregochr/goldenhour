package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * API response DTO for forecast evaluations.
 *
 * <p>Decouples the REST API contract from the JPA persistence model. Role-based score
 * selection (LITE users get basic scores, PRO/ADMIN get enhanced directional scores)
 * is handled by {@link ForecastDtoMapper} at mapping time — the {@code basic_*} columns
 * never appear in this DTO.
 *
 * @param id                             database primary key
 * @param locationName                   human-readable location name
 * @param locationLat                    latitude in decimal degrees
 * @param locationLon                    longitude in decimal degrees
 * @param targetDate                     calendar date being forecast
 * @param targetType                     SUNRISE, SUNSET, or HOURLY
 * @param forecastRunAt                  UTC timestamp when the evaluation was computed
 * @param daysAhead                      days between run date and target date
 * @param rating                         1–5 star rating (nullable)
 * @param fierySkyPotential              dramatic colour potential 0–100 (role-aware)
 * @param goldenHourPotential            light quality potential 0–100 (role-aware)
 * @param summary                        plain English explanation (role-aware)
 * @param solarEventTime                 UTC time of the solar event
 * @param azimuthDeg                     compass azimuth of the solar event (nullable)
 * @param evaluationModel                which model produced this evaluation
 * @param lowCloud                       low cloud cover % at observer point
 * @param midCloud                       mid cloud cover % at observer point
 * @param highCloud                      high cloud cover % at observer point
 * @param visibility                     visibility in metres
 * @param windSpeed                      wind speed in m/s
 * @param windDirection                  wind direction in degrees
 * @param precipitation                  precipitation in mm
 * @param humidity                       relative humidity %
 * @param weatherCode                    WMO weather condition code
 * @param boundaryLayerHeight            boundary layer height in metres
 * @param shortwaveRadiation             shortwave radiation in W/m²
 * @param pm25                           PM2.5 in µg/m³
 * @param dust                           dust in µg/m³
 * @param aerosolOpticalDepth            aerosol optical depth (dimensionless)
 * @param temperatureCelsius             air temperature at 2 m in °C
 * @param apparentTemperatureCelsius     feels-like temperature in °C
 * @param precipitationProbabilityPercent precipitation probability %
 * @param dewPointCelsius                dew point at 2 m in °C (nullable for historical rows)
 * @param tideState                      tide state at event time (nullable for inland)
 * @param nextHighTideTime               UTC time of next high tide (nullable)
 * @param nextHighTideHeightMetres       height of next high tide in metres (nullable)
 * @param nextLowTideTime                UTC time of next low tide (nullable)
 * @param nextLowTideHeightMetres        height of next low tide in metres (nullable)
 * @param tideAligned                    whether tide matches location preference (nullable)
 * @param solarLowCloud                  low cloud at solar horizon 50 km offset (nullable)
 * @param solarMidCloud                  mid cloud at solar horizon 50 km offset (nullable)
 * @param solarHighCloud                 high cloud at solar horizon 50 km offset (nullable)
 * @param antisolarLowCloud              low cloud at antisolar horizon 50 km offset (nullable)
 * @param antisolarMidCloud              mid cloud at antisolar horizon 50 km offset (nullable)
 * @param antisolarHighCloud             high cloud at antisolar horizon offset (nullable)
 * @param solarTrendEventLowCloud        low cloud at solar horizon at event time (nullable)
 * @param solarTrendEarliestLowCloud     low cloud at solar horizon at T-3h (nullable)
 * @param solarTrendBuilding             true if solar horizon cloud trend is building (nullable)
 * @param upwindCurrentLowCloud          low cloud at upwind point now (nullable)
 * @param upwindEventLowCloud            low cloud at upwind point at event time (nullable)
 * @param upwindDistanceKm               distance to upwind sample point in km (nullable)
 */
public record ForecastEvaluationDto(
        Long id,
        String locationName,
        BigDecimal locationLat,
        BigDecimal locationLon,
        LocalDate targetDate,
        TargetType targetType,
        LocalDateTime forecastRunAt,
        Integer daysAhead,
        Integer rating,
        Integer fierySkyPotential,
        Integer goldenHourPotential,
        String summary,
        LocalDateTime solarEventTime,
        Integer azimuthDeg,
        EvaluationModel evaluationModel,
        Integer lowCloud,
        Integer midCloud,
        Integer highCloud,
        Integer visibility,
        BigDecimal windSpeed,
        Integer windDirection,
        BigDecimal precipitation,
        Integer humidity,
        Integer weatherCode,
        Integer boundaryLayerHeight,
        BigDecimal shortwaveRadiation,
        BigDecimal pm25,
        BigDecimal dust,
        BigDecimal aerosolOpticalDepth,
        Double temperatureCelsius,
        Double apparentTemperatureCelsius,
        Integer precipitationProbabilityPercent,
        Double dewPointCelsius,
        TideState tideState,
        LocalDateTime nextHighTideTime,
        BigDecimal nextHighTideHeightMetres,
        LocalDateTime nextLowTideTime,
        BigDecimal nextLowTideHeightMetres,
        Boolean tideAligned,
        Integer solarLowCloud,
        Integer solarMidCloud,
        Integer solarHighCloud,
        Integer antisolarLowCloud,
        Integer antisolarMidCloud,
        Integer antisolarHighCloud,
        Integer solarTrendEventLowCloud,
        Integer solarTrendEarliestLowCloud,
        Boolean solarTrendBuilding,
        Integer upwindCurrentLowCloud,
        Integer upwindEventLowCloud,
        Integer upwindDistanceKm) {
}
