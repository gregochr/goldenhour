package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.util.TimeSlotUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
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
     * Bulk-pre-fetches Open-Meteo weather for every unique coordinate in the candidate
     * set, returning a coord-key → result map (never {@code null}).
     *
     * @param candidates the candidate set
     * @return coord-key → prefetched weather result, empty if the fetch returned nothing
     */
    public Map<String, WeatherExtractionResult> prefetchBatchWeather(
            List<ForecastCandidate> candidates) {
        Map<String, double[]> uniqueCoords = new LinkedHashMap<>();
        for (ForecastCandidate c : candidates) {
            String key = OpenMeteoService.coordKey(c.location().getLat(),
                    c.location().getLon());
            uniqueCoords.putIfAbsent(key,
                    new double[]{c.location().getLat(), c.location().getLon()});
        }
        LOG.info("Forecast batch: weather pre-fetch for {} unique location(s) (from {} tasks)",
                uniqueCoords.size(), candidates.size());
        Map<String, WeatherExtractionResult> result =
                openMeteoService.prefetchWeatherBatchResilient(
                        new ArrayList<>(uniqueCoords.values()));
        return result != null ? result : Map.of();
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

        for (ForecastCandidate c : candidates) {
            double lat = c.location().getLat();
            double lon = c.location().getLon();
            LocalDate date = c.date();
            TargetType targetType = c.targetType();

            int azimuth = targetType == TargetType.SUNRISE
                    ? solarService.sunriseAzimuthDeg(lat, lon, date)
                    : solarService.sunsetAzimuthDeg(lat, lon, date);

            allPoints.addAll(openMeteoService.computeDirectionalCloudPoints(lat, lon, azimuth));

            if (prefetchedWeather != null) {
                String coordKey = OpenMeteoService.coordKey(lat, lon);
                WeatherExtractionResult cached = prefetchedWeather.get(coordKey);
                if (cached != null && cached.forecastResponse() != null
                        && cached.forecastResponse().getHourly() != null) {
                    LocalDateTime eventTime = targetType == TargetType.SUNRISE
                            ? solarService.sunriseUtc(lat, lon, date)
                            : solarService.sunsetUtc(lat, lon, date);
                    OpenMeteoForecastResponse.Hourly h = cached.forecastResponse().getHourly();
                    List<String> times = h.getTime();
                    if (times != null && h.getWindDirection10m() != null
                            && h.getWindSpeed10m() != null) {
                        int idx = TimeSlotUtils.findNearestIndex(times, eventTime);
                        if (idx < h.getWindDirection10m().size()
                                && idx < h.getWindSpeed10m().size()) {
                            Integer windDir = h.getWindDirection10m().get(idx);
                            Double windSpeed = h.getWindSpeed10m().get(idx);
                            if (windDir != null && windSpeed != null) {
                                double[] upwind = openMeteoService.computeUpwindPoint(
                                        lat, lon, windDir, windSpeed, now, eventTime);
                                if (upwind != null) {
                                    allPoints.add(upwind);
                                }
                            }
                        }
                    }
                }
            }
        }

        LOG.info("Forecast batch: cloud point pre-fetch — {} raw points from {} tasks",
                allPoints.size(), candidates.size());
        return openMeteoService.prefetchCloudBatch(allPoints, null);
    }
}
