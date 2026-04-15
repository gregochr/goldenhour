package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.LightPollutionClient;
import com.gregochr.goldenhour.client.OpenMeteoForecastApi;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.model.LocationEnrichmentResult;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Enriches a lat/lon pair with metadata from external APIs.
 *
 * <p>Called during the admin "Add Location" flow to auto-detect bortle class,
 * sky brightness, elevation, and Open-Meteo grid cell coordinates. Each API call
 * runs in parallel and fails independently — a partial result is returned if any
 * single source is unavailable.
 */
@Service
public class LocationEnrichmentService {

    private static final Logger LOG = LoggerFactory.getLogger(LocationEnrichmentService.class);

    private final LightPollutionClient lightPollutionClient;
    private final AuroraProperties auroraProperties;
    private final RestClient restClient;
    private final OpenMeteoForecastApi openMeteoForecastApi;

    /**
     * Constructs the enrichment service.
     *
     * @param lightPollutionClient client for lightpollutionmap.info bortle/SQM lookups
     * @param auroraProperties     aurora config containing the light pollution API key
     * @param restClient           shared REST client for the Open-Meteo elevation API
     * @param openMeteoForecastApi typed proxy for the Open-Meteo forecast API
     */
    public LocationEnrichmentService(LightPollutionClient lightPollutionClient,
                                     AuroraProperties auroraProperties,
                                     RestClient restClient,
                                     OpenMeteoForecastApi openMeteoForecastApi) {
        this.lightPollutionClient = lightPollutionClient;
        this.auroraProperties = auroraProperties;
        this.restClient = restClient;
        this.openMeteoForecastApi = openMeteoForecastApi;
    }

    /**
     * Enriches a coordinate with metadata from multiple external APIs in parallel.
     *
     * <p>Three calls are made concurrently:
     * <ol>
     *   <li>lightpollutionmap.info — Bortle class and SQM sky brightness</li>
     *   <li>Open-Meteo elevation API — elevation above sea level</li>
     *   <li>Open-Meteo forecast API (minimal) — snapped grid cell coordinates</li>
     * </ol>
     *
     * <p>If any individual call fails, its fields are null in the result rather than
     * failing the entire enrichment.
     *
     * @param latitude  latitude in decimal degrees
     * @param longitude longitude in decimal degrees
     * @return enrichment result with nullable fields for each data source
     */
    public LocationEnrichmentResult enrich(double latitude, double longitude) {
        CompletableFuture<LightPollutionClient.SkyBrightnessResult> bortleFuture =
                CompletableFuture.supplyAsync(() -> fetchBortle(latitude, longitude));

        CompletableFuture<Integer> elevationFuture =
                CompletableFuture.supplyAsync(() -> fetchElevation(latitude, longitude));

        CompletableFuture<double[]> gridFuture =
                CompletableFuture.supplyAsync(() -> fetchGridCell(latitude, longitude));

        CompletableFuture.allOf(bortleFuture, elevationFuture, gridFuture).join();

        LightPollutionClient.SkyBrightnessResult brightness = bortleFuture.join();
        Integer elevation = elevationFuture.join();
        double[] grid = gridFuture.join();

        return new LocationEnrichmentResult(
                brightness != null ? brightness.bortle() : null,
                brightness != null ? brightness.sqm() : null,
                elevation,
                grid != null ? grid[0] : null,
                grid != null ? grid[1] : null
        );
    }

    /**
     * Fetches Bortle class and SQM via the lightpollutionmap.info API.
     *
     * @return sky brightness result, or null if the API key is missing or the call fails
     */
    private LightPollutionClient.SkyBrightnessResult fetchBortle(double latitude, double longitude) {
        try {
            String apiKey = auroraProperties.getLightPollutionApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                LOG.warn("Light pollution API key not configured — skipping bortle enrichment");
                return null;
            }
            return lightPollutionClient.querySkyBrightness(latitude, longitude, apiKey);
        } catch (Exception e) {
            LOG.warn("Bortle enrichment failed for ({}, {}): {}", latitude, longitude, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches elevation from the Open-Meteo elevation API.
     *
     * <p>The API returns {@code {"elevation": [368.0]}} — the first element is extracted
     * and rounded to the nearest integer.
     *
     * @return elevation in metres, or null on failure
     */
    @SuppressWarnings("unchecked")
    private Integer fetchElevation(double latitude, double longitude) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("https://api.open-meteo.com/v1/elevation?latitude={lat}&longitude={lon}",
                            latitude, longitude)
                    .retrieve()
                    .body(Map.class);
            if (response != null && response.containsKey("elevation")) {
                List<Number> elevations = (List<Number>) response.get("elevation");
                if (elevations != null && !elevations.isEmpty()) {
                    return (int) Math.round(elevations.getFirst().doubleValue());
                }
            }
            LOG.warn("Unexpected elevation response for ({}, {})", latitude, longitude);
            return null;
        } catch (RestClientException e) {
            LOG.warn("Elevation API failed for ({}, {}): {}", latitude, longitude, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches the Open-Meteo snapped grid cell by making a minimal forecast request.
     *
     * <p>The API returns snapped {@code latitude} and {@code longitude} fields in every response,
     * representing the centre of the nearest ~2 km grid cell.
     *
     * @return [gridLat, gridLng] array, or null on failure
     */
    private double[] fetchGridCell(double latitude, double longitude) {
        try {
            OpenMeteoForecastResponse response = openMeteoForecastApi.getForecast(
                    latitude, longitude, "temperature_2m", "ms", "UTC");
            if (response != null && response.getLatitude() != null && response.getLongitude() != null) {
                return new double[]{response.getLatitude(), response.getLongitude()};
            }
            LOG.warn("No grid cell in forecast response for ({}, {})", latitude, longitude);
            return null;
        } catch (Exception e) {
            LOG.warn("Grid cell lookup failed for ({}, {}): {}", latitude, longitude, e.getMessage());
            return null;
        }
    }
}
