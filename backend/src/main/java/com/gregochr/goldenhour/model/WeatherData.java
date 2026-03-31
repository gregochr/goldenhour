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
        Double pressureHpa) {
}
