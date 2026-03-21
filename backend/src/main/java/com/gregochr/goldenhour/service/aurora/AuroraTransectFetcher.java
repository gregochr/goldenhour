package com.gregochr.goldenhour.service.aurora;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.util.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches cloud cover across a northward transect for aurora candidate locations.
 *
 * <p>For each location, three sample points are computed stepping northward
 * (50 km, 100 km, 150 km) and total cloud cover at the current UTC hour is fetched
 * from Open-Meteo. Results are deduplicated by grid cell (0.1° resolution) to avoid
 * redundant API calls for nearby locations.
 *
 * <p>For aurora photography, clear sky is the primary requirement — {@code cloud_cover}
 * (total, 0–100 %) is the correct signal regardless of cloud layer.
 */
@Component
public class AuroraTransectFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(AuroraTransectFetcher.class);

    /** Northward offsets in metres for the three transect sample points. */
    private static final double[] TRANSECT_OFFSETS_M = {50_000.0, 100_000.0, 150_000.0};

    /** Grid resolution for deduplication — Open-Meteo grid is approximately 0.1°. */
    private static final double GRID_STEP = 0.1;

    private final RestClient restClient;

    /**
     * Constructs the fetcher with the shared {@link RestClient}.
     *
     * @param restClient shared HTTP client
     */
    public AuroraTransectFetcher(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Computes the average northward cloud cover for each location in the list.
     *
     * <p>Deduplicates Open-Meteo calls: if two locations have transect points that fall
     * in the same 0.1° grid cell, the cloud value is fetched once and reused.
     *
     * @param locations candidate aurora locations
     * @return map from location name to average cloud cover (0–100), or 50 on fetch failure
     */
    public Map<String, Integer> fetchTransectCloud(List<LocationEntity> locations) {
        // Collect unique grid points across all transects
        Map<String, double[]> gridKeyToCoord = new HashMap<>();
        for (LocationEntity loc : locations) {
            for (double offsetM : TRANSECT_OFFSETS_M) {
                double[] point = GeoUtils.offsetPoint(loc.getLat(), loc.getLon(), 0.0, offsetM);
                String key = gridKey(point[0], point[1]);
                gridKeyToCoord.putIfAbsent(key, point);
            }
        }

        // Fetch cloud cover for each unique grid point
        Map<String, Integer> gridKeyToCloud = new HashMap<>();
        for (Map.Entry<String, double[]> entry : gridKeyToCoord.entrySet()) {
            double[] coord = entry.getValue();
            int cloud = fetchCurrentHourCloud(coord[0], coord[1]);
            gridKeyToCloud.put(entry.getKey(), cloud);
        }

        // Average the three transect points for each location
        Map<String, Integer> result = new HashMap<>();
        for (LocationEntity loc : locations) {
            int total = 0;
            int count = 0;
            for (double offsetM : TRANSECT_OFFSETS_M) {
                double[] point = GeoUtils.offsetPoint(loc.getLat(), loc.getLon(), 0.0, offsetM);
                String key = gridKey(point[0], point[1]);
                Integer cloud = gridKeyToCloud.get(key);
                if (cloud != null) {
                    total += cloud;
                    count++;
                }
            }
            result.put(loc.getName(), count > 0 ? total / count : 50);
        }
        return result;
    }

    /**
     * Fetches the total cloud cover at the current UTC hour for a single coordinate.
     *
     * @param lat latitude
     * @param lon longitude
     * @return cloud cover percentage 0–100, or 50 as a safe fallback on error
     */
    private int fetchCurrentHourCloud(double lat, double lon) {
        try {
            CloudResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("api.open-meteo.com")
                            .path("/v1/forecast")
                            .queryParam("latitude", lat)
                            .queryParam("longitude", lon)
                            .queryParam("hourly", "cloud_cover")
                            .queryParam("wind_speed_unit", "ms")
                            .queryParam("timezone", "UTC")
                            .queryParam("forecast_days", "1")
                            .build())
                    .retrieve()
                    .body(CloudResponse.class);

            if (response == null || response.hourly() == null) {
                return 50;
            }
            return extractCurrentHour(response.hourly());
        } catch (RestClientException e) {
            LOG.warn("Open-Meteo cloud fetch failed for ({}, {}): {}", lat, lon, e.getMessage());
            return 50;
        }
    }

    /**
     * Extracts the cloud cover value for the current UTC hour from the hourly data.
     *
     * @param hourly the hourly data block from the Open-Meteo response
     * @return cloud cover at the current hour, or 50 if not found
     */
    private int extractCurrentHour(HourlyCloudData hourly) {
        if (hourly.time() == null || hourly.cloudCover() == null) {
            return 50;
        }
        String currentHour = ZonedDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.HOURS)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        int idx = hourly.time().indexOf(currentHour);
        if (idx < 0 || idx >= hourly.cloudCover().size()) {
            return 50;
        }
        Integer value = hourly.cloudCover().get(idx);
        return value != null ? value : 50;
    }

    /**
     * Computes a grid-cell key by snapping a coordinate to the nearest 0.1° step.
     *
     * @param lat latitude
     * @param lon longitude
     * @return grid key string like {@code "54.8_-1.5"}
     */
    private String gridKey(double lat, double lon) {
        long snapLat = Math.round(lat / GRID_STEP);
        long snapLon = Math.round(lon / GRID_STEP);
        return snapLat + "_" + snapLon;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CloudResponse(HourlyCloudData hourly) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HourlyCloudData(
            List<String> time,
            @JsonProperty("cloud_cover") List<Integer> cloudCover) {
    }
}
