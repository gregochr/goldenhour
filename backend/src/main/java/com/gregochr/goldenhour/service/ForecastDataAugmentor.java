package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TideStatisticalSize;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.CoastalParameters;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.TideData;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.util.TimeSlotUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Augments {@link AtmosphericData} with directional cloud data, tide information,
 * and storm surge calculations.
 *
 * <p>Extracted from {@link ForecastService} to separate the data-enrichment pipeline
 * from forecast orchestration. Used by {@code ForecastService}, {@code ModelTestService},
 * and {@code PromptTestService}.
 */
@Service
public class ForecastDataAugmentor {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastDataAugmentor.class);

    private final OpenMeteoService openMeteoService;
    private final TideService tideService;
    private final LunarPhaseService lunarPhaseService;
    private final WeatherAugmentedTideService weatherAugmentedTideService;
    private final SurgeCalibrationLogger surgeCalibrationLogger;

    /**
     * Constructs a {@code ForecastDataAugmentor} with the services needed for data enrichment.
     *
     * @param openMeteoService             retrieves directional cloud data from Open-Meteo
     * @param tideService                  fetches tide data for coastal locations
     * @param lunarPhaseService            computes lunar tide classification and moon phase
     * @param weatherAugmentedTideService  storm surge calculation service
     * @param surgeCalibrationLogger       structured logging for surge calibration
     */
    public ForecastDataAugmentor(OpenMeteoService openMeteoService, TideService tideService,
            LunarPhaseService lunarPhaseService,
            WeatherAugmentedTideService weatherAugmentedTideService,
            SurgeCalibrationLogger surgeCalibrationLogger) {
        this.openMeteoService = openMeteoService;
        this.tideService = tideService;
        this.lunarPhaseService = lunarPhaseService;
        this.weatherAugmentedTideService = weatherAugmentedTideService;
        this.surgeCalibrationLogger = surgeCalibrationLogger;
    }

    /**
     * Returns a copy of {@code base} with directional cloud data from the solar/antisolar
     * horizon points (113 km offset) and far solar point (226 km) for strip detection.
     * Falls back gracefully to the original data if the fetch fails.
     *
     * @param base         atmospheric data without directional cloud fields
     * @param lat          observer latitude
     * @param lon          observer longitude
     * @param solarAzimuth compass bearing of the sun
     * @param eventTime    UTC time of the solar event
     * @param jobRun       the parent job run for metrics tracking, or {@code null}
     * @return a new {@link AtmosphericData} with directional cloud populated where available
     */
    public AtmosphericData augmentWithDirectionalCloud(AtmosphericData base, double lat,
            double lon, int solarAzimuth, LocalDateTime eventTime, JobRunEntity jobRun) {
        return augmentWithDirectionalCloud(base, lat, lon, solarAzimuth, eventTime, jobRun, null);
    }

    /**
     * Cache-aware overload that reads from a pre-fetched {@link CloudPointCache} if available.
     *
     * @param base         atmospheric data without directional cloud fields
     * @param lat          observer latitude
     * @param lon          observer longitude
     * @param solarAzimuth compass bearing of the sun
     * @param eventTime    UTC time of the solar event
     * @param jobRun       the parent job run for metrics tracking, or {@code null}
     * @param cloudCache   pre-fetched cloud cache, or {@code null} to fetch per-evaluation
     * @return a new {@link AtmosphericData} with directional cloud populated where available
     */
    public AtmosphericData augmentWithDirectionalCloud(AtmosphericData base, double lat,
            double lon, int solarAzimuth, LocalDateTime eventTime, JobRunEntity jobRun,
            CloudPointCache cloudCache) {
        var directional = (cloudCache != null && cloudCache.size() > 0)
                ? openMeteoService.fetchDirectionalCloudDataFromCache(
                        lat, lon, solarAzimuth, eventTime, base.targetType(), cloudCache)
                : openMeteoService.fetchDirectionalCloudData(
                        lat, lon, solarAzimuth, eventTime, base.targetType(), jobRun);
        return directional != null ? base.withDirectionalCloud(directional) : base;
    }

    /**
     * Returns a copy of {@code base} augmented with tide fields for coastal locations.
     *
     * <p>If the location is not coastal (empty tide types), the tide fields in the
     * returned record are {@code null} and the original data is otherwise unchanged.
     *
     * @param base       atmospheric data without tide fields
     * @param locationId the location primary key used for DB tide lookup, or {@code null} if inland
     * @param eventTime  UTC time of the solar event
     * @param tideTypes  tide preferences for this location (empty if inland)
     * @return a new {@link AtmosphericData} with tide fields populated where applicable
     */
    public AtmosphericData augmentWithTideData(AtmosphericData base, Long locationId,
            LocalDateTime eventTime, Set<TideType> tideTypes) {
        boolean isCoastal = locationId != null && tideTypes != null && !tideTypes.isEmpty();
        if (!isCoastal) {
            return base;
        }
        var tideMaybe = tideService.deriveTideData(locationId, eventTime);
        if (tideMaybe.isEmpty()) {
            return base;
        }
        TideData tideData = tideMaybe.get();
        Boolean tideAligned = tideService.calculateTideAligned(tideData, tideTypes);

        // Lunar classification (deterministic, from moon phase + perigee)
        var eventDate = eventTime.toLocalDate();
        LunarTideType lunarTideType = lunarPhaseService.classifyTide(eventDate);
        String lunarPhase = lunarPhaseService.getMoonPhase(eventDate);
        Boolean moonAtPerigee = lunarPhaseService.isMoonAtPerigee(eventDate);

        // Statistical size (empirical, from historical tide data)
        TideStatisticalSize statisticalSize = deriveStatisticalSize(
                locationId, tideData.nextHighTideHeightMetres());

        return base.withTide(new TideSnapshot(
                tideData.tideState(),
                tideData.nextHighTideTime(),
                tideData.nextHighTideHeightMetres(),
                tideData.nextLowTideTime(),
                tideData.nextLowTideHeightMetres(),
                tideAligned,
                tideData.nearestHighTideTime(),
                tideData.nearestLowTideTime(),
                lunarTideType,
                lunarPhase,
                moonAtPerigee,
                statisticalSize));
    }

    /**
     * Derives the statistical tide size by comparing the next high tide height against
     * historical percentiles for the location.
     *
     * @param locationId the location primary key
     * @param highTideHeight the next high tide height, or null
     * @return the statistical classification, or null if data is insufficient
     */
    private TideStatisticalSize deriveStatisticalSize(Long locationId, BigDecimal highTideHeight) {
        if (highTideHeight == null) {
            return null;
        }
        return tideService.getTideStats(locationId)
                .map(stats -> classifyStatisticalSize(highTideHeight, stats))
                .orElse(null);
    }

    /**
     * Classifies tide height against historical thresholds.
     */
    private static TideStatisticalSize classifyStatisticalSize(BigDecimal height, TideStats stats) {
        if (stats.p95HighMetres() != null && height.compareTo(stats.p95HighMetres()) > 0) {
            return TideStatisticalSize.EXTRA_EXTRA_HIGH;
        }
        if (stats.springTideThreshold() != null
                && height.compareTo(stats.springTideThreshold()) > 0) {
            return TideStatisticalSize.EXTRA_HIGH;
        }
        return null;
    }

    /**
     * Returns a copy of {@code base} with cloud approach risk data from the solar horizon
     * temporal trend and upwind spatial sample. Falls back to the original data if the fetch fails.
     *
     * @param base           atmospheric data to augment
     * @param lat            observer latitude
     * @param lon            observer longitude
     * @param solarAzimuth   compass bearing of the sun
     * @param eventTime      UTC time of the solar event
     * @param currentTime    current UTC time
     * @param jobRun         the parent job run for metrics tracking, or {@code null}
     * @return a new {@link AtmosphericData} with cloud approach data populated where available
     */
    public AtmosphericData augmentWithCloudApproach(AtmosphericData base, double lat,
            double lon, int solarAzimuth, LocalDateTime eventTime, LocalDateTime currentTime,
            JobRunEntity jobRun) {
        return augmentWithCloudApproach(base, lat, lon, solarAzimuth, eventTime,
                currentTime, jobRun, null);
    }

    /**
     * Cache-aware overload that reads from a pre-fetched {@link CloudPointCache} if available.
     *
     * @param base           atmospheric data to augment
     * @param lat            observer latitude
     * @param lon            observer longitude
     * @param solarAzimuth   compass bearing of the sun
     * @param eventTime      UTC time of the solar event
     * @param currentTime    current UTC time
     * @param jobRun         the parent job run for metrics tracking, or {@code null}
     * @param cloudCache     pre-fetched cloud cache, or {@code null} to fetch per-evaluation
     * @return a new {@link AtmosphericData} with cloud approach data populated where available
     */
    public AtmosphericData augmentWithCloudApproach(AtmosphericData base, double lat,
            double lon, int solarAzimuth, LocalDateTime eventTime, LocalDateTime currentTime,
            JobRunEntity jobRun, CloudPointCache cloudCache) {
        if (base.weather() == null) {
            return base;
        }
        CloudApproachData approach;
        if (cloudCache != null && cloudCache.size() > 0) {
            approach = openMeteoService.fetchCloudApproachDataFromCache(
                    lat, lon, solarAzimuth, eventTime, currentTime, base.targetType(),
                    base.weather().windDirectionDegrees(),
                    base.weather().windSpeedMs().doubleValue(), cloudCache);
        } else {
            approach = openMeteoService.fetchCloudApproachData(
                    lat, lon, solarAzimuth, eventTime, currentTime, base.targetType(),
                    base.weather().windDirectionDegrees(),
                    base.weather().windSpeedMs().doubleValue(), jobRun);
        }
        return approach != null ? base.withCloudApproach(approach) : base;
    }

    /**
     * Returns a copy of {@code base} with location orientation set based on the location's solar
     * event type preferences. If the location supports both events (or has no preference),
     * orientation is left null and scoring proceeds normally.
     *
     * @param base            atmospheric data to augment
     * @param solarEventTypes the location's solar event type preferences (may be null or empty)
     * @return a new {@link AtmosphericData} with orientation populated, or the original if not applicable
     */
    public AtmosphericData augmentWithLocationOrientation(AtmosphericData base,
            Set<SolarEventType> solarEventTypes) {
        if (solarEventTypes == null || solarEventTypes.isEmpty()
                || solarEventTypes.contains(SolarEventType.ALLDAY)
                || solarEventTypes.size() > 1) {
            return base;
        }
        String orientation = solarEventTypes.contains(SolarEventType.SUNRISE)
                ? "sunrise-optimised" : "sunset-optimised";
        return base.withLocationOrientation(orientation);
    }

    /**
     * Returns a copy of {@code base} with storm surge data for coastal tidal locations.
     *
     * <p>Extracts pressure, wind speed, and wind direction from the raw Open-Meteo hourly
     * forecast at the <strong>time of next high tide</strong> (not at the solar event time),
     * then delegates to {@link WeatherAugmentedTideService} for the surge calculation.
     *
     * <p>Returns the original data unchanged if the location is not coastal, has no tide
     * data, or if any required field is missing.
     *
     * @param base            atmospheric data with tide snapshot already populated
     * @param coastalParams   per-location coastal parameters (shore normal, fetch, depth)
     * @param locationId      the location database ID for tide stats lookup
     * @param locationName    location name for logging
     * @param forecastResponse the raw Open-Meteo forecast response for high-tide time extraction
     * @return a new {@link AtmosphericData} with surge data populated where applicable
     */
    public AtmosphericData augmentWithStormSurge(AtmosphericData base,
            CoastalParameters coastalParams, Long locationId, String locationName,
            OpenMeteoForecastResponse forecastResponse) {
        if (!coastalParams.isCoastalTidal() || base.tide() == null
                || base.tide().nextHighTideTime() == null
                || base.tide().nextHighTideHeightMetres() == null
                || forecastResponse == null) {
            return base;
        }

        try {
            LocalDateTime highTideTime = base.tide().nextHighTideTime();
            OpenMeteoForecastResponse.Hourly h = forecastResponse.getHourly();
            List<String> times = h.getTime();
            int idx = TimeSlotUtils.findNearestIndex(times, highTideTime);

            Double pressure = h.getSurfacePressure() != null && idx < h.getSurfacePressure().size()
                    ? h.getSurfacePressure().get(idx) : null;
            Double windSpeed = h.getWindSpeed10m() != null && idx < h.getWindSpeed10m().size()
                    ? h.getWindSpeed10m().get(idx) : null;
            Integer windDir = h.getWindDirection10m() != null && idx < h.getWindDirection10m().size()
                    ? h.getWindDirection10m().get(idx) : null;

            if (pressure == null || windSpeed == null || windDir == null) {
                LOG.debug("Surge skipped for {}: missing weather at high-tide time {}", locationName,
                        highTideTime);
                return base;
            }

            // Derive lunar tide type from tide stats
            String lunarTideType = deriveLunarTideType(locationId, base.tide().nextHighTideHeightMetres());

            // Calculate astronomical tidal range
            double highTide = base.tide().nextHighTideHeightMetres().doubleValue();
            double lowTide = base.tide().nextLowTideHeightMetres() != null
                    ? base.tide().nextLowTideHeightMetres().doubleValue() : 0.0;
            double astronomicalRange = highTide - lowTide;

            var result = weatherAugmentedTideService.augment(
                    pressure, windSpeed, windDir, coastalParams, lunarTideType,
                    astronomicalRange, highTide);

            surgeCalibrationLogger.logPrediction(locationId, locationName,
                    highTideTime.toInstant(java.time.ZoneOffset.UTC), result.surgeBreakdown());

            return base.withSurge(result.surgeBreakdown(), result.adjustedRangeMetres(),
                    astronomicalRange);
        } catch (Exception e) {
            LOG.warn("Surge calculation failed for {} — continuing without surge: {}",
                    locationName, e.getMessage());
            return base;
        }
    }

    /**
     * Returns a copy of {@code base} with a cloud inversion score for elevated water-overlook
     * locations. The score is computed from temperature-dew point gap, wind speed, humidity,
     * and low cloud cover. Returns the original data unchanged if the location does not meet
     * the elevation/water criteria.
     *
     * @param base             atmospheric data to augment
     * @param elevationMetres  location elevation in metres, or null
     * @param overlooksWater   whether the location overlooks water
     * @return a new {@link AtmosphericData} with inversion score populated where applicable
     */
    public AtmosphericData augmentWithInversionScore(AtmosphericData base,
            Integer elevationMetres, boolean overlooksWater) {
        if (elevationMetres == null
                || elevationMetres < InversionScoreCalculator.MIN_ELEVATION_METRES
                || !overlooksWater) {
            return base;
        }
        Double score = InversionScoreCalculator.calculate(base);
        if (score == null) {
            return base;
        }
        LOG.debug("Inversion score for {}: {}/10", base.locationName(), score);
        return base.withInversionScore(score);
    }

    /**
     * Derives a lunar tide type string from tide stats and the predicted high tide height.
     *
     * @param locationId   the location database ID
     * @param highTideHeight the predicted next high tide height
     * @return "KING_TIDE", "SPRING_TIDE", or "REGULAR_TIDE"
     */
    private String deriveLunarTideType(Long locationId, BigDecimal highTideHeight) {
        Optional<TideStats> statsOpt = tideService.getTideStats(locationId);
        if (statsOpt.isEmpty() || highTideHeight == null) {
            return "REGULAR_TIDE";
        }
        TideStats stats = statsOpt.get();
        if (stats.p95HighMetres() != null
                && highTideHeight.compareTo(stats.p95HighMetres()) > 0) {
            return "KING_TIDE";
        }
        if (stats.springTideThreshold() != null
                && highTideHeight.compareTo(stats.springTideThreshold()) > 0) {
            return "SPRING_TIDE";
        }
        return "REGULAR_TIDE";
    }
}
