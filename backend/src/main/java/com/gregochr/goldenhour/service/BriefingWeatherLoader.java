package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.util.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches Open-Meteo weather for a briefing cycle: the per-location forecast batch and the
 * solar-horizon cloud batch, both deduplicated by Open-Meteo grid cell.
 *
 * <p>This is the vendor-facing half of what used to live on {@link BriefingService}: batching
 * the calls, persisting grid coordinates discovered in the response, and recording API-call
 * metrics. {@code BriefingService} still decides which dates are briefed and which locations are
 * candidates — this class only answers "fetch the weather for these locations". See
 * {@code docs/engineering} (or the PR that introduced this split) for why: an Open-Meteo batching
 * change has no business reopening the class that owns caching, refresh orchestration and
 * briefing assembly.
 */
@Service
public class BriefingWeatherLoader {

    private static final Logger LOG = LoggerFactory.getLogger(BriefingWeatherLoader.class);

    /** Horizon offset distance in metres — geometric horizon for low cloud at ~1 km altitude. */
    private static final double HORIZON_OFFSET_METRES = 113_000.0;

    /** Fallback sunrise bearing (due east), used only when the solar azimuth cannot be computed. */
    private static final double FALLBACK_SUNRISE_BEARING = 90.0;

    /** Fallback sunset bearing (due west), used only when the solar azimuth cannot be computed. */
    private static final double FALLBACK_SUNSET_BEARING = 270.0;

    private final OpenMeteoClient openMeteoClient;
    private final LocationRepository locationRepository;
    private final JobRunService jobRunService;
    private final ServedBriefingEpoch servedEpoch;
    private final SolarService solarService;

    /**
     * Constructs a {@code BriefingWeatherLoader}.
     *
     * @param openMeteoClient    resilient Open-Meteo API client
     * @param locationRepository repository for persisting grid coordinates discovered in responses
     * @param jobRunService      service for job run and API-call metrics tracking
     * @param servedEpoch        bumped after each grid-cell write
     * @param solarService       supplies the real sunrise and sunset azimuths for the horizon points
     */
    public BriefingWeatherLoader(OpenMeteoClient openMeteoClient,
            LocationRepository locationRepository, JobRunService jobRunService,
            ServedBriefingEpoch servedEpoch, SolarService solarService) {
        this.openMeteoClient = openMeteoClient;
        this.locationRepository = locationRepository;
        this.jobRunService = jobRunService;
        this.servedEpoch = servedEpoch;
        this.solarService = solarService;
    }

    /**
     * Fetches Open-Meteo forecast data for all locations sequentially, deduplicating by grid cell.
     *
     * <p>Open-Meteo snaps coordinates to the nearest ~2 km grid point, so locations sharing
     * a grid cell get identical weather data. This method groups locations by their known grid
     * cell (or treats ungrouped locations individually), fetches once per distinct group, and
     * fans the result out to all members. Grid coordinates discovered from the response are
     * persisted back to the location entity for future deduplication.
     *
     * <p>Sequential fetching lets the {@code @RateLimiter} on
     * {@link OpenMeteoClient#fetchForecast} throttle calls naturally at 8/s with no
     * queuing pressure.
     *
     * @param locations the locations to fetch weather for
     * @param jobRun    the job run for API call tracking
     * @return list of location-weather pairs (forecast may be null on failure)
     */
    List<BriefingSlotBuilder.LocationWeather> fetchWeatherSequential(
            List<LocationEntity> locations, JobRunEntity jobRun) {

        // Group locations by grid cell key — ungrouped locations get a unique key
        Map<String, List<LocationEntity>> groups = new LinkedHashMap<>();
        for (LocationEntity loc : locations) {
            String key = loc.hasGridCell()
                    ? loc.gridCellKey()
                    : "ungrouped-" + loc.getId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(loc);
        }

        LOG.info("Briefing weather fetch: {} locations → {} grid cells",
                locations.size(), groups.size());

        // Collect one representative coordinate per grid cell group
        List<String> groupKeys = new ArrayList<>(groups.keySet());
        List<double[]> coords = new ArrayList<>();
        for (String key : groupKeys) {
            LocationEntity rep = groups.get(key).getFirst();
            coords.add(new double[]{rep.getLat(), rep.getLon()});
        }

        List<BriefingSlotBuilder.LocationWeather> results = new ArrayList<>();
        long startMs = System.currentTimeMillis();
        try {
            List<OpenMeteoForecastResponse> responses =
                    openMeteoClient.fetchForecastBriefingBatch(coords);
            long durationMs = System.currentTimeMillis() - startMs;
            long populated = responses.stream().filter(r -> r != null).count();
            LOG.info("Briefing weather fetch complete: {}/{} forecasts returned ({}ms)",
                    populated, coords.size(), durationMs);
            jobRunService.logApiCall(jobRun.getId(),
                    ServiceName.OPEN_METEO_FORECAST,
                    "GET", "briefing-forecast-batch(" + coords.size() + ")", null,
                    durationMs, 200, null, true, null);

            for (int i = 0; i < groupKeys.size(); i++) {
                List<LocationEntity> group = groups.get(groupKeys.get(i));
                OpenMeteoForecastResponse forecast = responses.get(i);

                if (forecast != null) {
                    captureGridCoordinates(forecast, group);
                }

                for (LocationEntity loc : group) {
                    results.add(new BriefingSlotBuilder.LocationWeather(loc, forecast));
                }
            }
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            LOG.warn("Briefing weather batch fetch failed: {}", e.getMessage());
            jobRunService.logApiCall(jobRun.getId(),
                    ServiceName.OPEN_METEO_FORECAST,
                    "GET", "briefing-forecast-batch(" + coords.size() + ")", null,
                    durationMs, null, null, false, e.getMessage());
            for (LocationEntity loc : locations) {
                results.add(new BriefingSlotBuilder.LocationWeather(loc, null));
            }
        }
        return results;
    }

    /**
     * Captures snapped grid coordinates from the Open-Meteo response and persists them
     * to any location in the group that doesn't yet have grid cell coordinates.
     *
     * @param forecast the Open-Meteo response containing snapped lat/lon
     * @param group    the locations sharing this grid cell
     */
    private void captureGridCoordinates(OpenMeteoForecastResponse forecast,
            List<LocationEntity> group) {
        if (forecast.getLatitude() == null || forecast.getLongitude() == null) {
            return;
        }
        List<LocationEntity> toSave = new ArrayList<>();
        for (LocationEntity loc : group) {
            if (!loc.hasGridCell()) {
                loc.setGridLat(forecast.getLatitude());
                loc.setGridLng(forecast.getLongitude());
                toSave.add(loc);
            }
        }
        if (!toSave.isEmpty()) {
            try {
                // Scoped per location: these entities are detached snapshots from a long-running
                // briefing build and must not rewrite any column but the grid cell.
                for (LocationEntity loc : toSave) {
                    locationRepository.updateGridCell(
                            loc.getId(), forecast.getLatitude(), forecast.getLongitude());
                    servedEpoch.bump();
                }
                LOG.debug("Captured grid cell {},{} for {} location(s)",
                        forecast.getLatitude(), forecast.getLongitude(), toSave.size());
            } catch (Exception e) {
                LOG.warn("Failed to persist grid coordinates: {}", e.getMessage());
            }
        }
    }

    /**
     * Fetches cloud-only data at the solar horizon point for each location+event combination.
     *
     * <p>Per location and event it samples 113 km toward the sun (the real sunrise or sunset
     * azimuth) and 113 km toward the anti-sun point (azimuth + 180°), so four points per
     * location. They are de-duplicated by Open-Meteo grid cell (nearest 0.25°) and fetched in a
     * single batch. With ~50 locations × 4 points → ~200 raw points → typically 80–120 unique
     * grid cells after de-duplication.
     *
     * <p>The azimuth is computed once per location and event on {@code referenceDate} (the first
     * briefing date) and reused for every date in the window: it drifts by under 1° a day, and a
     * per-date point would multiply the fetch. If the azimuth cannot be computed, that event falls
     * back to due east (sunrise) or due west (sunset).
     *
     * @param locations     the colour locations to compute horizon points for
     * @param referenceDate the first briefing date, used to compute the solar azimuths
     * @param jobRun        the job run for API call tracking
     * @return horizon cloud data lookup
     */
    HorizonCloudData fetchHorizonCloud(List<LocationEntity> locations, LocalDate referenceDate,
            JobRunEntity jobRun) {

        // Collect phase: compute horizon points and de-duplicate by grid key
        Map<String, double[]> uniqueCoords = new LinkedHashMap<>();
        Map<Long, Map<TargetType, String>> locationKeys = new HashMap<>();
        Map<Long, Map<TargetType, String>> antiSunKeys = new HashMap<>();

        for (LocationEntity loc : locations) {
            Map<TargetType, String> sunKeys = new HashMap<>();
            Map<TargetType, String> antiKeys = new HashMap<>();
            for (TargetType event : List.of(TargetType.SUNRISE, TargetType.SUNSET)) {
                double sunBearing = sunBearing(loc, event, referenceDate);
                sunKeys.put(event, registerPoint(uniqueCoords, loc, sunBearing));
                antiKeys.put(event, registerPoint(uniqueCoords, loc,
                        GeoUtils.antisolarBearing(sunBearing)));
            }
            locationKeys.put(loc.getId(), sunKeys);
            antiSunKeys.put(loc.getId(), antiKeys);
        }

        // Single batch fetch for all unique horizon grid cells
        List<String> keys = new ArrayList<>(uniqueCoords.keySet());
        List<double[]> coords = keys.stream().map(uniqueCoords::get).toList();

        Map<String, OpenMeteoForecastResponse> responseMap = new HashMap<>();
        long startMs = System.currentTimeMillis();
        try {
            List<OpenMeteoForecastResponse> responses =
                    openMeteoClient.fetchCloudOnlyBatch(coords);
            long durationMs = System.currentTimeMillis() - startMs;
            long populated = responses.stream().filter(r -> r != null).count();
            LOG.info("Horizon cloud fetch: {}/{} grid cells returned ({}ms)",
                    populated, coords.size(), durationMs);
            jobRunService.logApiCall(jobRun.getId(),
                    ServiceName.OPEN_METEO_FORECAST,
                    "GET", "horizon-cloud-batch(" + coords.size() + ")", null,
                    durationMs, 200, null, true, null);
            for (int i = 0; i < keys.size(); i++) {
                responseMap.put(keys.get(i), responses.get(i));
            }
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            LOG.warn("Horizon cloud batch fetch failed — continuing without horizon data: {}",
                    e.getMessage());
            jobRunService.logApiCall(jobRun.getId(),
                    ServiceName.OPEN_METEO_FORECAST,
                    "GET", "horizon-cloud-batch(" + coords.size() + ")", null,
                    durationMs, null, null, false, e.getMessage());
        }

        return new HorizonCloudData(locationKeys, antiSunKeys, responseMap);
    }

    private double sunBearing(LocationEntity loc, TargetType event, LocalDate date) {
        try {
            return event == TargetType.SUNRISE
                    ? solarService.sunriseAzimuthDeg(loc.getLat(), loc.getLon(), date)
                    : solarService.sunsetAzimuthDeg(loc.getLat(), loc.getLon(), date);
        } catch (RuntimeException e) {
            LOG.debug("Solar azimuth unavailable for location {} {} — using fallback bearing: {}",
                    loc.getId(), event, e.getMessage());
            return event == TargetType.SUNRISE ? FALLBACK_SUNRISE_BEARING : FALLBACK_SUNSET_BEARING;
        }
    }

    private static String registerPoint(Map<String, double[]> uniqueCoords, LocationEntity loc,
            double bearing) {
        double[] point = GeoUtils.offsetPoint(
                loc.getLat(), loc.getLon(), bearing, HORIZON_OFFSET_METRES);
        String key = horizonGridKey(point);
        uniqueCoords.putIfAbsent(key, point);
        return key;
    }

    /**
     * Rounds a coordinate to the nearest Open-Meteo grid cell (0.25° resolution).
     *
     * @param point [lat, lon] in decimal degrees
     * @return grid cell key string
     */
    static String horizonGridKey(double[] point) {
        return String.format("%.2f,%.2f",
                Math.round(point[0] * 4) / 4.0,
                Math.round(point[1] * 4) / 4.0);
    }

    /**
     * Lookup container for horizon cloud forecast data, keyed by location ID and event type.
     */
    record HorizonCloudData(Map<Long, Map<TargetType, String>> locationKeys,
            Map<Long, Map<TargetType, String>> antiSunKeys,
            Map<String, OpenMeteoForecastResponse> responseMap) {

        /**
         * Returns the horizon forecast for a given location and event type.
         *
         * @param locationId the location ID
         * @param eventType  SUNRISE or SUNSET
         * @return the horizon forecast response, or null if unavailable
         */
        OpenMeteoForecastResponse getForLocation(Long locationId, TargetType eventType) {
            return lookup(locationKeys, locationId, eventType);
        }

        /**
         * Returns the anti-sun horizon forecast for a given location and event type: the point
         * 113 km directly away from where that event's sun is.
         *
         * @param locationId the location ID
         * @param eventType  SUNRISE or SUNSET
         * @return the anti-sun horizon forecast response, or null if unavailable
         */
        OpenMeteoForecastResponse getAntiSunForLocation(Long locationId, TargetType eventType) {
            return lookup(antiSunKeys, locationId, eventType);
        }

        private OpenMeteoForecastResponse lookup(Map<Long, Map<TargetType, String>> keyMap,
                Long locationId, TargetType eventType) {
            Map<TargetType, String> keys = keyMap.get(locationId);
            if (keys == null) {
                return null;
            }
            String key = keys.get(eventType);
            return key != null ? responseMap.get(key) : null;
        }
    }
}
