package com.gregochr.goldenhour.model;

/**
 * Pairs extracted {@link AtmosphericData} with the raw Open-Meteo forecast and air quality
 * responses for use cases that need to query the hourly arrays at non-solar-event times
 * (e.g. storm surge calculation at high-tide time) or for batch pre-fetch caching.
 *
 * @param atmosphericData    the extracted atmospheric data at the solar event time
 * @param forecastResponse   the raw Open-Meteo hourly forecast response
 * @param airQualityResponse the raw Open-Meteo air quality response (nullable for legacy callers)
 */
public record WeatherExtractionResult(
        AtmosphericData atmosphericData,
        OpenMeteoForecastResponse forecastResponse,
        OpenMeteoAirQualityResponse airQualityResponse) {

    /** Convenience constructor for callers that don't need the air quality response. */
    public WeatherExtractionResult(AtmosphericData atmosphericData,
            OpenMeteoForecastResponse forecastResponse) {
        this(atmosphericData, forecastResponse, null);
    }
}
