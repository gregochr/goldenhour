package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenMeteoAirQualityApi;
import com.gregochr.goldenhour.client.OpenMeteoForecastApi;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Service;

/**
 * Resilient wrapper around the Open-Meteo HTTP APIs.
 *
 * <p>Delegates to {@link OpenMeteoForecastApi} and {@link OpenMeteoAirQualityApi}
 * declarative HTTP interfaces. Each call is protected by retry (transient 5xx/429),
 * circuit breaker (fail fast when Open-Meteo is down), and rate limiter (stay within
 * Open-Meteo's free-tier minutely quota).
 */
@Service
public class OpenMeteoClient {

    /** Comma-separated hourly forecast parameters requested from Open-Meteo. */
    static final String FORECAST_PARAMS =
            "cloud_cover_low,cloud_cover_mid,cloud_cover_high,visibility,"
            + "wind_speed_10m,wind_direction_10m,precipitation,weather_code,"
            + "relative_humidity_2m,surface_pressure,shortwave_radiation,boundary_layer_height,"
            + "temperature_2m,apparent_temperature,precipitation_probability,dew_point_2m";

    /** Comma-separated cloud-only parameters for directional horizon sampling. */
    static final String CLOUD_ONLY_PARAMS = "cloud_cover_low,cloud_cover_mid,cloud_cover_high";

    /** Comma-separated hourly air quality parameters requested from Open-Meteo. */
    static final String AIR_QUALITY_PARAMS = "pm2_5,dust,aerosol_optical_depth";

    private final OpenMeteoForecastApi forecastApi;
    private final OpenMeteoAirQualityApi airQualityApi;

    /**
     * Constructs an {@code OpenMeteoClient}.
     *
     * @param forecastApi   proxy for the Open-Meteo forecast endpoint
     * @param airQualityApi proxy for the Open-Meteo air quality endpoint
     */
    public OpenMeteoClient(OpenMeteoForecastApi forecastApi, OpenMeteoAirQualityApi airQualityApi) {
        this.forecastApi = forecastApi;
        this.airQualityApi = airQualityApi;
    }

    /**
     * Fetches hourly weather forecast data for a location.
     *
     * @param lat latitude in decimal degrees
     * @param lon longitude in decimal degrees
     * @return the deserialized forecast response
     */
    @Retry(name = "open-meteo")
    @CircuitBreaker(name = "open-meteo")
    @RateLimiter(name = "open-meteo")
    public OpenMeteoForecastResponse fetchForecast(double lat, double lon) {
        return forecastApi.getForecast(lat, lon, FORECAST_PARAMS, "ms", "UTC");
    }

    /**
     * Fetches hourly weather forecast data for a location, using a dedicated circuit breaker
     * and no retries. Used by the briefing job so that forecast-run or aurora failures cannot
     * trip the briefing circuit breaker and vice versa.
     *
     * @param lat latitude in decimal degrees
     * @param lon longitude in decimal degrees
     * @return the deserialized forecast response
     */
    @Retry(name = "open-meteo-briefing")
    @CircuitBreaker(name = "open-meteo-briefing")
    @RateLimiter(name = "open-meteo")
    public OpenMeteoForecastResponse fetchForecastBriefing(double lat, double lon) {
        return forecastApi.getForecast(lat, lon, FORECAST_PARAMS, "ms", "UTC");
    }

    /**
     * Fetches hourly air quality data for a location.
     *
     * @param lat latitude in decimal degrees
     * @param lon longitude in decimal degrees
     * @return the deserialized air quality response
     */
    @Retry(name = "open-meteo")
    @CircuitBreaker(name = "open-meteo")
    @RateLimiter(name = "open-meteo")
    public OpenMeteoAirQualityResponse fetchAirQuality(double lat, double lon) {
        return airQualityApi.getAirQuality(lat, lon, AIR_QUALITY_PARAMS, "UTC");
    }

    /**
     * Fetches cloud-only forecast data for a directional horizon sampling point.
     *
     * @param lat latitude in decimal degrees
     * @param lon longitude in decimal degrees
     * @return the deserialized forecast response (only cloud layers populated)
     */
    @Retry(name = "open-meteo")
    @CircuitBreaker(name = "open-meteo")
    @RateLimiter(name = "open-meteo")
    public OpenMeteoForecastResponse fetchCloudOnly(double lat, double lon) {
        return forecastApi.getForecast(lat, lon, CLOUD_ONLY_PARAMS, "ms", "UTC");
    }
}
