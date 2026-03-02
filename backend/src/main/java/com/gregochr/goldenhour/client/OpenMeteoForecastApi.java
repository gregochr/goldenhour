package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * Declarative HTTP interface for the Open-Meteo Forecast API.
 *
 * <p>Backed by {@link org.springframework.web.client.RestClient} via
 * {@link org.springframework.web.service.invoker.HttpServiceProxyFactory}.
 */
@HttpExchange
public interface OpenMeteoForecastApi {

    /**
     * Fetches hourly weather forecast data for a location.
     *
     * @param latitude      latitude in decimal degrees
     * @param longitude     longitude in decimal degrees
     * @param hourly        comma-separated list of hourly parameters to return
     * @param windSpeedUnit wind speed unit (e.g. "ms")
     * @param timezone      timezone for timestamps (e.g. "UTC")
     * @return the deserialized forecast response
     */
    @GetExchange("/v1/forecast")
    OpenMeteoForecastResponse getForecast(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam String hourly,
            @RequestParam("wind_speed_unit") String windSpeedUnit,
            @RequestParam String timezone);
}
