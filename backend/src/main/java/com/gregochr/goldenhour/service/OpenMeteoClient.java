package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenMeteoAirQualityApi;
import com.gregochr.goldenhour.client.OpenMeteoForecastApi;
import com.gregochr.goldenhour.config.OpenMeteoRateLimiter;
import com.gregochr.goldenhour.config.TransientHttpErrorPredicate;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * Resilient wrapper around the Open-Meteo HTTP APIs.
 *
 * <p>Delegates to {@link OpenMeteoForecastApi} and {@link OpenMeteoAirQualityApi}
 * declarative HTTP interfaces. Each call is retried on transient errors (5xx and 429)
 * using Spring's {@code @Retryable} annotation with exponential backoff.
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
    private final OpenMeteoRateLimiter rateLimiter;

    /**
     * Constructs an {@code OpenMeteoClient}.
     *
     * @param forecastApi   proxy for the Open-Meteo forecast endpoint
     * @param airQualityApi proxy for the Open-Meteo air quality endpoint
     * @param rateLimiter   rate limiter to stay within Open-Meteo's minutely quota
     */
    public OpenMeteoClient(OpenMeteoForecastApi forecastApi, OpenMeteoAirQualityApi airQualityApi,
            OpenMeteoRateLimiter rateLimiter) {
        this.forecastApi = forecastApi;
        this.airQualityApi = airQualityApi;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Fetches hourly weather forecast data for a location.
     *
     * @param lat latitude in decimal degrees
     * @param lon longitude in decimal degrees
     * @return the deserialized forecast response
     */
    @Retryable(includes = RestClientResponseException.class,
               predicate = TransientHttpErrorPredicate.class,
               maxRetries = 2, delay = 5000, multiplier = 2)
    public OpenMeteoForecastResponse fetchForecast(double lat, double lon) {
        return rateLimited(() -> forecastApi.getForecast(lat, lon, FORECAST_PARAMS, "ms", "UTC"));
    }

    /**
     * Fetches hourly air quality data for a location.
     *
     * @param lat latitude in decimal degrees
     * @param lon longitude in decimal degrees
     * @return the deserialized air quality response
     */
    @Retryable(includes = RestClientResponseException.class,
               predicate = TransientHttpErrorPredicate.class,
               maxRetries = 2, delay = 5000, multiplier = 2)
    public OpenMeteoAirQualityResponse fetchAirQuality(double lat, double lon) {
        return rateLimited(() -> airQualityApi.getAirQuality(lat, lon, AIR_QUALITY_PARAMS, "UTC"));
    }

    /**
     * Fetches cloud-only forecast data for a directional horizon sampling point.
     *
     * @param lat latitude in decimal degrees
     * @param lon longitude in decimal degrees
     * @return the deserialized forecast response (only cloud layers populated)
     */
    @Retryable(includes = RestClientResponseException.class,
               predicate = TransientHttpErrorPredicate.class,
               maxRetries = 2, delay = 5000, multiplier = 2)
    public OpenMeteoForecastResponse fetchCloudOnly(double lat, double lon) {
        return rateLimited(() -> forecastApi.getForecast(lat, lon, CLOUD_ONLY_PARAMS, "ms", "UTC"));
    }

    /**
     * Executes a supplier within the rate limiter's acquire/release lifecycle.
     *
     * @param supplier the API call to execute
     * @param <T>      the response type
     * @return the API response
     */
    private <T> T rateLimited(java.util.function.Supplier<T> supplier) {
        try {
            rateLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Open-Meteo rate limit", e);
        }
        try {
            return supplier.get();
        } finally {
            rateLimiter.release();
        }
    }
}
