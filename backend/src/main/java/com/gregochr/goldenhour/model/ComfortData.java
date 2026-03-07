package com.gregochr.goldenhour.model;

/**
 * Human comfort metrics for wildlife/outdoor photography planning.
 *
 * @param temperatureCelsius         air temperature at 2 m in °C
 * @param apparentTemperatureCelsius feels-like temperature at 2 m in °C
 * @param precipitationProbability   probability of precipitation in percent (0-100)
 */
public record ComfortData(
        Double temperatureCelsius,
        Double apparentTemperatureCelsius,
        Integer precipitationProbability) {
}
