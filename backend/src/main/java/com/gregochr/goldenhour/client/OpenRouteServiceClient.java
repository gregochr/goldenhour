package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.config.OrsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client for the OpenRouteService matrix API.
 *
 * <p>Sends a single POST request with one source and N destination coordinates,
 * returning drive durations (in seconds) from the source to each destination.
 *
 * <p>Uses the {@code driving-car} profile. If ORS is not configured the call
 * is not made and an empty list is returned.
 *
 * <p>Every request goes through {@link OrsRateLimiter}, which is the account-wide concurrency
 * limit. It is applied here rather than in the callers so that a new caller cannot bypass it: the
 * per-user refresh and the shared region matrix (plan P7) both queue on the same two permits.
 */
@Component
public class OpenRouteServiceClient {

    private static final Logger LOG = LoggerFactory.getLogger(OpenRouteServiceClient.class);
    private static final String MATRIX_URL = "https://api.openrouteservice.org/v2/matrix/driving-car";
    /** ORS's error code for "no routable point near that coordinate" (reach-scoped-card-plan R1c). */
    private static final int UNROUTABLE_POINT_CODE = 6010;
    private static final JsonMapper ERROR_MAPPER = JsonMapper.builder().build();
    private static final Pattern COORDINATE_INDEX = Pattern.compile("coordinate (\\d+)");

    private final RestClient restClient;
    private final OrsProperties properties;
    private final OrsRateLimiter rateLimiter;

    /**
     * Constructs an {@code OpenRouteServiceClient}.
     *
     * @param restClient  the shared RestClient bean
     * @param properties  ORS configuration (API key, enabled flag)
     * @param rateLimiter the account-wide concurrency limit on ORS calls
     */
    public OpenRouteServiceClient(RestClient restClient, OrsProperties properties,
            OrsRateLimiter rateLimiter) {
        this.restClient = restClient;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Fetches drive durations from a source point to each destination.
     *
     * <p>The source is always index 0 in the ORS locations array. ORS uses
     * {@code [longitude, latitude]} order (GeoJSON standard).
     *
     * @param sourceLat    source latitude
     * @param sourceLon    source longitude
     * @param destinations list of {@code [lat, lon]} pairs for each destination
     * @return list of durations in seconds, one per destination, in the same order;
     *         null entries indicate unreachable destinations
     */
    public List<Double> fetchDurations(double sourceLat, double sourceLon,
            List<double[]> destinations) {
        if (!properties.isConfigured()) {
            LOG.warn("ORS not configured — skipping drive duration fetch");
            return List.of();
        }
        if (destinations.isEmpty()) {
            return List.of();
        }

        Map<String, Object> body = matrixBody(sourceLat, sourceLon, destinations);

        // The permit covers the request alone. The guard clauses above deliberately sit outside it:
        // an unconfigured key or an empty destination list makes no call, so queueing for a permit
        // to discover that would make the limiter slower than the API it protects.
        OrsMatrixResponse response = rateLimiter.withPermit(() -> restClient.post()
                .uri(MATRIX_URL)
                .header("Authorization", "Bearer " + properties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(OrsMatrixResponse.class));

        if (response == null || response.durations() == null || response.durations().isEmpty()) {
            LOG.warn("ORS returned empty or null durations");
            return List.of();
        }

        // durations[0][0] = source→source (0), durations[0][i] = source→destination[i-1]
        List<Double> row = response.durations().get(0);
        if (row.size() < destinations.size() + 1) {
            LOG.warn("ORS durations row size {} < expected {}", row.size(), destinations.size() + 1);
            return List.of();
        }
        return row.subList(1, row.size()); // strip source→source entry
    }

    /**
     * Probes whether a destination can be routed to, as a one-row matrix call that gives up rather than waits
     * (reach-scoped-card-plan R1c). The admin check made while a location is saved: it must never hold the save up
     * behind the nightly sweeps, so it waits at most {@code permitTimeout} for a permit.
     *
     * <p>An empty result means "no answer" and the caller treats it as unavailable: ORS is not configured, no permit
     * came in time, ORS answered with nothing usable, or the row was shorter than asked. ORS saying it cannot snap a
     * point to a road is an answer, and is reported two ways: a destination whose metadata is null (or whose duration
     * is null) in {@link RouteProbe}, or an {@link OrsUnroutablePointException} for the error form (HTTP 404, code
     * 6010) naming the coordinate it could not place. Any other failure propagates for the caller to treat as
     * unavailable.
     *
     * @param sourceLat     source latitude
     * @param sourceLon     source longitude
     * @param destinations  {@code [lat, lon]} pairs to route to
     * @param permitTimeout the longest to wait for a rate-limit permit
     * @return the durations and snap flags, one per destination, or empty when there is no answer
     * @throws OrsUnroutablePointException if ORS refuses the request because a coordinate cannot be snapped
     */
    public Optional<RouteProbe> probe(double sourceLat, double sourceLon, List<double[]> destinations,
            Duration permitTimeout) {
        if (!properties.isConfigured()) {
            LOG.warn("ORS not configured — skipping routability probe");
            return Optional.empty();
        }
        if (destinations.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> body = matrixBody(sourceLat, sourceLon, destinations);
        Optional<OrsMatrixResponse> answer = rateLimiter.tryWithPermit(permitTimeout, () -> {
            try {
                return restClient.post()
                        .uri(MATRIX_URL)
                        .header("Authorization", "Bearer " + properties.getApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(OrsMatrixResponse.class);
            } catch (RestClientResponseException e) {
                throw unroutableOrOriginal(e);
            }
        });
        if (answer.isEmpty() || answer.get().durations() == null || answer.get().durations().isEmpty()) {
            return Optional.empty();
        }
        OrsMatrixResponse response = answer.get();
        List<Double> row = response.durations().get(0);
        if (row == null || row.size() < destinations.size() + 1) {
            LOG.warn("ORS probe row size {} < expected {}", row == null ? 0 : row.size(), destinations.size() + 1);
            return Optional.empty();
        }
        List<Boolean> snapped = new ArrayList<>();
        List<OrsPlace> places = response.destinations();
        for (int i = 0; i < destinations.size(); i++) {
            // destinations[0] is the source; absent metadata altogether means ORS did not say, so rely on durations.
            snapped.add(places == null || places.size() <= i + 1 || places.get(i + 1) != null);
        }
        return Optional.of(new RouteProbe(new ArrayList<>(row.subList(1, row.size())), snapped));
    }

    /**
     * Turns an ORS "no routable point" error into {@link OrsUnroutablePointException}, leaving every other HTTP
     * failure as it was.
     */
    private static RuntimeException unroutableOrOriginal(RestClientResponseException e) {
        if (e.getStatusCode().value() != 404) {
            return e;
        }
        // Parsed, not searched: a message that echoes a coordinate such as -2.6010 must not read as code 6010, and the
        // coordinate index is read only from error.message.
        JsonNode error;
        try {
            error = ERROR_MAPPER.readTree(e.getResponseBodyAsString()).path("error");
        } catch (RuntimeException parseFailure) {
            return e;
        }
        if (error.path("code").asInt(-1) != UNROUTABLE_POINT_CODE) {
            return e;
        }
        Matcher m = COORDINATE_INDEX.matcher(error.path("message").asString(""));
        int index = -1;
        if (m.find()) {
            try {
                index = Integer.parseInt(m.group(1));
            } catch (NumberFormatException overflow) {
                // A digit run too long for an int is not an index we can name: unknown, which the caller allows.
                index = -1;
            }
        }
        return new OrsUnroutablePointException(index);
    }

    /** Builds the one-row matrix request: the source at index 0, then each {@code [lat, lon]} as {@code [lon, lat]}. */
    private static Map<String, Object> matrixBody(double sourceLat, double sourceLon, List<double[]> destinations) {
        // Build locations array: [source, dest1, dest2, ...] in [lon, lat] order
        List<List<Double>> locations = new ArrayList<>();
        locations.add(List.of(sourceLon, sourceLat));
        for (double[] dest : destinations) {
            locations.add(List.of(dest[1], dest[0])); // dest is [lat, lon] → [lon, lat]
        }
        return Map.of(
                "locations", locations,
                "sources", List.of(0),
                "metrics", List.of("duration"));
    }

    /**
     * What a probe learned about each destination.
     *
     * @param durations seconds from the source to each destination, in order; null where ORS found no route
     * @param snapped   whether ORS placed each destination on a road; false where its metadata came back null
     */
    public record RouteProbe(List<Double> durations, List<Boolean> snapped) {
    }

    /**
     * Thrown by {@link #probe} when ORS refuses the request because it cannot place a coordinate on a road.
     */
    public static class OrsUnroutablePointException extends RuntimeException {

        private final int coordinateIndex;

        /**
         * Constructs the exception.
         *
         * @param coordinateIndex index into the request's {@code locations} array (0 is the source), or -1 when
         *                        the error did not say which
         */
        public OrsUnroutablePointException(int coordinateIndex) {
            super("ORS could not route to coordinate " + coordinateIndex);
            this.coordinateIndex = coordinateIndex;
        }

        /**
         * Which coordinate of the request ORS could not place.
         *
         * @return the index into the request's {@code locations} array, or -1 when unknown
         */
        public int getCoordinateIndex() {
            return coordinateIndex;
        }
    }

    /**
     * Internal record mapping one {@code destinations[]} entry of the ORS matrix response (the snap metadata).
     *
     * @param location        the snapped {@code [longitude, latitude]}
     * @param snappedDistance metres from the requested coordinate to the road it was snapped to
     */
    record OrsPlace(List<Double> location, @JsonProperty("snapped_distance") Double snappedDistance) {}

    /**
     * Internal record mapping the ORS matrix response durations field.
     *
     * @param durations    2D array — {@code durations[sourceIndex][destIndex]}
     * @param destinations the snap metadata of each location (index 0 is the source); an unplaceable one is null
     */
    record OrsMatrixResponse(List<List<Double>> durations, List<OrsPlace> destinations) {

        /**
         * A response that carries durations only (the shape {@code fetchDurations} reads).
         *
         * @param durations 2D array — {@code durations[sourceIndex][destIndex]}
         */
        OrsMatrixResponse(List<List<Double>> durations) {
            this(durations, null);
        }
    }
}
