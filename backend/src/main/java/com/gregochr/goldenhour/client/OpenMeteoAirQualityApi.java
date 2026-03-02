package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * Declarative HTTP interface for the Open-Meteo Air Quality API.
 *
 * <p>Backed by {@link org.springframework.web.client.RestClient} via
 * {@link org.springframework.web.service.invoker.HttpServiceProxyFactory}.
 */
@HttpExchange
public interface OpenMeteoAirQualityApi {

    /**
     * Fetches hourly air quality data for a location.
     *
     * @param latitude  latitude in decimal degrees
     * @param longitude longitude in decimal degrees
     * @param hourly    comma-separated list of hourly parameters to return
     * @param timezone  timezone for timestamps (e.g. "UTC")
     * @return the deserialized air quality response
     */
    @GetExchange("/v1/air-quality")
    OpenMeteoAirQualityResponse getAirQuality(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam String hourly,
            @RequestParam String timezone);
}
