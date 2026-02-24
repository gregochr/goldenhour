package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Pre-processed atmospheric data for the ±30-minute window around a solar event.
 *
 * <p>Populated from the Open-Meteo Forecast and Air Quality APIs and passed to
 * {@code EvaluationService} for Claude's colour-potential rating.
 *
 * @param locationName              human-readable location name (e.g. "Durham UK")
 * @param solarEventTime            UTC time of the sunrise or sunset being evaluated
 * @param targetType                SUNRISE or SUNSET
 * @param lowCloudPercent           low cloud cover percentage (0-100, 0-3 km)
 * @param midCloudPercent           mid cloud cover percentage (0-100, 3-8 km)
 * @param highCloudPercent          high cloud cover percentage (0-100, 8+ km)
 * @param visibilityMetres          horizontal visibility in metres
 * @param windSpeedMs               wind speed in m/s
 * @param windDirectionDegrees      wind direction in degrees (0-360, meteorological)
 * @param precipitationMm           precipitation in mm
 * @param humidityPercent           relative humidity percentage (0-100)
 * @param weatherCode               WMO weather condition code
 * @param boundaryLayerHeightMetres atmospheric boundary layer height in metres
 * @param shortwaveRadiationWm2     incoming shortwave solar radiation in W/m²
 * @param pm25                      fine particulate matter concentration in µg/m³
 * @param dustUgm3                  dust concentration in µg/m³
 * @param aerosolOpticalDepth       aerosol optical depth (dimensionless)
 * @param tideState                 current tide state at the solar event time, or null for inland
 * @param nextHighTideTime          UTC time of next high tide or null
 * @param nextHighTideHeightMetres  height of next high tide in metres or null
 * @param nextLowTideTime           UTC time of next low tide or null
 * @param nextLowTideHeightMetres   height of next low tide in metres or null
 * @param tideAligned               true if tide state matches location preference, null for inland
 */
public record AtmosphericData(
        String locationName,
        LocalDateTime solarEventTime,
        TargetType targetType,
        int lowCloudPercent,
        int midCloudPercent,
        int highCloudPercent,
        int visibilityMetres,
        BigDecimal windSpeedMs,
        int windDirectionDegrees,
        BigDecimal precipitationMm,
        int humidityPercent,
        int weatherCode,
        int boundaryLayerHeightMetres,
        BigDecimal shortwaveRadiationWm2,
        BigDecimal pm25,
        BigDecimal dustUgm3,
        BigDecimal aerosolOpticalDepth,
        TideState tideState,
        LocalDateTime nextHighTideTime,
        BigDecimal nextHighTideHeightMetres,
        LocalDateTime nextLowTideTime,
        BigDecimal nextLowTideHeightMetres,
        Boolean tideAligned) {
}
