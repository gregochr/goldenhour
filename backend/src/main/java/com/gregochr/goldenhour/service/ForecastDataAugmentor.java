package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TideStatisticalSize;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.TideData;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.TideStats;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Augments {@link AtmosphericData} with directional cloud data and tide information.
 *
 * <p>Extracted from {@link ForecastService} to separate the data-enrichment pipeline
 * from forecast orchestration. Used by {@code ForecastService}, {@code ModelTestService},
 * and {@code PromptTestService}.
 */
@Service
public class ForecastDataAugmentor {

    private final OpenMeteoService openMeteoService;
    private final TideService tideService;
    private final LunarPhaseService lunarPhaseService;

    /**
     * Constructs a {@code ForecastDataAugmentor} with the services needed for data enrichment.
     *
     * @param openMeteoService  retrieves directional cloud data from Open-Meteo
     * @param tideService       fetches tide data for coastal locations
     * @param lunarPhaseService computes lunar tide classification and moon phase
     */
    public ForecastDataAugmentor(OpenMeteoService openMeteoService, TideService tideService,
            LunarPhaseService lunarPhaseService) {
        this.openMeteoService = openMeteoService;
        this.tideService = tideService;
        this.lunarPhaseService = lunarPhaseService;
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
        var directional = openMeteoService.fetchDirectionalCloudData(
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
        if (base.weather() == null) {
            return base;
        }
        CloudApproachData approach = openMeteoService.fetchCloudApproachData(
                lat, lon, solarAzimuth, eventTime, currentTime, base.targetType(),
                base.weather().windDirectionDegrees(),
                base.weather().windSpeedMs().doubleValue(), jobRun);
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
}
