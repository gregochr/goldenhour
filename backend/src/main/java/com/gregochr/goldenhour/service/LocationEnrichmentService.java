package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.LightPollutionClient;
import com.gregochr.goldenhour.client.OpenMeteoForecastApi;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.exception.UpstreamLookupException;
import com.gregochr.goldenhour.model.LocationEnrichmentResult;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.ValleyFloorCandidate;
import com.gregochr.goldenhour.model.ValleyFloorCandidates;
import com.gregochr.goldenhour.model.ValleyFloorCell;
import com.gregochr.goldenhour.util.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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

    private static final String ELEVATION_URL =
            "https://api.open-meteo.com/v1/elevation?latitude={lat}&longitude={lon}";

    /** Compass sectors sampled around the location. */
    private static final int SECTORS = 8;

    /** Width of one sector in degrees. */
    private static final double SECTOR_DEGREES = 360.0 / SECTORS;

    /** Points sampled per sector. */
    private static final int POINTS_PER_SECTOR = 12;

    /** Ring distances in metres, cycled through across each sector. */
    private static final double[] RING_METRES = {1000, 2000, 3000, 4000, 5000};

    /** Sample points per request: the location itself plus every sector point (the API allows 100). */
    static final int VALLEY_SAMPLE_COUNT = 1 + SECTORS * POINTS_PER_SECTOR;

    /** A candidate must sit at least this far below the sampled location height, in metres. */
    private static final double MIN_DROP_METRES = 30.0;

    /** Largest latitude magnitude in degrees. */
    private static final double MAX_ABS_LAT = 90.0;

    /** Largest longitude magnitude in degrees. */
    private static final double MAX_ABS_LON = 180.0;

    /** Inclusive range of a surface height the cell lookup accepts, in metres. */
    private static final double MIN_ELEVATION_M = -50.0;

    private static final double MAX_ELEVATION_M = 1500.0;

    private final LightPollutionClient lightPollutionClient;
    private final AuroraProperties auroraProperties;
    private final RestClient restClient;
    private final OpenMeteoForecastApi openMeteoForecastApi;
    private final Executor forecastExecutor;

    /**
     * Constructs the enrichment service.
     *
     * @param lightPollutionClient client for lightpollutionmap.info bortle/SQM lookups
     * @param auroraProperties     aurora config containing the light pollution API key
     * @param restClient           shared REST client for the Open-Meteo elevation API
     * @param openMeteoForecastApi typed proxy for the Open-Meteo forecast API
     * @param forecastExecutor     virtual-thread executor for the parallel enrichment fan-out
     */
    public LocationEnrichmentService(LightPollutionClient lightPollutionClient,
                                     AuroraProperties auroraProperties,
                                     RestClient restClient,
                                     OpenMeteoForecastApi openMeteoForecastApi,
                                     Executor forecastExecutor) {
        this.lightPollutionClient = lightPollutionClient;
        this.auroraProperties = auroraProperties;
        this.restClient = restClient;
        this.openMeteoForecastApi = openMeteoForecastApi;
        this.forecastExecutor = forecastExecutor;
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
     * <p>All three run on {@code forecastExecutor}, never the default. Omitting the executor
     * argument sends the work to {@link java.util.concurrent.ForkJoinPool#commonPool()}, which is
     * sized for CPU-bound work at roughly one thread per core: three concurrent blocking HTTP
     * calls per enrichment then contend there with every other common-pool user in the JVM.
     * A virtual-thread executor is the right home for calls that spend their life waiting on a
     * socket.
     *
     * @param latitude  latitude in decimal degrees
     * @param longitude longitude in decimal degrees
     * @return enrichment result with nullable fields for each data source
     */
    public LocationEnrichmentResult enrich(double latitude, double longitude) {
        CompletableFuture<LightPollutionClient.SkyBrightnessResult> bortleFuture =
                CompletableFuture.supplyAsync(() -> fetchBortle(latitude, longitude), forecastExecutor);

        CompletableFuture<Integer> elevationFuture =
                CompletableFuture.supplyAsync(() -> fetchElevation(latitude, longitude), forecastExecutor);

        CompletableFuture<double[]> gridFuture =
                CompletableFuture.supplyAsync(() -> fetchGridCell(latitude, longitude), forecastExecutor);

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
                    .uri(ELEVATION_URL, latitude, longitude)
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
     * <p>Public, and deliberately separate from {@link #enrich(double, double)}, because
     * {@code LocationService.update} needs the grid cell and nothing else. Routing that through
     * {@code enrich} would additionally call lightpollutionmap.info — a keyed, quota-bearing
     * third-party API — and the elevation endpoint, on every coordinate edit, to discard both
     * results. It would also overwrite a hand-curated bortle class with an automated reading.
     *
     * @param latitude  latitude in decimal degrees
     * @param longitude longitude in decimal degrees
     * @return [gridLat, gridLng] array, or null on failure
     */
    public double[] fetchGridCell(double latitude, double longitude) {
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

    /**
     * Suggests valley-floor points below a location.
     *
     * <p>One call to Open-Meteo's elevation API with {@value #VALLEY_SAMPLE_COUNT} points: the location
     * itself, then 8 compass sectors of 12 points each (rings at 1, 2, 3, 4 and 5 km, spread across the
     * sector's 45 degrees, built with {@link GeoUtils#offsetPoint} so longitude scales with latitude). The
     * lowest point of each sector is kept only when at least 30 m below the sampled location height; note
     * the comparison is against the DEM sample at the location, not the stored (often OS-corrected) height.
     * Each call counts as about 97 locations against Open-Meteo's quota.
     *
     * @param lat latitude in decimal degrees
     * @param lon longitude in decimal degrees
     * @return the site's DEM height and the qualifying candidates, lowest first (empty when none qualify)
     * @throws IllegalArgumentException if the coordinates are out of range
     * @throws UpstreamLookupException  if the call fails or returns an unusable list, so "none" and
     *                                  "failed" stay distinguishable
     */
    public ValleyFloorCandidates valleyFloorCandidates(double lat, double lon) {
        requireCoordinates(lat, lon);
        List<double[]> points = new ArrayList<>(VALLEY_SAMPLE_COUNT);
        points.add(new double[]{lat, lon});
        for (int sector = 0; sector < SECTORS; sector++) {
            double centre = sector * SECTOR_DEGREES;
            for (int k = 0; k < POINTS_PER_SECTOR; k++) {
                double bearing = centre - SECTOR_DEGREES / 2 + SECTOR_DEGREES * (k + 0.5) / POINTS_PER_SECTOR;
                points.add(GeoUtils.offsetPoint(lat, lon, bearing, RING_METRES[k % RING_METRES.length]));
            }
        }
        List<Double> heights = fetchElevations(points);
        double site = heights.getFirst();
        List<ValleyFloorCandidate> candidates = new ArrayList<>();
        for (int sector = 0; sector < SECTORS; sector++) {
            int lowest = -1;
            for (int k = 0; k < POINTS_PER_SECTOR; k++) {
                int index = 1 + sector * POINTS_PER_SECTOR + k;
                if (lowest < 0 || heights.get(index) < heights.get(lowest)) {
                    lowest = index;
                }
            }
            if (site - heights.get(lowest) >= MIN_DROP_METRES) {
                double[] point = points.get(lowest);
                candidates.add(new ValleyFloorCandidate(
                        (int) Math.round(heights.get(lowest)), point[0], point[1],
                        GeoUtils.distanceKm(lat, lon, point[0], point[1]),
                        GeoUtils.compassPoint(GeoUtils.initialBearing(lat, lon, point[0], point[1]))));
            }
        }
        candidates.sort(Comparator.comparingInt(ValleyFloorCandidate::metres));
        return new ValleyFloorCandidates((int) Math.round(site), candidates);
    }

    /**
     * Shows which Open-Meteo model cell a valley-floor point resolves to when requested at the floor's own
     * height. Read-only and never stored.
     *
     * @param lat       the floor point's latitude
     * @param lon       the floor point's longitude
     * @param elevation the floor height in metres
     * @return the snapped cell and the surface height the response refers to
     * @throws IllegalArgumentException if an input is out of range
     * @throws UpstreamLookupException  if the request fails or the response lacks a cell
     */
    public ValleyFloorCell valleyFloorCell(double lat, double lon, double elevation) {
        requireCoordinates(lat, lon);
        if (Double.isNaN(elevation) || elevation < MIN_ELEVATION_M || elevation > MAX_ELEVATION_M) {
            throw new IllegalArgumentException("Elevation must be between -50 and 1500 metres");
        }
        OpenMeteoForecastResponse response;
        try {
            response = openMeteoForecastApi.getForecastAtElevation(
                    lat, lon, "temperature_2m", "ms", "UTC", elevation);
        } catch (Exception e) {
            LOG.warn("Valley-floor cell lookup failed for ({}, {}): {}", lat, lon, e.getMessage());
            throw new UpstreamLookupException("Could not look up the model cell; try again shortly.");
        }
        if (response == null || response.getLatitude() == null || response.getLongitude() == null
                || response.getElevation() == null) {
            throw new UpstreamLookupException("The forecast service returned no model cell for that point.");
        }
        return new ValleyFloorCell(response.getLatitude(), response.getLongitude(), response.getElevation(),
                GeoUtils.distanceKm(lat, lon, response.getLatitude(), response.getLongitude()));
    }

    private static void requireCoordinates(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > MAX_ABS_LAT || Math.abs(lon) > MAX_ABS_LON) {
            throw new IllegalArgumentException(
                    "Latitude must be between -90 and 90 and longitude between -180 and 180");
        }
    }

    /**
     * One batched Open-Meteo elevation call for many points.
     *
     * @return one height per point, in order
     * @throws UpstreamLookupException on any failure, a wrong-sized list, or a null entry
     */
    @SuppressWarnings("unchecked")
    private List<Double> fetchElevations(List<double[]> points) {
        String lats = joinCoordinates(points, 0);
        String lons = joinCoordinates(points, 1);
        try {
            Map<String, Object> response = restClient.get()
                    .uri(ELEVATION_URL, lats, lons)
                    .retrieve()
                    .body(Map.class);
            List<Number> raw = response == null ? null : (List<Number>) response.get("elevation");
            if (raw == null || raw.size() != points.size() || raw.contains(null)) {
                throw new UpstreamLookupException("The elevation service returned an unusable answer.");
            }
            return raw.stream().map(Number::doubleValue).toList();
        } catch (UpstreamLookupException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("Valley-floor elevation lookup failed: {}", e.getMessage());
            throw new UpstreamLookupException("Could not look up the surrounding heights; try again shortly.");
        }
    }

    private static String joinCoordinates(List<double[]> points, int axis) {
        StringBuilder sb = new StringBuilder();
        for (double[] p : points) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.5f", p[axis]));
        }
        return sb.toString();
    }
}
