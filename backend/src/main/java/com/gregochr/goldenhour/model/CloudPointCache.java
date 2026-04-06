package com.gregochr.goldenhour.model;

import java.util.Map;

/**
 * Grid-key-based cache for pre-fetched cloud-only Open-Meteo responses.
 *
 * <p>Cloud sampling points (directional, approach, upwind) are computed upfront for all
 * evaluations in a forecast run, deduplicated by Open-Meteo's ~0.1° grid resolution,
 * and batch-fetched in a single API call. This cache stores the responses for lookup
 * during the triage phase, eliminating per-evaluation API calls.
 */
public class CloudPointCache {

    private static final double GRID_STEP = 0.1;

    private final Map<String, OpenMeteoForecastResponse> cache;

    /**
     * Constructs a cache from a map of grid key to forecast response.
     *
     * @param cache grid key to cloud-only forecast response
     */
    public CloudPointCache(Map<String, OpenMeteoForecastResponse> cache) {
        this.cache = Map.copyOf(cache);
    }

    /**
     * Looks up the forecast response for a coordinate, snapped to grid resolution.
     *
     * @param lat latitude
     * @param lon longitude
     * @return the cached response, or {@code null} if not in cache
     */
    public OpenMeteoForecastResponse get(double lat, double lon) {
        return cache.get(gridKey(lat, lon));
    }

    /**
     * Returns the number of unique grid cells in the cache.
     *
     * @return cache size
     */
    public int size() {
        return cache.size();
    }

    /**
     * Computes a grid-snapped key for deduplication and lookup.
     *
     * @param lat latitude
     * @param lon longitude
     * @return grid key string
     */
    public static String gridKey(double lat, double lon) {
        long snapLat = Math.round(lat / GRID_STEP);
        long snapLon = Math.round(lon / GRID_STEP);
        return snapLat + "_" + snapLon;
    }
}
