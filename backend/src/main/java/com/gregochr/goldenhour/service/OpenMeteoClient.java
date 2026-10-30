package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.client.OpenMeteoAirQualityApi;
import com.gregochr.goldenhour.client.OpenMeteoForecastApi;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;
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
            // Total cloud (all layers): the valley-mist calculator's clear-night term. The three
            // layer series cannot stand in for it: their maximum understates the total.
            + "cloud_cover,"
            + "wind_speed_10m,wind_direction_10m,precipitation,weather_code,"
            + "relative_humidity_2m,surface_pressure,shortwave_radiation,boundary_layer_height,"
            + "temperature_2m,apparent_temperature,precipitation_probability,dew_point_2m,"
            + "pressure_msl,wind_gusts_10m,"
            + "snowfall,snow_depth,freezing_level_height,"
            // The only vertical temperature profile the pipeline has. Without it, a cloud
            // inversion can only be guessed at from surface readings — see
            // InversionScoreCalculator.
            + "temperature_925hPa,temperature_850hPa";

    /** Comma-separated cloud-only parameters for directional horizon sampling. */
    static final String CLOUD_ONLY_PARAMS = "cloud_cover_low,cloud_cover_mid,cloud_cover_high";

    /**
     * Comma-separated pressure-level cloud parameters, 850 hPa to 300 hPa. Requested on their own,
     * never alongside {@link #CLOUD_ONLY_PARAMS}, so a refusal cannot touch directional cloud.
     */
    static final String PRESSURE_LEVEL_CLOUD_PARAMS =
            "cloud_cover_850hPa,cloud_cover_700hPa,cloud_cover_600hPa,"
            + "cloud_cover_500hPa,cloud_cover_400hPa,cloud_cover_300hPa";

    /** Total time the pressure-level fetch may spend before leaving the remaining cells null. */
    static final Duration PRESSURE_LEVEL_FETCH_BUDGET = Duration.ofSeconds(90);

    /**
     * Hourly variables of the v2 inversion valley profile (inversion-v2-plan Phase 1b), fetched at a
     * location's valley-floor point with {@code elevation=} set to the floor. Requested on their own, never
     * added to {@link #FORECAST_PARAMS}, so no mist input or main-fetch variable changes.
     */
    static final String INVERSION_PROFILE_PARAMS =
            "temperature_2m,dew_point_2m,cloud_cover_low,cloud_cover_mid,cloud_cover_high,visibility,"
            + "wind_speed_10m,"
            + "temperature_975hPa,temperature_950hPa,temperature_925hPa,temperature_900hPa,temperature_850hPa,"
            + "geopotential_height_975hPa,geopotential_height_950hPa,geopotential_height_925hPa,"
            + "geopotential_height_900hPa,geopotential_height_850hPa,"
            + "wind_speed_975hPa,wind_speed_950hPa,wind_speed_925hPa,wind_speed_900hPa,wind_speed_850hPa";

    /**
     * Days of history the inversion profile request adds ({@code past_days}), so a day-0 sunrise still has
     * the previous evening for the clear-night measure (inversion-v2-plan Phase 2). Sent on the profile
     * request ONLY; the main, pressure-level and generic batches send none.
     */
    static final int INVERSION_PROFILE_PAST_DAYS = 1;

    /** Total time the inversion profile fetch may spend before leaving the remaining sites without one. */
    static final Duration INVERSION_PROFILE_FETCH_BUDGET = Duration.ofSeconds(90);

    /** Comma-separated hourly air quality parameters requested from Open-Meteo. */
    static final String AIR_QUALITY_PARAMS = "pm2_5,dust,aerosol_optical_depth";

    /**
     * Maximum number of coordinate pairs per Open-Meteo batch request.
     *
     * <p>Open-Meteo uses comma-separated lat/lon query parameters. With {@code ~50} production
     * locations and long parameter strings, uncapped batch requests exceed the HTTP 414 URI-too-long
     * limit. Keeping chunks at 20 keeps each GET URL well within safe bounds.
     */
    static final int BATCH_COORD_LIMIT = 20;

    /**
     * Milliseconds to sleep between successive chunk requests within a single batch call.
     *
     * <p>Chunk HTTP requests bypass the Resilience4j rate limiter (which only wraps the
     * public batch method). A 3-second inter-chunk pause leaves headroom within Open-Meteo's
     * minutely quota even when the batch spans 9+ chunks (~27 s total, well within the 2-hour
     * briefing cadence).
     */
    private static final long INTER_CHUNK_DELAY_MS = 3_000;

    /**
     * Milliseconds to wait after detecting a 429 rate-limit response before retrying the
     * next chunk. 61 seconds is just over the Open-Meteo 1-minute rate-limit window.
     */
    private static final long RATE_LIMIT_BACKOFF_MS = 61_000;

    /**
     * Maximum number of retry attempts for a briefing chunk that fails with a transient error
     * (timeout, connection reset, 5xx). Rate-limit (429) failures are not retried — they use
     * the existing inter-chunk backoff instead.
     */
    private static final int CHUNK_MAX_RETRIES = 2;

    /**
     * Base backoff in milliseconds before retrying a transiently failed briefing chunk.
     * Doubles on each subsequent attempt: 2 s → 4 s.
     */
    private static final long CHUNK_RETRY_BASE_BACKOFF_MS = 2_000;

    private final OpenMeteoForecastApi forecastApi;
    private final OpenMeteoAirQualityApi airQualityApi;
    private final RestClient forecastRestClient;
    private final RestClient airQualityRestClient;
    private final ObjectMapper objectMapper;

    /**
     * Overridable in unit tests so that chunk-isolation tests do not sleep 3 seconds per chunk.
     * Production code always reads {@link #INTER_CHUNK_DELAY_MS}.
     */
    long interChunkDelayMs = INTER_CHUNK_DELAY_MS;

    /**
     * Overridable in unit tests so that rate-limit backoff tests do not sleep 61 seconds.
     * Production code always reads {@link #RATE_LIMIT_BACKOFF_MS}.
     */
    long rateLimitBackoffMs = RATE_LIMIT_BACKOFF_MS;

    /**
     * Overridable in unit tests so that chunk-retry tests do not sleep 2+ seconds per attempt.
     * Production code always reads {@link #CHUNK_RETRY_BASE_BACKOFF_MS}.
     */
    long chunkRetryBackoffMs = CHUNK_RETRY_BASE_BACKOFF_MS;

    /**
     * Time source for the pressure-level fetch budget, in milliseconds. Overridable in unit tests;
     * production code always reads the wall clock.
     */
    LongSupplier elapsedClockMs = System::currentTimeMillis;

    @Autowired(required = false)
    private RateLimiterRegistry rateLimiterRegistry;

    /**
     * Registers a WARN-level log listener for rate limiter rejections on startup.
     *
     * <p>When the {@code open-meteo} rate limiter rejects a call (e.g. because Open-Meteo's
     * minutely quota has been exhausted), Resilience4j throws {@code RequestNotPermitted}.
     * Without this listener that event is silent at the call site. This listener surfaces
     * every rejection so that recurring rate-limit pressure is visible in logs.
     */
    @PostConstruct
    void registerRateLimiterEventLogging() {
        if (rateLimiterRegistry == null) {
            return;
        }
        rateLimiterRegistry.rateLimiter("open-meteo")
                .getEventPublisher()
                .onFailure(event -> LOG.warn("Open-Meteo rate limiter REJECTED a call: {}", event));
    }

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
                RestClient.builder().baseUrl("https://api.open-meteo.com")
                        .requestFactory(batchRequestFactory()).build(),
                RestClient.builder().baseUrl("https://air-quality-api.open-meteo.com")
                        .requestFactory(batchRequestFactory()).build());
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
     * <p>For large inputs the request is split into chunks of {@value #BATCH_COORD_LIMIT}.
     * Failed chunks leave {@code null} entries at their positions in the returned list.
     * Callers must guard against {@code null} entries.
     *
     * @param coords list of [lat, lon] pairs
     * @return list of length {@code coords.size()} — {@code null} where a chunk failed
     */
    @Retry(name = "open-meteo")
    @CircuitBreaker(name = "open-meteo")
    @RateLimiter(name = "open-meteo")
    public List<OpenMeteoForecastResponse> fetchCloudOnlyBatch(List<double[]> coords) {
        return fetchForecastBatchInternal(coords, CLOUD_ONLY_PARAMS, forecastRestClient);
    }

    /**
     * Fetches pressure-level cloud cover (850 to 300 hPa) for multiple points. Best effort, data
     * only.
     *
     * <p>Shares only the {@code open-meteo} rate limiter with the rating-affecting fetches (the
     * quota is shared on purpose). It has no retry and no circuit breaker, so a failure here can
     * never open the {@code open-meteo} breaker those fetches use. Coordinates are chunked at
     * {@value #BATCH_COORD_LIMIT} with the usual inter-chunk pause. The first chunk that throws
     * (a 429 included) stops the fetch with one WARN and no rate-limit backoff, and the fetch also
     * stops once {@link #PRESSURE_LEVEL_FETCH_BUDGET} has elapsed (one INFO line counts the cells
     * skipped). Positions not fetched are {@code null}; callers must guard against that.
     *
     * @param coords list of [lat, lon] pairs
     * @return list of length {@code coords.size()} with {@code null} where nothing was fetched
     */
    @RateLimiter(name = "open-meteo")
    public List<OpenMeteoForecastResponse> fetchPressureLevelCloudBatch(List<double[]> coords) {
        List<OpenMeteoForecastResponse> results =
                new ArrayList<>(Collections.nCopies(coords.size(), null));
        long startMs = elapsedClockMs.getAsLong();
        for (int i = 0; i < coords.size(); i += BATCH_COORD_LIMIT) {
            if (elapsedClockMs.getAsLong() - startMs >= PRESSURE_LEVEL_FETCH_BUDGET.toMillis()) {
                LOG.info("Pressure-level cloud fetch budget of {}s reached, {} of {} cells skipped",
                        PRESSURE_LEVEL_FETCH_BUDGET.toSeconds(), coords.size() - i, coords.size());
                break;
            }
            if (i > 0) {
                sleepQuietly(interChunkDelayMs);
            }
            List<double[]> chunk = coords.subList(i, Math.min(i + BATCH_COORD_LIMIT, coords.size()));
            try {
                List<OpenMeteoForecastResponse> chunkResults =
                        fetchForecastChunk(chunk, PRESSURE_LEVEL_CLOUD_PARAMS, forecastRestClient);
                for (int j = 0; j < chunkResults.size() && i + j < results.size(); j++) {
                    results.set(i + j, chunkResults.get(j));
                }
            } catch (Exception e) {
                LOG.warn("Pressure-level cloud chunk {} failed, stopping with {} of {} cells unfetched: {}",
                        i / BATCH_COORD_LIMIT + 1, coords.size() - i, coords.size(),
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                break;
            }
        }
        return results;
    }

    /**
     * Fetches the v2 inversion valley profile for many valley-floor points (inversion-v2-plan Phase 1b).
     * Best effort, data only: it copies {@link #fetchPressureLevelCloudBatch}'s resilience.
     *
     * <p>Shares only the {@code open-meteo} rate limiter with the rating-affecting fetches. It has no retry
     * and no circuit breaker, so a failure here can never open the {@code open-meteo} breaker those fetches
     * use. Points are chunked at {@value #BATCH_COORD_LIMIT} with the usual inter-chunk pause, and each chunk
     * sends a comma-separated {@code elevation} list the same length as the coordinate lists, pinning each
     * point to its own valley-floor height. The first chunk that throws (a 429 included) stops the fetch with
     * one WARN and no backoff, and the fetch also stops once {@link #INVERSION_PROFILE_FETCH_BUDGET} has
     * elapsed. Positions not fetched are {@code null}; callers must guard against that.
     *
     * @param points list of [lat, lon, valley floor metres] triples
     * @return list of length {@code points.size()} with {@code null} where nothing was fetched
     */
    @RateLimiter(name = "open-meteo")
    public List<OpenMeteoForecastResponse> fetchInversionProfileBatch(List<double[]> points) {
        List<OpenMeteoForecastResponse> results =
                new ArrayList<>(Collections.nCopies(points.size(), null));
        long startMs = elapsedClockMs.getAsLong();
        for (int i = 0; i < points.size(); i += BATCH_COORD_LIMIT) {
            if (elapsedClockMs.getAsLong() - startMs >= INVERSION_PROFILE_FETCH_BUDGET.toMillis()) {
                LOG.info("Inversion profile fetch budget of {}s reached, {} of {} points skipped",
                        INVERSION_PROFILE_FETCH_BUDGET.toSeconds(), points.size() - i, points.size());
                break;
            }
            if (i > 0) {
                sleepQuietly(interChunkDelayMs);
            }
            List<double[]> chunk = points.subList(i, Math.min(i + BATCH_COORD_LIMIT, points.size()));
            List<double[]> coords = chunk.stream().map(p -> new double[]{p[0], p[1]}).toList();
            List<Integer> elevations = chunk.stream().map(p -> (int) Math.round(p[2])).toList();
            try {
                List<OpenMeteoForecastResponse> chunkResults = fetchForecastChunk(
                        coords, INVERSION_PROFILE_PARAMS, forecastRestClient, elevations,
                        INVERSION_PROFILE_PAST_DAYS);
                for (int j = 0; j < chunkResults.size() && i + j < results.size(); j++) {
                    results.set(i + j, chunkResults.get(j));
                }
            } catch (Exception e) {
                LOG.warn("Inversion profile chunk {} failed, stopping with {} of {} points unfetched: {}",
                        i / BATCH_COORD_LIMIT + 1, points.size() - i, points.size(),
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                break;
            }
        }
        return results;
    }

    /**
     * Fetches full forecast data for multiple points in a single API call.
     *
     * <p>For large inputs the request is split into chunks of {@value #BATCH_COORD_LIMIT}.
     * Failed chunks leave {@code null} entries at their positions in the returned list.
     * Callers must guard against {@code null} entries.
     *
     * @param coords list of [lat, lon] pairs
     * @return list of length {@code coords.size()} — {@code null} where a chunk failed
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
     * <p>Unlike {@link #fetchForecastBatch}, this variant isolates each chunk so that a
     * single failed chunk (e.g. a 429 rate-limit response) does not discard results from
     * chunks that already succeeded. Failed chunks leave {@code null} entries at the
     * corresponding positions in the returned list. Callers must guard against {@code null}
     * entries. If a 429 is detected the method applies a 61-second backoff before the next
     * chunk to wait out the Open-Meteo minutely rate-limit window.
     *
     * @param coords list of [lat, lon] pairs
     * @return list of length {@code coords.size()} — null where a chunk failed
     */
    @Retry(name = "open-meteo-briefing")
    @CircuitBreaker(name = "open-meteo-briefing")
    @RateLimiter(name = "open-meteo")
    public List<OpenMeteoForecastResponse> fetchForecastBriefingBatch(List<double[]> coords) {
        if (coords.size() <= BATCH_COORD_LIMIT) {
            return fetchBriefingChunkWithRetry(coords, 1, 1, new int[]{0});
        }

        int totalChunks = (int) Math.ceil((double) coords.size() / BATCH_COORD_LIMIT);
        int succeededChunks = 0;
        int failedChunks = 0;
        int totalRetries = 0;
        boolean hitRateLimit = false;

        // Pre-fill with nulls — failed chunk positions remain null
        List<OpenMeteoForecastResponse> results =
                new ArrayList<>(Collections.nCopies(coords.size(), null));

        for (int i = 0; i < coords.size(); i += BATCH_COORD_LIMIT) {
            int chunkIndex = i / BATCH_COORD_LIMIT + 1;

            if (i > 0) {
                long delay = hitRateLimit ? rateLimitBackoffMs : interChunkDelayMs;
                sleepQuietly(delay);
            }

            int end = Math.min(i + BATCH_COORD_LIMIT, coords.size());
            List<double[]> chunk = coords.subList(i, end);
            try {
                int[] retries = {0};
                List<OpenMeteoForecastResponse> chunkResults =
                        fetchBriefingChunkWithRetry(chunk, chunkIndex, totalChunks, retries);
                for (int j = 0; j < chunkResults.size(); j++) {
                    results.set(i + j, chunkResults.get(j));
                }
                succeededChunks++;
                totalRetries += retries[0];
                hitRateLimit = false;
            } catch (Exception e) {
                failedChunks++;
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                LOG.warn("Open-Meteo briefing batch chunk {}/{} failed ({} coords): {}",
                        chunkIndex, totalChunks, chunk.size(), reason);
                if (reason.contains("429")) {
                    hitRateLimit = true;
                    LOG.warn("Rate limit detected — applying {}s backoff before next chunk",
                            rateLimitBackoffMs / 1000);
                }
                // Null entries remain for this chunk's positions
            }
        }

        long populated = results.stream().filter(r -> r != null).count();
        LOG.info("Open-Meteo briefing batch: {}/{} chunks succeeded ({} retries used), "
                        + "{}/{} responses populated",
                succeededChunks, totalChunks, totalRetries, populated, coords.size());

        return results;
    }

    /**
     * Attempts to fetch a single briefing chunk, retrying up to {@value #CHUNK_MAX_RETRIES}
     * times on transient failures (timeouts, connection errors, 5xx).
     *
     * <p>Rate-limit (429) and non-transient errors are re-thrown immediately for the caller
     * to handle.
     *
     * @param chunk        coordinate pairs for this chunk
     * @param chunkIndex   1-based chunk index (for logging)
     * @param totalChunks  total number of chunks in the batch (for logging)
     * @param retryCounter single-element array; incremented on each retry attempt
     * @return parsed forecast responses for the chunk
     */
    private List<OpenMeteoForecastResponse> fetchBriefingChunkWithRetry(
            List<double[]> chunk, int chunkIndex, int totalChunks, int[] retryCounter) {

        Exception lastFailure = null;
        for (int attempt = 0; attempt <= CHUNK_MAX_RETRIES; attempt++) {
            try {
                List<OpenMeteoForecastResponse> result =
                        fetchForecastChunk(chunk, FORECAST_PARAMS, forecastRestClient);
                if (attempt > 0) {
                    LOG.info("Open-Meteo chunk {}/{} succeeded on retry attempt {}",
                            chunkIndex, totalChunks, attempt + 1);
                }
                return result;
            } catch (Exception e) {
                lastFailure = e;
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

                // 429 — propagate immediately; caller handles rate-limit backoff
                if (reason.contains("429")) {
                    throw e;
                }

                // Non-transient — no point retrying
                if (!isTransientFailure(e)) {
                    throw e;
                }

                // Last attempt exhausted
                if (attempt == CHUNK_MAX_RETRIES) {
                    break;
                }

                retryCounter[0]++;
                long backoffMs = chunkRetryBackoffMs * (1L << attempt); // 2s, 4s
                LOG.warn("Open-Meteo chunk {}/{} attempt {} failed ({}) — retrying in {}ms",
                        chunkIndex, totalChunks, attempt + 1, reason, backoffMs);
                sleepQuietly(backoffMs);
            }
        }
        throw new RuntimeException(
                "Chunk " + chunkIndex + "/" + totalChunks + " failed after "
                + (CHUNK_MAX_RETRIES + 1) + " attempts", lastFailure);
    }

    /**
     * Classifies whether an exception represents a transient failure worth retrying.
     *
     * <p>Returns {@code true} for I/O errors (timeouts, connection resets) and HTTP 5xx
     * server errors. Returns {@code false} for rate limits (429), client errors, and
     * parse failures.
     */
    private boolean isTransientFailure(Exception e) {
        String message = e.getMessage() != null ? e.getMessage() : "";
        if (message.contains("429")) {
            return false;
        }
        if (e instanceof ResourceAccessException) {
            return true;
        }
        if (e instanceof HttpServerErrorException) {
            return true;
        }
        return false;
    }

    /**
     * Fetches air quality data for multiple points, chunking into batches of
     * {@value #BATCH_COORD_LIMIT} to stay within HTTP URI length limits.
     *
     * @param coords list of [lat, lon] pairs
     * @return list of responses in the same order as the input coordinates
     */
    @Retry(name = "open-meteo")
    @CircuitBreaker(name = "open-meteo")
    @RateLimiter(name = "open-meteo")
    public List<OpenMeteoAirQualityResponse> fetchAirQualityBatch(List<double[]> coords) {
        if (coords.size() <= BATCH_COORD_LIMIT) {
            return fetchAirQualityChunk(coords);
        }
        List<OpenMeteoAirQualityResponse> results = new ArrayList<>();
        for (int i = 0; i < coords.size(); i += BATCH_COORD_LIMIT) {
            if (i > 0) {
                sleepQuietly(interChunkDelayMs);
            }
            List<double[]> chunk = coords.subList(i, Math.min(i + BATCH_COORD_LIMIT, coords.size()));
            results.addAll(fetchAirQualityChunk(chunk));
        }
        return results;
    }

    /**
     * Fetches air quality data for multiple points using chunk-level retry and isolation.
     *
     * <p>Like {@link #fetchForecastBriefingBatch}, each chunk is retried independently on
     * transient failures and a single failed chunk does not discard results from successful
     * chunks. Failed chunks leave {@code null} entries at the corresponding positions.
     *
     * @param coords list of [lat, lon] pairs
     * @return list of length {@code coords.size()} — {@code null} where a chunk failed
     */
    @Retry(name = "open-meteo-briefing")
    @CircuitBreaker(name = "open-meteo-briefing")
    @RateLimiter(name = "open-meteo")
    public List<OpenMeteoAirQualityResponse> fetchAirQualityBatchResilient(List<double[]> coords) {
        if (coords.size() <= BATCH_COORD_LIMIT) {
            return fetchAirQualityChunkWithRetry(coords, 1, 1, new int[]{0});
        }

        int totalChunks = (int) Math.ceil((double) coords.size() / BATCH_COORD_LIMIT);
        int succeededChunks = 0;
        int failedChunks = 0;
        int totalRetries = 0;
        boolean hitRateLimit = false;

        List<OpenMeteoAirQualityResponse> results =
                new ArrayList<>(Collections.nCopies(coords.size(), null));

        for (int i = 0; i < coords.size(); i += BATCH_COORD_LIMIT) {
            int chunkIndex = i / BATCH_COORD_LIMIT + 1;

            if (i > 0) {
                long delay = hitRateLimit ? rateLimitBackoffMs : interChunkDelayMs;
                sleepQuietly(delay);
            }

            int end = Math.min(i + BATCH_COORD_LIMIT, coords.size());
            List<double[]> chunk = coords.subList(i, end);
            try {
                int[] retries = {0};
                List<OpenMeteoAirQualityResponse> chunkResults =
                        fetchAirQualityChunkWithRetry(chunk, chunkIndex, totalChunks, retries);
                for (int j = 0; j < chunkResults.size(); j++) {
                    results.set(i + j, chunkResults.get(j));
                }
                succeededChunks++;
                totalRetries += retries[0];
                hitRateLimit = false;
            } catch (Exception e) {
                failedChunks++;
                String reason = e.getMessage() != null ? e.getMessage()
                        : e.getClass().getSimpleName();
                LOG.warn("Open-Meteo air quality batch chunk {}/{} failed ({} coords): {}",
                        chunkIndex, totalChunks, chunk.size(), reason);
                if (reason.contains("429")) {
                    hitRateLimit = true;
                    LOG.warn("Rate limit detected — applying {}s backoff before next chunk",
                            rateLimitBackoffMs / 1000);
                }
            }
        }

        long populated = results.stream().filter(r -> r != null).count();
        LOG.info("Open-Meteo air quality batch: {}/{} chunks succeeded ({} retries used), "
                        + "{}/{} responses populated",
                succeededChunks, totalChunks, totalRetries, populated, coords.size());

        return results;
    }

    /**
     * Attempts to fetch a single air quality chunk, retrying up to
     * {@value #CHUNK_MAX_RETRIES} times on transient failures.
     */
    private List<OpenMeteoAirQualityResponse> fetchAirQualityChunkWithRetry(
            List<double[]> chunk, int chunkIndex, int totalChunks, int[] retryCounter) {

        Exception lastFailure = null;
        for (int attempt = 0; attempt <= CHUNK_MAX_RETRIES; attempt++) {
            try {
                List<OpenMeteoAirQualityResponse> result = fetchAirQualityChunk(chunk);
                if (attempt > 0) {
                    LOG.info("Open-Meteo air quality chunk {}/{} succeeded on retry attempt {}",
                            chunkIndex, totalChunks, attempt + 1);
                }
                return result;
            } catch (Exception e) {
                lastFailure = e;
                String reason = e.getMessage() != null ? e.getMessage()
                        : e.getClass().getSimpleName();

                if (reason.contains("429")) {
                    throw e;
                }
                if (!isTransientFailure(e)) {
                    throw e;
                }
                if (attempt == CHUNK_MAX_RETRIES) {
                    break;
                }

                retryCounter[0]++;
                long backoffMs = chunkRetryBackoffMs * (1L << attempt);
                LOG.warn("Open-Meteo air quality chunk {}/{} attempt {} failed ({}) "
                                + "— retrying in {}ms",
                        chunkIndex, totalChunks, attempt + 1, reason, backoffMs);
                sleepQuietly(backoffMs);
            }
        }
        throw new RuntimeException(
                "Air quality chunk " + chunkIndex + "/" + totalChunks + " failed after "
                + (CHUNK_MAX_RETRIES + 1) + " attempts", lastFailure);
    }

    private List<OpenMeteoAirQualityResponse> fetchAirQualityChunk(List<double[]> coords) {
        String latitudes = coords.stream()
                .map(c -> String.valueOf(c[0])).collect(Collectors.joining(","));
        String longitudes = coords.stream()
                .map(c -> String.valueOf(c[1])).collect(Collectors.joining(","));

        LOG.debug("Open-Meteo batch air-quality chunk: {} points", coords.size());
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
     * Internal batch forecast fetch — shared by cloud-only and full forecast batch methods.
     *
     * <p>Chunks input into batches of {@value #BATCH_COORD_LIMIT} to avoid HTTP 414 errors.
     * Each chunk is wrapped in a try-catch so that one failed chunk does not discard the
     * results of chunks that already succeeded. Failed chunks leave {@code null} entries at
     * their positions. If a 429 rate-limit response is detected, a {@value #RATE_LIMIT_BACKOFF_MS}
     * ms backoff is applied before the next chunk. Callers must guard against {@code null} entries.
     *
     * @return list of length {@code coords.size()} — {@code null} where a chunk failed
     */
    private List<OpenMeteoForecastResponse> fetchForecastBatchInternal(
            List<double[]> coords, String hourlyParams, RestClient client) {
        if (coords.size() <= BATCH_COORD_LIMIT) {
            return fetchForecastChunk(coords, hourlyParams, client);
        }

        int totalChunks = (int) Math.ceil((double) coords.size() / BATCH_COORD_LIMIT);
        int succeededChunks = 0;
        boolean hitRateLimit = false;

        // Pre-fill with nulls — failed chunk positions remain null
        List<OpenMeteoForecastResponse> results =
                new ArrayList<>(Collections.nCopies(coords.size(), null));

        for (int i = 0; i < coords.size(); i += BATCH_COORD_LIMIT) {
            int chunkIndex = i / BATCH_COORD_LIMIT + 1;

            if (i > 0) {
                long delay = hitRateLimit ? rateLimitBackoffMs : interChunkDelayMs;
                sleepQuietly(delay);
            }

            int end = Math.min(i + BATCH_COORD_LIMIT, coords.size());
            List<double[]> chunk = coords.subList(i, end);
            try {
                List<OpenMeteoForecastResponse> chunkResults =
                        fetchForecastChunk(chunk, hourlyParams, client);
                for (int j = 0; j < chunkResults.size(); j++) {
                    results.set(i + j, chunkResults.get(j));
                }
                succeededChunks++;
                hitRateLimit = false;
            } catch (Exception e) {
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                LOG.warn("Open-Meteo batch chunk {}/{} failed ({} coords): {}",
                        chunkIndex, totalChunks, chunk.size(), reason);
                if (reason.contains("429")) {
                    hitRateLimit = true;
                    LOG.warn("Rate limit detected — applying {}s backoff before next chunk",
                            rateLimitBackoffMs / 1000);
                }
            }
        }

        long populated = results.stream().filter(r -> r != null).count();
        LOG.info("Open-Meteo batch complete: {}/{} chunks succeeded, {}/{} responses populated",
                succeededChunks, totalChunks, populated, coords.size());

        return results;
    }

    private List<OpenMeteoForecastResponse> fetchForecastChunk(
            List<double[]> coords, String hourlyParams, RestClient client) {
        return fetchForecastChunk(coords, hourlyParams, client, null, null);
    }

    /**
     * Fetches one chunk, optionally pinning each point's model surface with a comma-separated
     * {@code elevation} list (one value per coordinate, in order), and optionally adding {@code past_days}
     * (null sends none).
     */
    private List<OpenMeteoForecastResponse> fetchForecastChunk(
            List<double[]> coords, String hourlyParams, RestClient client, List<Integer> elevations,
            Integer pastDays) {
        String elevationList = elevations == null ? null
                : elevations.stream().map(String::valueOf).collect(Collectors.joining(","));
        String latitudes = coords.stream()
                .map(c -> String.valueOf(c[0])).collect(Collectors.joining(","));
        String longitudes = coords.stream()
                .map(c -> String.valueOf(c[1])).collect(Collectors.joining(","));

        LOG.debug("Open-Meteo batch forecast chunk: {} points, params={}",
                coords.size(), hourlyParams.substring(0, Math.min(30, hourlyParams.length())) + "...");
        String json = client.get()
                .uri(uriBuilder -> {
                    uriBuilder
                            .path("/v1/forecast")
                            .queryParam("latitude", latitudes)
                            .queryParam("longitude", longitudes)
                            .queryParam("hourly", hourlyParams)
                            .queryParam("wind_speed_unit", "ms")
                            .queryParam("timezone", "UTC");
                    if (elevationList != null) {
                        uriBuilder.queryParam("elevation", elevationList);
                    }
                    if (pastDays != null) {
                        uriBuilder.queryParam("past_days", pastDays);
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .body(String.class);

        return parseArrayResponse(json, OpenMeteoForecastResponse.class);
    }

    /**
     * Creates an HTTP request factory with connect and read timeouts for Open-Meteo batch calls.
     *
     * <p>Without explicit timeouts the default factory has no read timeout, so a slow or
     * unresponsive Open-Meteo server hangs the calling thread indefinitely (observed as
     * 181-second read hangs in production). A 30-second read timeout lets Resilience4j
     * retry and circuit-breaker react promptly instead.
     */
    static SimpleClientHttpRequestFactory batchRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }

    /**
     * Sleeps for the given number of milliseconds, swallowing {@code InterruptedException}.
     * Used to add a small pause between batch chunks so we do not burst Open-Meteo with many
     * back-to-back requests within a single decorated method call.
     */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
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
