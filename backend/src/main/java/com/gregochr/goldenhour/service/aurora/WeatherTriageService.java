package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.service.OpenMeteoClient;
import com.gregochr.goldenhour.util.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Triages aurora candidate locations by cloud cover, separating those worth
 * including in the Claude evaluation from those that should receive an automatic 1-star rating.
 *
 * <p>For each location a northward transect of three sample points (50 km, 100 km, 150 km)
 * is fetched from Open-Meteo. The triage rule is intentionally lenient:
 * <strong>reject only when every hour in the next {@value #TRIAGE_LOOKAHEAD_HOURS} hours
 * exceeds the overcast threshold.</strong> If any single hour is below the threshold,
 * the location passes — a gap in the clouds is all you need for aurora photography.
 *
 * <p>Transect points are deduplicated at 0.1° grid resolution to minimise Open-Meteo calls.
 */
@Service
public class WeatherTriageService {

    private static final Logger LOG = LoggerFactory.getLogger(WeatherTriageService.class);

    /**
     * Cloud cover percentage above which a single hour is considered overcast for aurora.
     * A location passes triage if any hour in the window is below this threshold.
     */
    static final int OVERCAST_THRESHOLD_PERCENT = 75;

    /** Hours ahead to check for any viable aurora window. */
    static final int TRIAGE_LOOKAHEAD_HOURS = 6;

    /** Northward offsets in metres for the three transect sample points. */
    private static final double[] TRANSECT_OFFSETS_M = {50_000.0, 100_000.0, 150_000.0};

    /** Grid resolution for deduplication — Open-Meteo grid is approximately 0.1°. */
    private static final double GRID_STEP = 0.1;

    private final OpenMeteoClient openMeteoClient;

    /**
     * Constructs the triage service.
     *
     * @param openMeteoClient resilient Open-Meteo client for batch cloud fetches
     */
    public WeatherTriageService(OpenMeteoClient openMeteoClient) {
        this.openMeteoClient = openMeteoClient;
    }

    /**
     * Runs weather triage for a list of aurora candidate locations.
     *
     * <p>Fetches hourly cloud cover from Open-Meteo for a northward transect at each location.
     * Separates the list into viable locations (any hour below {@value #OVERCAST_THRESHOLD_PERCENT}%)
     * and rejected locations (completely overcast for all forecast hours).
     *
     * @param candidates locations that have already passed the Bortle filter
     * @return triage result with viable/rejected lists and cloud data per location
     */
    public TriageResult triage(List<LocationEntity> candidates) {
        // Collect unique grid points across all transects
        Map<String, double[]> gridKeyToCoord = new HashMap<>();
        for (LocationEntity loc : candidates) {
            for (double offsetM : TRANSECT_OFFSETS_M) {
                double[] point = GeoUtils.offsetPoint(loc.getLat(), loc.getLon(), 0.0, offsetM);
                String key = gridKey(point[0], point[1]);
                gridKeyToCoord.putIfAbsent(key, point);
            }
        }

        // Batch fetch hourly cloud cover for all unique grid points in a single API call
        List<String> gridKeys = new ArrayList<>(gridKeyToCoord.keySet());
        List<double[]> coords = new ArrayList<>();
        for (String key : gridKeys) {
            coords.add(gridKeyToCoord.get(key));
        }

        Map<String, int[]> gridKeyToCloud = new HashMap<>();
        try {
            List<OpenMeteoForecastResponse> responses = openMeteoClient.fetchCloudOnlyBatch(coords);
            for (int i = 0; i < gridKeys.size(); i++) {
                gridKeyToCloud.put(gridKeys.get(i), extractWindowCloud(responses.get(i)));
            }
            LOG.info("Aurora triage batch fetch: {} grid points in 1 call", coords.size());
        } catch (Exception e) {
            LOG.warn("Aurora triage batch fetch failed, using defaults: {}", e.getMessage());
            for (String key : gridKeys) {
                gridKeyToCloud.put(key, defaultCloud());
            }
        }

        // Build per-location cloud average and triage decision
        List<LocationEntity> viable = new ArrayList<>();
        List<LocationEntity> rejected = new ArrayList<>();
        Map<LocationEntity, Integer> cloudByLocation = new HashMap<>();

        for (LocationEntity loc : candidates) {
            int[] averagedHourly = averageTransect(loc, gridKeyToCloud);
            int avgCloud = averageCloud(averagedHourly);
            cloudByLocation.put(loc, avgCloud);

            boolean hasAnyViableHour = hasViableHour(averagedHourly);
            if (hasAnyViableHour) {
                viable.add(loc);
            } else {
                rejected.add(loc);
                LOG.debug("Triage rejected {} — overcast every hour (avg {}%)", loc.getName(), avgCloud);
            }
        }

        LOG.info("Aurora weather triage: {}/{} locations viable", viable.size(), candidates.size());
        return new TriageResult(viable, rejected, cloudByLocation);
    }

    /**
     * Extracts cloud cover for the triage window from a batch forecast response.
     * Uses the total of low+mid+high cloud layers (capped at 100) as a proxy for total cloud cover.
     *
     * @param forecast the forecast response from the batch call
     * @return array of {@value #TRIAGE_LOOKAHEAD_HOURS}+1 cloud cover values
     */
    private int[] extractWindowCloud(OpenMeteoForecastResponse forecast) {
        if (forecast == null || forecast.getHourly() == null
                || forecast.getHourly().getTime() == null
                || forecast.getHourly().getCloudCoverLow() == null) {
            return defaultCloud();
        }
        List<String> times = forecast.getHourly().getTime();
        List<Integer> low = forecast.getHourly().getCloudCoverLow();
        List<Integer> mid = forecast.getHourly().getCloudCoverMid();
        List<Integer> high = forecast.getHourly().getCloudCoverHigh();

        String currentHour = ZonedDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.HOURS)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        int startIdx = times.indexOf(currentHour);
        if (startIdx < 0) {
            return defaultCloud();
        }
        int[] window = new int[TRIAGE_LOOKAHEAD_HOURS + 1];
        for (int i = 0; i <= TRIAGE_LOOKAHEAD_HOURS; i++) {
            int idx = startIdx + i;
            if (idx < low.size()) {
                int total = safeGet(low, idx) + safeGet(mid, idx) + safeGet(high, idx);
                window[i] = Math.min(total, 100);
            } else {
                window[i] = OVERCAST_THRESHOLD_PERCENT;
            }
        }
        return window;
    }

    private int safeGet(List<Integer> list, int idx) {
        if (list == null || idx >= list.size()) {
            return 0;
        }
        Integer val = list.get(idx);
        return val != null ? val : 0;
    }

    /**
     * Averages cloud values across the three transect points for a location.
     *
     * @param loc             the location
     * @param gridKeyToCloud  map from grid key to hourly cloud array
     * @return per-hour average across the transect
     */
    private int[] averageTransect(LocationEntity loc, Map<String, int[]> gridKeyToCloud) {
        int[] sum = new int[TRIAGE_LOOKAHEAD_HOURS + 1];
        int count = 0;
        for (double offsetM : TRANSECT_OFFSETS_M) {
            double[] point = GeoUtils.offsetPoint(loc.getLat(), loc.getLon(), 0.0, offsetM);
            String key = gridKey(point[0], point[1]);
            int[] hourly = gridKeyToCloud.get(key);
            if (hourly != null) {
                for (int i = 0; i < sum.length; i++) {
                    sum[i] += hourly[i];
                }
                count++;
            }
        }
        if (count == 0) {
            return defaultCloud();
        }
        int[] avg = new int[TRIAGE_LOOKAHEAD_HOURS + 1];
        for (int i = 0; i < avg.length; i++) {
            avg[i] = sum[i] / count;
        }
        return avg;
    }

    /**
     * Returns true if any hour in the window is below the overcast threshold.
     *
     * @param hourlyCloud hourly cloud cover array
     * @return true if any hour is viable for aurora photography
     */
    private boolean hasViableHour(int[] hourlyCloud) {
        for (int cloud : hourlyCloud) {
            if (cloud < OVERCAST_THRESHOLD_PERCENT) {
                return true;
            }
        }
        return false;
    }

    private int averageCloud(int[] hourlyCloud) {
        if (hourlyCloud.length == 0) {
            return OVERCAST_THRESHOLD_PERCENT;
        }
        int sum = 0;
        for (int v : hourlyCloud) {
            sum += v;
        }
        return sum / hourlyCloud.length;
    }

    private int[] defaultCloud() {
        int[] arr = new int[TRIAGE_LOOKAHEAD_HOURS + 1];
        java.util.Arrays.fill(arr, OVERCAST_THRESHOLD_PERCENT);
        return arr;
    }

    private String gridKey(double lat, double lon) {
        long snapLat = Math.round(lat / GRID_STEP);
        long snapLon = Math.round(lon / GRID_STEP);
        return snapLat + "_" + snapLon;
    }

    /**
     * Result of the weather triage pass.
     *
     * @param viable          locations that passed triage and should be sent to Claude
     * @param rejected         locations rejected (completely overcast) — auto-assign 1★
     * @param cloudByLocation  average transect cloud cover per location (0–100)
     */
    public record TriageResult(
            List<LocationEntity> viable,
            List<LocationEntity> rejected,
            Map<LocationEntity, Integer> cloudByLocation) {}

}
