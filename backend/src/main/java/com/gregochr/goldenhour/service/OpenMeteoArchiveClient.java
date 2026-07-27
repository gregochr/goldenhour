package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Batched reader for the Open-Meteo Historical Weather (archive) API.
 *
 * <p>The archive accepts comma-separated coordinates and returns a JSON array, one entry per
 * point — the same shape as the forecast batch endpoint. That matters a great deal here: cloud
 * verification samples <em>two</em> points per evaluation (the solar horizon and the observer),
 * so a one-point-per-call backfill over a full history costs tens of thousands of requests and
 * runs into the daily rate ceiling. Batching {@value OpenMeteoClient#BATCH_COORD_LIMIT} points
 * per request cuts that by the same factor.
 *
 * <p>Separate from {@link OpenMeteoClient} deliberately: that class is already large, and the
 * archive has a different base URL, a different path, and date parameters instead of forecast
 * ones. It reuses {@code BATCH_COORD_LIMIT} so the two cannot drift on chunk size.
 */
@Component
public class OpenMeteoArchiveClient {

    private static final Logger LOG = LoggerFactory.getLogger(OpenMeteoArchiveClient.class);

    /** Milliseconds to pause between chunks, easing pressure on the shared rate limit. */
    private static final long INTER_CHUNK_DELAY_MS = 200L;

    /** Milliseconds to back off after a 429 before attempting the next chunk. */
    private static final long RATE_LIMIT_BACKOFF_MS = 60_000L;

    private final RestClient archiveRestClient;
    private final ObjectMapper objectMapper;

    /**
     * Constructs the client with a default archive {@link RestClient}.
     *
     * <p>Explicitly {@code @Autowired} because the class has two constructors — without it Spring
     * cannot choose between them and falls back to looking for a no-arg one, failing the whole
     * context. {@link OpenMeteoClient} carries the same annotation for the same reason.
     *
     * @param objectMapper mapper for the array response
     */
    @Autowired
    public OpenMeteoArchiveClient(ObjectMapper objectMapper) {
        this(objectMapper, RestClient.builder()
                .baseUrl("https://archive-api.open-meteo.com")
                .requestFactory(OpenMeteoClient.batchRequestFactory())
                .build());
    }

    /**
     * Package-private constructor for unit tests, allowing a mock {@link RestClient}.
     *
     * @param objectMapper      mapper for the array response
     * @param archiveRestClient the archive HTTP client
     */
    OpenMeteoArchiveClient(ObjectMapper objectMapper, RestClient archiveRestClient) {
        this.objectMapper = objectMapper;
        this.archiveRestClient = archiveRestClient;
    }

    /**
     * Fetches analysed cloud for many points on one date, in chunked batch requests.
     *
     * <p>Returns a list the same length as {@code coords}, positionally aligned, with
     * {@code null} where a chunk failed. Callers must guard against nulls — a null means
     * "no observation available", which cloud verification records rather than retries.
     *
     * @param coords     list of [lat, lon] pairs
     * @param date       the date to fetch (inclusive start and end)
     * @param hourlyVars comma-separated hourly variables to request
     * @return responses aligned with {@code coords}; {@code null} entries where a chunk failed
     */
    @Retry(name = "open-meteo")
    @CircuitBreaker(name = "open-meteo")
    @RateLimiter(name = "open-meteo")
    public List<OpenMeteoForecastResponse> fetchArchiveBatch(List<double[]> coords, LocalDate date,
            String hourlyVars) {
        if (coords == null || coords.isEmpty()) {
            return List.of();
        }
        if (coords.size() <= OpenMeteoClient.BATCH_COORD_LIMIT) {
            try {
                return fetchChunk(coords, date, hourlyVars);
            } catch (Exception e) {
                LOG.warn("Open-Meteo archive chunk failed ({} coords, {}): {}",
                        coords.size(), date, e.getMessage());
                return Collections.nCopies(coords.size(), null);
            }
        }

        List<OpenMeteoForecastResponse> results =
                new ArrayList<>(Collections.nCopies(coords.size(), null));
        boolean hitRateLimit = false;

        for (int i = 0; i < coords.size(); i += OpenMeteoClient.BATCH_COORD_LIMIT) {
            if (i > 0) {
                sleepQuietly(hitRateLimit ? RATE_LIMIT_BACKOFF_MS : INTER_CHUNK_DELAY_MS);
            }
            int end = Math.min(i + OpenMeteoClient.BATCH_COORD_LIMIT, coords.size());
            List<double[]> chunk = coords.subList(i, end);
            try {
                List<OpenMeteoForecastResponse> chunkResults = fetchChunk(chunk, date, hourlyVars);
                for (int j = 0; j < chunkResults.size() && i + j < results.size(); j++) {
                    results.set(i + j, chunkResults.get(j));
                }
                hitRateLimit = false;
            } catch (Exception e) {
                String reason = e.getMessage() != null ? e.getMessage()
                        : e.getClass().getSimpleName();
                LOG.warn("Open-Meteo archive chunk failed ({} coords, {}): {}",
                        chunk.size(), date, reason);
                hitRateLimit = reason.contains("429");
            }
        }
        return results;
    }

    /**
     * Fetches one chunk of coordinates for a single date.
     *
     * @param coords     the chunk's [lat, lon] pairs
     * @param date       the date to fetch
     * @param hourlyVars comma-separated hourly variables
     * @return one response per coordinate, in order
     */
    private List<OpenMeteoForecastResponse> fetchChunk(List<double[]> coords, LocalDate date,
            String hourlyVars) {
        String latitudes = coords.stream()
                .map(c -> String.valueOf(c[0])).collect(Collectors.joining(","));
        String longitudes = coords.stream()
                .map(c -> String.valueOf(c[1])).collect(Collectors.joining(","));
        String day = date.toString();

        String json = archiveRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/archive")
                        .queryParam("latitude", latitudes)
                        .queryParam("longitude", longitudes)
                        .queryParam("start_date", day)
                        .queryParam("end_date", day)
                        .queryParam("hourly", hourlyVars)
                        .queryParam("timezone", "UTC")
                        .build())
                .retrieve()
                .body(String.class);

        return parseArrayResponse(json);
    }

    /**
     * Parses the archive response, which is a single object for one point and an array for many.
     *
     * @param json the raw response body
     * @return the parsed responses
     */
    private List<OpenMeteoForecastResponse> parseArrayResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            rejectErrorPayload(root);
            List<OpenMeteoForecastResponse> results = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    rejectErrorPayload(node);
                    results.add(objectMapper.treeToValue(node, OpenMeteoForecastResponse.class));
                }
            } else {
                results.add(objectMapper.treeToValue(root, OpenMeteoForecastResponse.class));
            }
            return results;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Open-Meteo archive response", e);
        }
    }

    /**
     * Throws if the node is Open-Meteo's error envelope rather than a weather response.
     *
     * <p><strong>Open-Meteo reports a rate limit as HTTP 200</strong> with the body
     * {@code {"error": true, "reason": "Hourly API request limit exceeded."}}. Nothing about that
     * is exceptional to the HTTP client, and it deserialises happily into an
     * {@link OpenMeteoForecastResponse} whose {@code hourly} block is simply null — which reads
     * downstream as "the archive has no data for this point", not "we were throttled".
     *
     * <p>Left undetected that is the worst possible failure: cloud verification writes a row with
     * null observations, and because candidate selection is an anti-join, that row is treated as
     * verified forever and never revisited. A throttled backfill would silently blank its entire
     * remaining backlog with no error, no warning, and a clean-looking finish.
     *
     * @param node the parsed response node
     */
    private void rejectErrorPayload(JsonNode node) {
        if (node != null && node.path("error").asBoolean(false)) {
            String reason = node.path("reason").asText("unknown");
            throw new IllegalStateException("Open-Meteo archive returned an error: " + reason);
        }
    }

    /**
     * Sleeps without propagating interruption as a checked failure.
     *
     * @param millis milliseconds to sleep
     */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
