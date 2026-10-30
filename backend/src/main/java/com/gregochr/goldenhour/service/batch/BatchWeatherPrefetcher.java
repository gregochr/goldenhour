package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.InversionProfile;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.service.DirectionalSamplingGeometry;
import com.gregochr.goldenhour.service.OpenMeteoResponseParser;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.SolarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bulk weather and cloud-point pre-fetch for a batch's candidate set.
 *
 * <p>Instance-scoped seam extracted from {@code ForecastTaskCollector}. Pure
 * data-assembly over a {@code List<ForecastCandidate>}: it counts the unique
 * coordinates, pre-fetches Open-Meteo weather for them in one resilient batch,
 * and assembles the directional + upwind cloud sample points a subsequent
 * cloud pre-fetch consumes. Logs under the {@link ForecastTaskCollector}
 * category so pre-fetch diagnostics stay grouped with the collector's own output.
 */
public final class BatchWeatherPrefetcher {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastTaskCollector.class);

    private final OpenMeteoService openMeteoService;
    private final SolarService solarService;
    private final Clock clock;

    /**
     * Constructs a {@code BatchWeatherPrefetcher}.
     *
     * @param openMeteoService bulk weather + cloud-point pre-fetch
     * @param solarService     solar azimuth and event time helpers
     * @param clock            UTC clock supplying "now" for upwind sampling
     */
    public BatchWeatherPrefetcher(OpenMeteoService openMeteoService, SolarService solarService,
            Clock clock) {
        this.openMeteoService = openMeteoService;
        this.solarService = solarService;
        this.clock = clock;
    }

    /**
     * Counts the number of unique locations (by coordinate key) in the candidate set.
     *
     * @param candidates the candidate set
     * @return the number of distinct coordinate keys
     */
    public int countUniqueLocations(List<ForecastCandidate> candidates) {
        Set<String> seen = new HashSet<>();
        for (ForecastCandidate c : candidates) {
            seen.add(OpenMeteoService.coordKey(c.location().getLat(),
                    c.location().getLon()));
        }
        return seen.size();
    }

    /**
     * Bulk-pre-fetches Open-Meteo weather once per distinct grid cell (plus once per cell-less
     * coordinate) in the candidate set, returning a coord-key → result map (never {@code null}).
     *
     * <p>Open-Meteo snaps every coordinate to its ~2 km grid point, so locations sharing a stored
     * grid cell are fetched once and the result is fanned out to the coordinate key of every
     * location in that cell, which is what {@link #prefetchBatchCloudPoints} and the downstream
     * lookups key on. A location without a known grid cell keeps its own fetch.
     *
     * <p><strong>Declared input change:</strong> Open-Meteo downscales temperature by a point's
     * elevation, so two coordinates in one cell could previously differ slightly. This adopts the
     * same one-fetch-per-cell assumption {@code BriefingWeatherLoader} already makes.
     *
     * @param candidates the candidate set
     * @return coord-key → prefetched weather result, empty if the fetch returned nothing
     */
    public Map<String, WeatherExtractionResult> prefetchBatchWeather(
            List<ForecastCandidate> candidates) {
        // group key -> representative coordinates / representative coord key / every coord key in group
        Map<String, double[]> fetchCoords = new LinkedHashMap<>();
        Map<String, String> representativeKey = new LinkedHashMap<>();
        Map<String, Set<String>> groupCoordKeys = new LinkedHashMap<>();
        for (ForecastCandidate c : candidates) {
            LocationEntity loc = c.location();
            String coordKey = OpenMeteoService.coordKey(loc.getLat(), loc.getLon());
            String groupKey = loc.hasGridCell() ? "cell:" + loc.gridCellKey() : "coord:" + coordKey;
            if (fetchCoords.putIfAbsent(groupKey, new double[]{loc.getLat(), loc.getLon()}) == null) {
                representativeKey.put(groupKey, coordKey);
            }
            groupCoordKeys.computeIfAbsent(groupKey, k -> new LinkedHashSet<>()).add(coordKey);
        }
        LOG.info("Forecast batch: weather pre-fetch for {} distinct grid cell(s)/location(s) (from {} tasks)",
                fetchCoords.size(), candidates.size());
        Map<String, WeatherExtractionResult> fetched =
                openMeteoService.prefetchWeatherBatchResilient(new ArrayList<>(fetchCoords.values()));
        if (fetched == null || fetched.isEmpty()) {
            return Map.of();
        }
        Map<String, WeatherExtractionResult> result = new LinkedHashMap<>(fetched);
        for (Map.Entry<String, Set<String>> group : groupCoordKeys.entrySet()) {
            WeatherExtractionResult shared = fetched.get(representativeKey.get(group.getKey()));
            if (shared == null) {
                continue;
            }
            for (String coordKey : group.getValue()) {
                result.putIfAbsent(coordKey, shared);
            }
        }
        return result;
    }

    /**
     * Pre-fetches the v2 inversion valley profile for the candidate set's eligible locations (inversion-v2-plan
     * Phase 1b), once per run, after the weather and cloud pre-fetches. Best effort: the result holds only the
     * locations that got a profile and the call never throws, so a failure here can never fail a slot or
     * leave mist unscored.
     *
     * @param candidates the candidate set
     * @return location id to profile; empty when nothing is eligible or the fetch failed
     */
    public Map<Long, InversionProfile> prefetchInversionProfiles(List<ForecastCandidate> candidates) {
        List<LocationEntity> locations = candidates.stream().map(ForecastCandidate::location).toList();
        Map<Long, InversionProfile> profiles = openMeteoService.prefetchInversionProfiles(locations);
        return profiles == null ? Map.of() : profiles;
    }

    /**
     * Assembles the directional cone and upwind cloud sample points for the candidate
     * set and pre-fetches cloud cover for them in one batch.
     *
     * @param candidates        the candidate set
     * @param prefetchedWeather coord-key → prefetched weather (supplies wind for upwind sampling)
     * @return the pre-fetched cloud point cache
     */
    public CloudPointCache prefetchBatchCloudPoints(List<ForecastCandidate> candidates,
            Map<String, WeatherExtractionResult> prefetchedWeather) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<double[]> allPoints = new ArrayList<>();
        List<double[]> farPoints = new ArrayList<>();

        for (ForecastCandidate c : candidates) {
            double lat = c.location().getLat();
            double lon = c.location().getLon();
            LocalDate date = c.date();
            TargetType targetType = c.targetType();

            int azimuth = targetType == TargetType.SUNRISE
                    ? solarService.sunriseAzimuthDeg(lat, lon, date)
                    : solarService.sunsetAzimuthDeg(lat, lon, date);

            List<double[]> directionalPoints =
                    openMeteoService.computeDirectionalCloudPoints(lat, lon, azimuth);
            allPoints.addAll(directionalPoints);
            // The real geometry always returns five points (DirectionalSamplingGeometryTest pins it);
            // the guard only tolerates mocked short lists.
            if (directionalPoints.size() > DirectionalSamplingGeometry.FAR_SOLAR_POINT_INDEX) {
                farPoints.add(directionalPoints.get(DirectionalSamplingGeometry.FAR_SOLAR_POINT_INDEX));
            }

            if (prefetchedWeather != null) {
                String coordKey = OpenMeteoService.coordKey(lat, lon);
                WeatherExtractionResult cached = prefetchedWeather.get(coordKey);
                if (cached != null && cached.forecastResponse() != null
                        && cached.forecastResponse().getHourly() != null) {
                    LocalDateTime eventTime = targetType == TargetType.SUNRISE
                            ? solarService.sunriseUtc(lat, lon, date)
                            : solarService.sunsetUtc(lat, lon, date);
                    OpenMeteoForecastResponse.Hourly h = cached.forecastResponse().getHourly();
                    // Must resolve the steering wind exactly as CloudPointCacheReader will, or the
                    // prefetched coordinate and the looked-up one diverge and the upwind sample
                    // silently vanishes. See OpenMeteoResponseParser.resolveEventWind.
                    OpenMeteoResponseParser.EventWind wind =
                            OpenMeteoResponseParser.resolveEventWind(h, eventTime, targetType);
                    if (wind != null) {
                        double[] upwind = openMeteoService.computeUpwindPoint(
                                lat, lon, wind.windFromDeg(), wind.windSpeedMs(),
                                now, eventTime);
                        if (upwind != null) {
                            allPoints.add(upwind);
                        }
                    }
                }
            }
        }

        LOG.info("Forecast batch: cloud point pre-fetch — {} raw points from {} tasks",
                allPoints.size(), candidates.size());
        return openMeteoService.prefetchCloudBatch(allPoints, farPoints, null);
    }
}
