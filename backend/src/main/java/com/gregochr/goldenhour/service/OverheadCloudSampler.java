package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Samples total-column cloud cover <b>directly overhead</b> at each location (its own grid cell) for
 * the requested hours.
 *
 * <p>Counterpart to {@link NorthwardTransectSampler}, which samples the northern-horizon transect
 * because aurora and NLC sit low on the poleward horizon. A meteor shower is a whole-sky phenomenon —
 * meteors streak across the entire sky and the radiant is only their apparent origin — so what matters
 * is whether the sky <em>above the observer</em> is clear, which is what this samples. The low/mid/high
 * layers reduce to one figure via {@link NorthwardTransectSampler.LayerCombiner#SUM_CAPPED} (sum capped
 * at 100 — a proxy for total column cloud).
 *
 * <p>Fetches in a single deduped cloud-only {@link OpenMeteoClient#fetchCloudOnlyBatch batch}. Failure
 * is lenient and never throws: a null/failed response or a missing hour yields the
 * {@value #DEFAULT_OVERCAST_PERCENT}% overcast default, so a fetch problem quietly suppresses a count
 * rather than fabricating clear skies.
 */
@Component
public class OverheadCloudSampler {

    private static final Logger LOG = LoggerFactory.getLogger(OverheadCloudSampler.class);

    /** Overcast default (%) used for any sample that cannot be fetched or found. */
    public static final int DEFAULT_OVERCAST_PERCENT = 75;

    /** Grid resolution for deduplication — the Open-Meteo grid is approximately 0.1°. */
    private static final double GRID_STEP = 0.1;

    private final OpenMeteoClient openMeteoClient;

    /**
     * Constructs an {@code OverheadCloudSampler}.
     *
     * @param openMeteoClient resilient Open-Meteo client for batch cloud-only fetches
     */
    public OverheadCloudSampler(OpenMeteoClient openMeteoClient) {
        this.openMeteoClient = openMeteoClient;
    }

    /**
     * Samples overhead total-column cloud for each location at each requested hour.
     *
     * @param locations locations to sample (each keyed into the result by identity)
     * @param hourKeys  UTC hour keys in {@code yyyy-MM-dd'T'HH:mm} form; the result array is aligned
     *                  to this order
     * @return map from location to an array of total-column cloud (0–100), one entry per
     *         {@code hourKey}; empty when there are no locations or no hours (no API call is made)
     */
    public Map<LocationEntity, int[]> sample(List<LocationEntity> locations, List<String> hourKeys) {
        Map<LocationEntity, int[]> result = new HashMap<>();
        if (locations.isEmpty() || hourKeys.isEmpty()) {
            return result;
        }

        // Collect the unique grid cells across every location (many dark-sky sites share a cell).
        Map<String, double[]> gridKeyToCoord = new LinkedHashMap<>();
        Map<LocationEntity, String> locationToGridKey = new HashMap<>();
        for (LocationEntity loc : locations) {
            String key = gridKey(loc.getLat(), loc.getLon());
            gridKeyToCoord.putIfAbsent(key, new double[]{loc.getLat(), loc.getLon()});
            locationToGridKey.put(loc, key);
        }

        Map<String, int[]> gridKeyToCloud = fetchGridCloud(gridKeyToCoord, hourKeys);

        for (LocationEntity loc : locations) {
            int[] cloud = gridKeyToCloud.get(locationToGridKey.get(loc));
            result.put(loc, cloud != null ? cloud : defaults(hourKeys.size()));
        }
        return result;
    }

    private Map<String, int[]> fetchGridCloud(Map<String, double[]> gridKeyToCoord,
            List<String> hourKeys) {
        List<String> gridKeys = new ArrayList<>(gridKeyToCoord.keySet());
        List<double[]> coords = new ArrayList<>();
        for (String key : gridKeys) {
            coords.add(gridKeyToCoord.get(key));
        }

        Map<String, int[]> gridKeyToCloud = new HashMap<>();
        try {
            List<OpenMeteoForecastResponse> responses = openMeteoClient.fetchCloudOnlyBatch(coords);
            int nullCount = 0;
            for (int i = 0; i < gridKeys.size(); i++) {
                OpenMeteoForecastResponse r = i < responses.size() ? responses.get(i) : null;
                if (r == null) {
                    nullCount++;
                }
                gridKeyToCloud.put(gridKeys.get(i), extractAt(r, hourKeys));
            }
            LOG.info("Overhead cloud fetch: {} grid cells, {} failed (using defaults)",
                    coords.size(), nullCount);
        } catch (Exception e) {
            LOG.warn("Overhead cloud fetch failed, using defaults: {}", e.getMessage());
            for (String key : gridKeys) {
                gridKeyToCloud.put(key, defaults(hourKeys.size()));
            }
        }
        return gridKeyToCloud;
    }

    private int[] extractAt(OpenMeteoForecastResponse forecast, List<String> hourKeys) {
        if (forecast == null || forecast.getHourly() == null
                || forecast.getHourly().getTime() == null
                || forecast.getHourly().getCloudCoverLow() == null) {
            return defaults(hourKeys.size());
        }
        OpenMeteoForecastResponse.Hourly hourly = forecast.getHourly();
        List<String> times = hourly.getTime();
        int[] out = new int[hourKeys.size()];
        for (int i = 0; i < hourKeys.size(); i++) {
            int idx = times.indexOf(hourKeys.get(i));
            if (idx < 0) {
                out[i] = DEFAULT_OVERCAST_PERCENT;
                continue;
            }
            int low = safeGet(hourly.getCloudCoverLow(), idx);
            int mid = safeGet(hourly.getCloudCoverMid(), idx);
            int high = safeGet(hourly.getCloudCoverHigh(), idx);
            out[i] = NorthwardTransectSampler.LayerCombiner.SUM_CAPPED.combine(low, mid, high);
        }
        return out;
    }

    private int[] defaults(int hours) {
        int[] arr = new int[hours];
        Arrays.fill(arr, DEFAULT_OVERCAST_PERCENT);
        return arr;
    }

    private static int safeGet(List<Integer> list, int idx) {
        if (list == null || idx >= list.size()) {
            return 0;
        }
        Integer val = list.get(idx);
        return val != null ? val : 0;
    }

    private String gridKey(double lat, double lon) {
        long snapLat = Math.round(lat / GRID_STEP);
        long snapLon = Math.round(lon / GRID_STEP);
        return snapLat + "_" + snapLon;
    }
}
