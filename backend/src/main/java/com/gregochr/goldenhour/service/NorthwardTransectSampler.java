package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.util.GeoUtils;
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
 * Samples cloud cover along a three-point transect due <b>north</b> of each location.
 *
 * <p>Both aurora and noctilucent clouds appear low on the <em>poleward</em> horizon, so what
 * actually blocks the view is cloud sitting between the observer and the northern horizon — not
 * cloud directly overhead. This sampler places three points at {@code 50/100/150 km} due north
 * (bearing {@code 0°}), deduplicates them onto the ~0.1° Open-Meteo grid across <em>all</em> input
 * locations, fetches cloud in a single {@link OpenMeteoClient#fetchCloudOnlyBatch batch} call, and
 * returns each location's transect-averaged combined cloud at the requested hours.
 *
 * <p>Extracted from {@code WeatherTriageService} (which pioneered the aurora transect) so the NLC
 * clarity scan can reuse the exact same northward sampling instead of a point-cloud proxy. Callers
 * supply the hours they care about (a look-ahead window for aurora, a single deep-night hour for
 * NLC) and a {@link LayerCombiner} for how the low/mid/high layers reduce to one figure.
 *
 * <p>Failure is lenient and never throws: a null/failed grid response, a missing hour, or a batch
 * exception yields the {@value CloudScoringRules#OVERCAST_PERCENT}% overcast default for the
 * affected sample, so a fetch problem quietly suppresses a topic rather than fabricating clear skies.
 */
@Component
public class NorthwardTransectSampler {

    private static final Logger LOG = LoggerFactory.getLogger(NorthwardTransectSampler.class);

    /** Northward offsets in metres for the three transect sample points. */
    private static final double[] TRANSECT_OFFSETS_M = {50_000.0, 100_000.0, 150_000.0};

    /** Bearing for the transect — due north (the poleward horizon). */
    private static final double NORTH_BEARING = 0.0;

    /** Grid resolution for deduplication — the Open-Meteo grid is approximately 0.1°. */
    private static final double GRID_STEP = 0.1;

    private final OpenMeteoClient openMeteoClient;

    /**
     * Constructs a {@code NorthwardTransectSampler}.
     *
     * @param openMeteoClient resilient Open-Meteo client for batch cloud-only fetches
     */
    public NorthwardTransectSampler(OpenMeteoClient openMeteoClient) {
        this.openMeteoClient = openMeteoClient;
    }

    /**
     * Reduces the low/mid/high cloud layers at one hour to a single 0–100 figure.
     */
    @FunctionalInterface
    public interface LayerCombiner {

        /**
         * Combines the three cloud layers.
         *
         * @param low  low-cloud cover (0–100)
         * @param mid  mid-cloud cover (0–100)
         * @param high high-cloud cover (0–100)
         * @return combined cover (0–100)
         */
        int combine(int low, int mid, int high);

        /**
         * Aurora: the sum of the three layers, capped at 100 — a proxy for total column cloud.
         */
        LayerCombiner SUM_CAPPED = (low, mid, high) -> Math.min(low + mid + high, 100);

        /**
         * NLC: the worst single layer — any layer can hide clouds that sit above the tropopause.
         */
        LayerCombiner MAX_LAYER = (low, mid, high) -> Math.max(low, Math.max(mid, high));
    }

    /**
     * Samples the northward transect for each location and returns the transect-averaged combined
     * cloud at each requested hour.
     *
     * @param locations locations to sample north of (each keyed into the result by identity)
     * @param hourKeys  UTC hour keys in {@code yyyy-MM-dd'T'HH:mm} form; the result array is aligned
     *                  to this order
     * @param combiner  how to reduce the low/mid/high layers to one value
     * @return map from location to an array of combined cloud (0–100), one entry per {@code hourKey};
     *         empty when there are no locations or no hours (no API call is made in that case)
     */
    public Map<LocationEntity, int[]> sample(List<LocationEntity> locations, List<String> hourKeys,
            LayerCombiner combiner) {
        Map<LocationEntity, int[]> result = new HashMap<>();
        if (locations.isEmpty() || hourKeys.isEmpty()) {
            return result;
        }

        // Collect the unique grid points across every location's transect.
        Map<String, double[]> gridKeyToCoord = new LinkedHashMap<>();
        for (LocationEntity loc : locations) {
            for (double offsetM : TRANSECT_OFFSETS_M) {
                double[] point = GeoUtils.offsetPoint(
                        loc.getLat(), loc.getLon(), NORTH_BEARING, offsetM);
                gridKeyToCoord.putIfAbsent(gridKey(point[0], point[1]), point);
            }
        }

        Map<String, int[]> gridKeyToCloud = fetchGridCloud(gridKeyToCoord, hourKeys, combiner);

        for (LocationEntity loc : locations) {
            result.put(loc, averageTransect(loc, hourKeys.size(), gridKeyToCloud));
        }
        return result;
    }

    private Map<String, int[]> fetchGridCloud(Map<String, double[]> gridKeyToCoord,
            List<String> hourKeys, LayerCombiner combiner) {
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
                gridKeyToCloud.put(gridKeys.get(i), extractAt(r, hourKeys, combiner));
            }
            LOG.info("Northward transect fetch: {} grid points, {} failed (using defaults)",
                    coords.size(), nullCount);
        } catch (Exception e) {
            LOG.warn("Northward transect fetch failed, using defaults: {}", e.getMessage());
            for (String key : gridKeys) {
                gridKeyToCloud.put(key, defaults(hourKeys.size()));
            }
        }
        return gridKeyToCloud;
    }

    /**
     * Extracts combined cloud at each requested hour from a single grid point's forecast, using the
     * overcast default for a null forecast or an hour not present in the response.
     */
    private int[] extractAt(OpenMeteoForecastResponse forecast, List<String> hourKeys,
            LayerCombiner combiner) {
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
                out[i] = CloudScoringRules.OVERCAST_PERCENT;
                continue;
            }
            int low = safeGet(hourly.getCloudCoverLow(), idx);
            int mid = safeGet(hourly.getCloudCoverMid(), idx);
            int high = safeGet(hourly.getCloudCoverHigh(), idx);
            out[i] = clampPercent(combiner.combine(low, mid, high));
        }
        return out;
    }

    /**
     * Averages the combined cloud across the (up to three) transect points for a location, per
     * hour. Points with no data are skipped; a location with no data at all defaults to overcast.
     */
    private int[] averageTransect(LocationEntity loc, int hours,
            Map<String, int[]> gridKeyToCloud) {
        int[] sum = new int[hours];
        int count = 0;
        for (double offsetM : TRANSECT_OFFSETS_M) {
            double[] point = GeoUtils.offsetPoint(
                    loc.getLat(), loc.getLon(), NORTH_BEARING, offsetM);
            int[] hourly = gridKeyToCloud.get(gridKey(point[0], point[1]));
            if (hourly != null) {
                for (int i = 0; i < hours; i++) {
                    sum[i] += hourly[i];
                }
                count++;
            }
        }
        if (count == 0) {
            return defaults(hours);
        }
        int[] avg = new int[hours];
        for (int i = 0; i < hours; i++) {
            avg[i] = sum[i] / count;
        }
        return avg;
    }

    private int[] defaults(int hours) {
        int[] arr = new int[hours];
        Arrays.fill(arr, CloudScoringRules.OVERCAST_PERCENT);
        return arr;
    }

    private static int safeGet(List<Integer> list, int idx) {
        if (list == null || idx >= list.size()) {
            return 0;
        }
        Integer val = list.get(idx);
        return val != null ? val : 0;
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(value, 100));
    }

    private String gridKey(double lat, double lon) {
        long snapLat = Math.round(lat / GRID_STEP);
        long snapLon = Math.round(lon / GRID_STEP);
        return snapLat + "_" + snapLon;
    }
}
