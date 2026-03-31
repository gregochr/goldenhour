package com.gregochr.goldenhour.model;

/**
 * Pairs extracted {@link AtmosphericData} with the raw Open-Meteo forecast response
 * for use cases that need to query the hourly arrays at non-solar-event times
 * (e.g. storm surge calculation at high-tide time).
 *
 * @param atmosphericData the extracted atmospheric data at the solar event time
 * @param forecastResponse the raw Open-Meteo hourly forecast response
 */
public record WeatherExtractionResult(
        AtmosphericData atmosphericData,
        OpenMeteoForecastResponse forecastResponse) {
}
