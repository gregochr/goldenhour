package com.gregochr.goldenhour.model;

import java.math.BigDecimal;

/**
 * Core weather observations at the forecast slot.
 *
 * @param visibilityMetres          horizontal visibility in metres
 * @param windSpeedMs               wind speed in m/s
 * @param windDirectionDegrees      wind direction in degrees (0-360, meteorological)
 * @param precipitationMm           precipitation in mm
 * @param humidityPercent           relative humidity percentage (0-100)
 * @param weatherCode               WMO weather condition code
 * @param shortwaveRadiationWm2     incoming shortwave solar radiation in W/m²
 * @param dewPointCelsius           dew point temperature at 2 m above ground in °C, or null
 * @param pressureHpa               mean sea-level pressure in hPa, or null if unavailable
 * @param snowfallCm                snowfall in the preceding hour in centimetres, or null
 * @param snowDepthMetres           depth of snow lying on the ground in metres, or null
 * @param freezingLevelMetres       altitude of the 0 °C isotherm in metres above sea level, or null
 */
public record WeatherData(
        int visibilityMetres,
        BigDecimal windSpeedMs,
        int windDirectionDegrees,
        BigDecimal precipitationMm,
        int humidityPercent,
        int weatherCode,
        BigDecimal shortwaveRadiationWm2,
        Double dewPointCelsius,
        Double pressureHpa,
        Double snowfallCm,
        Double snowDepthMetres,
        Double freezingLevelMetres) {
}
