package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.config.OrsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Client for the OpenRouteService matrix API.
 *
 * <p>Sends a single POST request with one source and N destination coordinates,
 * returning drive durations (in seconds) from the source to each destination.
 *
 * <p>Uses the {@code driving-car} profile. If ORS is not configured the call
 * is not made and an empty list is returned.
 */
@Component
public class OpenRouteServiceClient {

    private static final Logger LOG = LoggerFactory.getLogger(OpenRouteServiceClient.class);
    private static final String MATRIX_URL = "https://api.openrouteservice.org/v2/matrix/driving-car";

    private final RestClient restClient;
    private final OrsProperties properties;

    /**
     * Constructs an {@code OpenRouteServiceClient}.
     *
     * @param restClient the shared RestClient bean
     * @param properties ORS configuration (API key, enabled flag)
     */
    public OpenRouteServiceClient(RestClient restClient, OrsProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
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

        // Build locations array: [source, dest1, dest2, ...] in [lon, lat] order
        List<List<Double>> locations = new ArrayList<>();
        locations.add(List.of(sourceLon, sourceLat));
        for (double[] dest : destinations) {
            locations.add(List.of(dest[1], dest[0])); // dest is [lat, lon] → [lon, lat]
        }

        Map<String, Object> body = Map.of(
                "locations", locations,
                "sources", List.of(0),
                "metrics", List.of("duration"));

        OrsMatrixResponse response = restClient.post()
                .uri(MATRIX_URL)
                .header("Authorization", "Bearer " + properties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(OrsMatrixResponse.class);

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
     * Internal record mapping the ORS matrix response durations field.
     *
     * @param durations 2D array — {@code durations[sourceIndex][destIndex]}
     */
    record OrsMatrixResponse(List<List<Double>> durations) {}
}
