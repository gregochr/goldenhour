package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.model.OpenMeteoMarineResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.List;

/**
 * Declarative HTTP interface for the Open-Meteo Marine Weather API.
 *
 * <p>A separate host ({@code https://marine-api.open-meteo.com}) from the forecast and air-quality
 * APIs, serving ocean sea-state (significant wave height, swell, wave direction). Backed by
 * {@link org.springframework.web.client.RestClient} via
 * {@link org.springframework.web.service.invoker.HttpServiceProxyFactory}.
 */
@HttpExchange
public interface OpenMeteoMarineApi {

    /**
     * Fetches hourly marine (sea-state) data for a location.
     *
     * @param latitude     latitude in decimal degrees
     * @param longitude    longitude in decimal degrees
     * @param hourly       comma-separated list of hourly parameters to return
     * @param timezone     timezone for timestamps (e.g. "UTC")
     * @param forecastDays number of forecast days to return
     * @return the deserialized marine response (empty hourly arrays for a land/ice grid cell)
     */
    @GetExchange("/v1/marine")
    OpenMeteoMarineResponse getMarine(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam String hourly,
            @RequestParam String timezone,
            @RequestParam("forecast_days") int forecastDays);

    /**
     * Fetches hourly marine data for several locations in one request.
     *
     * <p>Open-Meteo accepts comma-separated latitude and longitude lists and returns a JSON array
     * with one element per coordinate, in request order. A single coordinate returns a bare object
     * rather than an array, so callers send two or more points here and use
     * {@link #getMarine} for one.
     *
     * @param latitudes    comma-separated latitudes in decimal degrees
     * @param longitudes   comma-separated longitudes in decimal degrees, in the same order
     * @param hourly       comma-separated list of hourly parameters to return
     * @param timezone     timezone for timestamps (e.g. "UTC")
     * @param forecastDays number of forecast days to return
     * @return one response per requested coordinate, in request order
     */
    @GetExchange("/v1/marine")
    List<OpenMeteoMarineResponse> getMarineBatch(
            @RequestParam("latitude") String latitudes,
            @RequestParam("longitude") String longitudes,
            @RequestParam String hourly,
            @RequestParam String timezone,
            @RequestParam("forecast_days") int forecastDays);
}
