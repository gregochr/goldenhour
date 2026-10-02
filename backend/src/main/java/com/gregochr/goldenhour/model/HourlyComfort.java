package com.gregochr.goldenhour.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The comfort-only reading for one full UTC hour of one location — the six figures a wildlife
 * hide's hourly table shows, and nothing else.
 *
 * <p>Produced by {@code OpenMeteoResponseParser.extractComfortHours}, which is tolerant by
 * design: a reading that Open-Meteo left null stays null here rather than failing the hour,
 * except {@code temperatureCelsius}, without which the hour is not returned at all.
 *
 * @param hour                       the full UTC hour this reading is for
 * @param temperatureCelsius         air temperature at 2 m in °C; never null on a returned slot
 * @param apparentTemperatureCelsius feels-like temperature at 2 m in °C, or null
 * @param precipitationProbability   probability of precipitation in percent (0-100), or null
 * @param windSpeedMs                wind speed at 10 m in m/s, scale 2, or null
 * @param windDirectionDegrees       wind direction at 10 m in degrees, or null
 * @param precipitationMm            precipitation in mm over the hour, scale 2, or null
 */
public record HourlyComfort(
        LocalDateTime hour,
        Double temperatureCelsius,
        Double apparentTemperatureCelsius,
        Integer precipitationProbability,
        BigDecimal windSpeedMs,
        Integer windDirectionDegrees,
        BigDecimal precipitationMm) {
}
