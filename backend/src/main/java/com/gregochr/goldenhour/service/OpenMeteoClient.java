package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.client.OpenMeteoAirQualityApi;
import com.gregochr.goldenhour.client.OpenMeteoForecastApi;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Resilient wrapper around the Open-Meteo HTTP APIs.
 *
 * <p>Delegates to {@link OpenMeteoForecastApi} and {@link OpenMeteoAirQualityApi}
 * declarative HTTP interfaces. Each call is protected by retry (transient 5xx/429),
 * circuit breaker (fail fast when Open-Meteo is down), and rate limiter (stay within
 * Open-Meteo's free-tier minutely quota).
 *
 * <p>Batch methods accept multiple coordinates and issue a single HTTP request using
 * Open-Meteo's comma-separated lat/lon support. This dramatically reduces the daily
 * API call count compared to individual per-location calls.
 */
@Service
public class OpenMeteoClient {

    private static final Logger LOG = LoggerFactory.getLogger(OpenMeteoClient.class);

    /** Comma-separated hourly forecast parameters requested from Open-Meteo. */
    static final String FORECAST_PARAMS =
            "cloud_cover_low,cloud_cover_mid,cloud_cover_high,visibility,"
            + "wind_speed_10m,wind_direction_10m,precipitation,weather_code,"
            + "relative_humidity_2m,surface_pressure,shortwave_radiation,boundary_layer_height,"
            + "temperature_2m,apparent_temperature,precipitation_probability,dew_point_2m,"
            + "pressure_msl,wind_gusts_10m";

    /** Comma-separated cloud-only parameters for directional horizon sampling. */
    static final String CLOUD_ONLY_PARAMS = "cloud_cover_low,cloud_cover_mid,cloud_cover_high";

    /** Comma-separated hourly air quality parameters requested from Open-Meteo. */
    static final String AIR_QUALITY_PARAMS = "pm2_5,dust,aerosol_optical_depth";

    private final OpenMeteoForecastApi forecastApi;
    private final OpenMeteoAirQualityApi airQualityApi;
    private final RestClient forecastRestClient;
    private final RestClient airQualityRestClient;
    private final ObjectMapper objectMapper;

    /**
     * Constructs an {@code OpenMeteoClient}.
     *
     * @param forecastApi   proxy for the Open-Meteo forecast endpoint
     * @param airQualityApi proxy for the Open-Meteo air quality endpoint
     * @param objectMapper  Jackson object mapper for batch response parsing
     */
    @org.springframework.beans.factory.annotation.Autowired
    public OpenMeteoClient(OpenMeteoForecastApi forecastApi, OpenMeteoAirQualityApi airQualityApi,
            ObjectMapper objectMapper) {
        this(forecastApi, airQualityApi, objectMapper,
                RestClient.builder().baseUrl("https://api.open-meteo.com").build(),
                RestClient.builder().baseUrl("https://air-quality-api.open-meteo.com").build());
    }

    /**
     * Package-private constructor for unit tests, allowing mock RestClients for batch methods.
     */
    OpenMeteoClient(OpenMeteoForecastApi forecastApi, OpenMeteoAirQualityApi airQualityApi,
            ObjectMapper objectMapper, RestClient forecastRestClient,
            RestClient airQualityRestClient) {
        this.forecastApi = forecastApi;
        this.airQualityApi = airQualityApi;
        this.objectMapper = objectMapper;
        this.forecastRestClient = forecastRestClient;
        this.airQualityRestClient = airQualityRestClient;
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

    // ──────────────────────────────── Batch methods ────────────────────────────────

    /**
     * Fetches cloud-only forecast data for multiple points in a single API call.
     *
     * <p>Open-Meteo accepts comma-separated latitude/longitude values and returns
     * a JSON array (for 2+ points) or a single object (for 1 point). This method
     * handles both cases transparently.
     *
     * @param coords list of [lat, lon] pairs
     * @return list of responses in the same order as the input coordinates
     */
    @Retry(name = "open-meteo")
    @CircuitBreaker(name = "open-meteo")
    @RateLimiter(name = "open-meteo")
    public List<OpenMeteoForecastResponse> fetchCloudOnlyBatch(List<double[]> coords) {
        return fetchForecastBatchInternal(coords, CLOUD_ONLY_PARAMS, forecastRestClient);
    }

    /**
     * Fetches full forecast data for multiple points in a single API call.
     *
     * @param coords list of [lat, lon] pairs
     * @return list of responses in the same order as the input coordinates
     */
    @Retry(name = "open-meteo")
    @CircuitBreaker(name = "open-meteo")
    @RateLimiter(name = "open-meteo")
    public List<OpenMeteoForecastResponse> fetchForecastBatch(List<double[]> coords) {
        return fetchForecastBatchInternal(coords, FORECAST_PARAMS, forecastRestClient);
    }

    /**
     * Fetches full forecast data for multiple points using the briefing circuit breaker.
     *
     * @param coords list of [lat, lon] pairs
     * @return list of responses in the same order as the input coordinates
     */
    @Retry(name = "open-meteo-briefing")
    @CircuitBreaker(name = "open-meteo-briefing")
    @RateLimiter(name = "open-meteo")
    public List<OpenMeteoForecastResponse> fetchForecastBriefingBatch(List<double[]> coords) {
        return fetchForecastBatchInternal(coords, FORECAST_PARAMS, forecastRestClient);
    }

    /**
     * Fetches air quality data for multiple points in a single API call.
     *
     * @param coords list of [lat, lon] pairs
     * @return list of responses in the same order as the input coordinates
     */
    @Retry(name = "open-meteo")
    @CircuitBreaker(name = "open-meteo")
    @RateLimiter(name = "open-meteo")
    public List<OpenMeteoAirQualityResponse> fetchAirQualityBatch(List<double[]> coords) {
        String latitudes = coords.stream()
                .map(c -> String.valueOf(c[0])).collect(Collectors.joining(","));
        String longitudes = coords.stream()
                .map(c -> String.valueOf(c[1])).collect(Collectors.joining(","));

        LOG.debug("Open-Meteo batch air-quality: {} points", coords.size());
        String json = airQualityRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/air-quality")
                        .queryParam("latitude", latitudes)
                        .queryParam("longitude", longitudes)
                        .queryParam("hourly", AIR_QUALITY_PARAMS)
                        .queryParam("timezone", "UTC")
                        .build())
                .retrieve()
                .body(String.class);

        return parseArrayResponse(json, OpenMeteoAirQualityResponse.class);
    }

    /**
     * Internal batch forecast fetch — shared by cloud-only, full forecast, and briefing methods.
     */
    private List<OpenMeteoForecastResponse> fetchForecastBatchInternal(
            List<double[]> coords, String hourlyParams, RestClient client) {
        String latitudes = coords.stream()
                .map(c -> String.valueOf(c[0])).collect(Collectors.joining(","));
        String longitudes = coords.stream()
                .map(c -> String.valueOf(c[1])).collect(Collectors.joining(","));

        LOG.debug("Open-Meteo batch forecast: {} points, params={}",
                coords.size(), hourlyParams.substring(0, Math.min(30, hourlyParams.length())) + "...");
        String json = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/forecast")
                        .queryParam("latitude", latitudes)
                        .queryParam("longitude", longitudes)
                        .queryParam("hourly", hourlyParams)
                        .queryParam("wind_speed_unit", "ms")
                        .queryParam("timezone", "UTC")
                        .build())
                .retrieve()
                .body(String.class);

        return parseArrayResponse(json, OpenMeteoForecastResponse.class);
    }

    /**
     * Parses the Open-Meteo response which is a single object for 1 location
     * or a JSON array for 2+ locations.
     */
    private <T> List<T> parseArrayResponse(String json, Class<T> type) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<T> results = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    results.add(objectMapper.treeToValue(node, type));
                }
            } else {
                results.add(objectMapper.treeToValue(root, type));
            }
            return results;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Open-Meteo batch response", e);
        }
    }
}
