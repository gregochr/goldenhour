package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.config.CoastalMistProperties;
import com.gregochr.goldenhour.config.InversionProperties;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BluebellConditionScore;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.CoastalMistBand;
import com.gregochr.goldenhour.model.CoastalMistResult;
import com.gregochr.goldenhour.model.CoastalParameters;
import com.gregochr.goldenhour.model.InversionV2Result;
import com.gregochr.goldenhour.model.MistPotential;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.TideContext;
import com.gregochr.goldenhour.model.TideDerivation;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.util.TimeSlotUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Augments {@link AtmosphericData} with directional cloud data, tide information,
 * and storm surge calculations.
 *
 * <p>Extracted from {@link ForecastService} to separate the data-enrichment pipeline
 * from forecast orchestration. Used by {@code ForecastService}.
 */
@Service
public class ForecastDataAugmentor {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastDataAugmentor.class);

    /**
     * Viewpoint height in metres below which the v2 inversion score may raise a site's score by at most
     * {@link #RAISE_CAP_POINTS} over the old score (the first-winter asymmetry, owner decision 2026-10-30,
     * option A). At or above it a summit is uncapped: the old score reads a summit's own clear cell and would
     * hold confirmed inversions down.
     */
    public static final int RAISE_CAP_MAX_ELEVATION_M = 600;

    /**
     * The most v2 may raise a low site's score over the old one. With whole-number scores this also means a
     * site the old calculator scored below MODERATE (7) can never be raised to STRONG (9).
     */
    static final double RAISE_CAP_POINTS = 1.0;

    private final OpenMeteoService openMeteoService;
    private final SolarService solarService;
    private final TideService tideService;
    private final TideFactDeriver tideFactDeriver;
    private final WeatherAugmentedTideService weatherAugmentedTideService;
    private final SurgeCalibrationLogger surgeCalibrationLogger;
    private final BluebellConditionService bluebellConditionService;
    private final SeasonalWindow bluebellSeason;
    private final InversionProperties inversionProperties;
    private final CoastalMistProperties coastalMistProperties;

    /**
     * Constructs a {@code ForecastDataAugmentor} with the services needed for data enrichment, with the v2
     * inversion switches at their defaults (v2 on, raise cap on).
     *
     * @param openMeteoService             retrieves directional cloud data from Open-Meteo
     * @param solarService                 calculates solar event times and golden/blue windows
     * @param tideService                  fetches tide data for coastal locations
     * @param tideFactDeriver              the single seam for deriving tide facts (snapshot)
     * @param weatherAugmentedTideService  storm surge calculation service
     * @param surgeCalibrationLogger       structured logging for surge calibration
     * @param bluebellConditionService     scores bluebell photography conditions
     * @param bluebellSeason               the configured bluebell season window
     */
    public ForecastDataAugmentor(OpenMeteoService openMeteoService, SolarService solarService,
            TideService tideService,
            TideFactDeriver tideFactDeriver,
            WeatherAugmentedTideService weatherAugmentedTideService,
            SurgeCalibrationLogger surgeCalibrationLogger,
            BluebellConditionService bluebellConditionService,
            SeasonalWindow bluebellSeason) {
        this(openMeteoService, solarService, tideService, tideFactDeriver, weatherAugmentedTideService,
                surgeCalibrationLogger, bluebellConditionService, bluebellSeason, new InversionProperties(),
                new CoastalMistProperties());
    }

    /**
     * Constructs a {@code ForecastDataAugmentor} with the v2 inversion switches supplied and the coastal mist
     * switch at its default (on).
     *
     * @param openMeteoService             retrieves directional cloud data from Open-Meteo
     * @param solarService                 calculates solar event times and golden/blue windows
     * @param tideService                  fetches tide data for coastal locations
     * @param tideFactDeriver              the single seam for deriving tide facts (snapshot)
     * @param weatherAugmentedTideService  storm surge calculation service
     * @param surgeCalibrationLogger       structured logging for surge calibration
     * @param bluebellConditionService     scores bluebell photography conditions
     * @param bluebellSeason               the configured bluebell season window
     * @param inversionProperties          the v2 inversion switches ({@code photocast.inversion})
     */
    public ForecastDataAugmentor(OpenMeteoService openMeteoService, SolarService solarService,
            TideService tideService,
            TideFactDeriver tideFactDeriver,
            WeatherAugmentedTideService weatherAugmentedTideService,
            SurgeCalibrationLogger surgeCalibrationLogger,
            BluebellConditionService bluebellConditionService,
            SeasonalWindow bluebellSeason,
            InversionProperties inversionProperties) {
        this(openMeteoService, solarService, tideService, tideFactDeriver, weatherAugmentedTideService,
                surgeCalibrationLogger, bluebellConditionService, bluebellSeason, inversionProperties,
                new CoastalMistProperties());
    }

    /**
     * Constructs a {@code ForecastDataAugmentor} with the v2 inversion and coastal mist switches supplied.
     *
     * @param openMeteoService             retrieves directional cloud data from Open-Meteo
     * @param solarService                 calculates solar event times and golden/blue windows
     * @param tideService                  fetches tide data for coastal locations
     * @param tideFactDeriver              the single seam for deriving tide facts (snapshot)
     * @param weatherAugmentedTideService  storm surge calculation service
     * @param surgeCalibrationLogger       structured logging for surge calibration
     * @param bluebellConditionService     scores bluebell photography conditions
     * @param bluebellSeason               the configured bluebell season window
     * @param inversionProperties          the v2 inversion switches ({@code photocast.inversion})
     * @param coastalMistProperties        the coastal mist switch ({@code photocast.coastal-mist})
     */
    @Autowired
    public ForecastDataAugmentor(OpenMeteoService openMeteoService, SolarService solarService,
            TideService tideService,
            TideFactDeriver tideFactDeriver,
            WeatherAugmentedTideService weatherAugmentedTideService,
            SurgeCalibrationLogger surgeCalibrationLogger,
            BluebellConditionService bluebellConditionService,
            SeasonalWindow bluebellSeason,
            InversionProperties inversionProperties,
            CoastalMistProperties coastalMistProperties) {
        this.openMeteoService = openMeteoService;
        this.solarService = solarService;
        this.tideService = tideService;
        this.tideFactDeriver = tideFactDeriver;
        this.weatherAugmentedTideService = weatherAugmentedTideService;
        this.surgeCalibrationLogger = surgeCalibrationLogger;
        this.bluebellConditionService = bluebellConditionService;
        this.bluebellSeason = bluebellSeason;
        this.inversionProperties = inversionProperties;
        this.coastalMistProperties = coastalMistProperties;
    }

    /**
     * Whether the v2 inversion score is switched on ({@code photocast.inversion.v2.enabled}).
     *
     * @return true when v2 drives the stored inversion score for sites that have a valley profile
     */
    public boolean inversionV2Enabled() {
        return inversionProperties.getV2().isEnabled();
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
     * @param lat        observer latitude for golden/blue window calculation
     * @param lon        observer longitude for golden/blue window calculation
     * @param targetType SUNRISE or SUNSET, used for golden/blue window calculation
     * @return a new {@link AtmosphericData} with tide fields populated where applicable
     */
    public AtmosphericData augmentWithTideData(AtmosphericData base, Long locationId,
            LocalDateTime eventTime, Set<TideType> tideTypes,
            double lat, double lon, TargetType targetType) {
        return augmentWithTideData(base, locationId, eventTime, tideTypes, lat, lon, targetType,
                null);
    }

    /**
     * As {@link #augmentWithTideData(AtmosphericData, Long, LocalDateTime, Set, double, double,
     * TargetType)}, reading the stored extremes from a collection-scoped snapshot when one is
     * given. Derives the identical tide facts; only the number of queries differs.
     *
     * @param base       atmospheric data without tide fields
     * @param locationId the location primary key, or {@code null} if inland
     * @param eventTime  UTC time of the solar event
     * @param tideTypes  tide preferences for this location (empty if inland)
     * @param lat        observer latitude
     * @param lon        observer longitude
     * @param targetType SUNRISE or SUNSET
     * @param snapshot   the collection's tide snapshot, or {@code null} to query per call
     * @return a new {@link AtmosphericData} with tide fields populated where applicable
     */
    public AtmosphericData augmentWithTideData(AtmosphericData base, Long locationId,
            LocalDateTime eventTime, Set<TideType> tideTypes,
            double lat, double lon, TargetType targetType, TideExtremeSnapshot snapshot) {
        return buildTideSnapshot(locationId, eventTime, tideTypes, lat, lon, targetType, snapshot)
                .map(base::withTide)
                .orElse(base);
    }

    /**
     * Loads one tide snapshot for the coastal locations among the given ones over the dates'
     * window, so a batch of {@link #augmentWithTideData} calls reads from memory.
     *
     * @param locations the candidates' locations (inland ones are skipped)
     * @param dates     the candidates' dates, any order
     * @return the snapshot
     */
    public TideExtremeSnapshot tideSnapshotFor(java.util.Collection<LocationEntity> locations,
            java.util.Collection<java.time.LocalDate> dates) {
        List<Long> ids = locations.stream()
                .filter(l -> l.getTideType() != null && !l.getTideType().isEmpty())
                .map(LocationEntity::getId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return tideService.snapshotFor(ids, dates);
    }

    /**
     * Re-derives the tide context for a coastal location at the post-batch combine seam, computing
     * eventTime from the solar event itself (a pure, deterministic function of lat/lon/date — the
     * identical instant the prompt pipeline used).
     *
     * <p>Inland locations (empty {@code tideType}) yield {@link Optional#empty()} so they are
     * scored on sky alone.
     *
     * @param location   the location under evaluation
     * @param date       the evaluation date
     * @param targetType SUNRISE or SUNSET
     * @return the tide context (snapshot + widened-alignment flag), or empty when inland or the
     *         tide could not be derived (a data gap)
     */
    public Optional<TideContext> deriveTideContext(LocationEntity location, LocalDate date,
            TargetType targetType) {
        Set<TideType> tideTypes = location.getTideType();
        if (tideTypes == null || tideTypes.isEmpty()) {
            return Optional.empty();
        }
        LocalDateTime eventTime = targetType == TargetType.SUNRISE
                ? solarService.sunriseUtc(location.getLat(), location.getLon(), date)
                : solarService.sunsetUtc(location.getLat(), location.getLon(), date);
        return deriveTideContext(location.getId(), eventTime, tideTypes,
                location.getLat(), location.getLon(), targetType);
    }

    /**
     * Re-derives the tide context for explicit coordinates and a known event time.
     *
     * <p>Returns the same {@link TideSnapshot} {@link #augmentWithTideData} would produce (tight
     * alignment baked in), plus the widened-alignment flag. Both flags come from the single
     * {@link TideFactDeriver#derive} call — the seam derives them from one extremes fetch (same
     * tide curve, two windows), so the combine seam no longer fetches a second time.
     *
     * @param locationId the location primary key
     * @param eventTime  UTC time of the solar event
     * @param tideTypes  the location's tide preferences
     * @param lat        observer latitude (for the golden/blue window width)
     * @param lon        observer longitude (for the golden/blue window width)
     * @param targetType SUNRISE or SUNSET
     * @return the tide context, or empty when inland or the tide could not be derived
     */
    public Optional<TideContext> deriveTideContext(Long locationId, LocalDateTime eventTime,
            Set<TideType> tideTypes, double lat, double lon, TargetType targetType) {
        return tideFactDeriver.derive(locationId, eventTime, tideTypes, lat, lon, targetType)
                .map(d -> new TideContext(toSnapshot(d), d.widenedAligned()));
    }

    /**
     * Builds the tide snapshot for a coastal location, or empty when inland or the tide cannot be
     * derived. Shared by {@link #augmentWithTideData} (the forecast prompt path) and
     * {@link #deriveTideContext} (the combine seam) so the snapshot is constructed in one place.
     */
    private Optional<TideSnapshot> buildTideSnapshot(Long locationId, LocalDateTime eventTime,
            Set<TideType> tideTypes, double lat, double lon, TargetType targetType,
            TideExtremeSnapshot extremes) {
        return tideFactDeriver.derive(locationId, eventTime, tideTypes, lat, lon, targetType,
                        extremes)
                .map(this::toSnapshot);
    }

    /**
     * Maps a {@link TideDerivation} to the scoring path's {@link TideSnapshot}, collapsing the two
     * statistical height booleans into {@link TideDerivation#statisticalSize()} and dropping the
     * widened-alignment flag (which the snapshot does not carry — it lives on {@link TideContext}).
     *
     * @param d the derived tide facts
     * @return the equivalent tide snapshot
     */
    private TideSnapshot toSnapshot(TideDerivation d) {
        return new TideSnapshot(
                d.tideState(),
                d.nextHighTideTime(),
                d.nextHighTideHeightMetres(),
                d.nextLowTideTime(),
                d.nextLowTideHeightMetres(),
                d.tideAligned(),
                d.nearestHighTideTime(),
                d.nearestLowTideTime(),
                d.lunarTideType(),
                d.lunarPhase(),
                d.moonAtPerigee(),
                d.statisticalSize());
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

            // pressure_msl, NOT surface_pressure. The inverse-barometer term measures departure
            // from STANDARD_PRESSURE_HPA (1013.25), which is a MEAN-SEA-LEVEL reference, while
            // surface_pressure is reduced to the grid cell's own elevation — so an elevated coastal
            // cell reported a permanently low pressure and the model read it as a standing surge of
            // roughly +0.04 to +0.06 m that no weather caused. Small, but it sits right at
            // SIGNIFICANCE_THRESHOLD_M (0.05), so it could carry a calm day over the gate on its
            // own. Changed here and in SurgeCurveService together: the chip and the curve must run
            // one function over one field, or the pill contradicts itself.
            Double pressure = h.getPressureMsl() != null && idx < h.getPressureMsl().size()
                    ? h.getPressureMsl().get(idx) : null;
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
     * locations. Returns the original data unchanged if the location does not meet the elevation/water
     * criteria.
     *
     * <p>Without a v2 valley profile on {@code base} (a site with no confirmed valley floor, a failed fetch, or
     * the switch off) the score is the old {@link InversionScoreCalculator}'s, exactly as before. With one,
     * BOTH calculators run and the stored value follows these rules:
     * <ul>
     *   <li>v2 cannot score (hours missing): the old score.</li>
     *   <li>the old score is null but v2 scores: v2, capped at
     *       {@value InversionScoreCalculator#VETOED_CEILING}.</li>
     *   <li>otherwise v2's value, except that for a viewpoint below {@value #RAISE_CAP_MAX_ELEVATION_M} m, with
     *       {@code photocast.inversion.v2.raise-cap-enabled} on, v2 may raise the old score by at most
     *       {@value #RAISE_CAP_POINTS} point. A summit is uncapped.</li>
     * </ul>
     * One INFO line per scored slot records the old and new scores and why.
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
        warnIfNoVerticalProfile(base);
        Double old = InversionScoreCalculator.calculate(base, elevationMetres);
        InversionV2Result v2 = inversionV2Enabled()
                ? InversionScoreCalculatorV2.calculate(base, elevationMetres) : null;
        if (v2 == null) {
            if (old == null) {
                return base;
            }
            LOG.debug("Inversion score for {}: {}/10", base.locationName(), old);
            return base.withInversionScore(old);
        }

        double stored = v2.v2Raw();
        String caps = v2.caps();
        if (old == null) {
            if (stored > InversionScoreCalculator.VETOED_CEILING) {
                stored = InversionScoreCalculator.VETOED_CEILING;
                caps = appendCap(caps, "no-v1", stored);
            }
        } else if (inversionProperties.getV2().isRaiseCapEnabled()
                && elevationMetres < RAISE_CAP_MAX_ELEVATION_M
                && stored > old + RAISE_CAP_POINTS) {
            stored = old + RAISE_CAP_POINTS;
            caps = appendCap(caps, "raise", stored);
        }
        InversionV2Result scored = v2.withCaps(caps).withStorage(old, stored, true);
        logInversionV2(base, scored);
        return base.withInversionScore(stored).withInversionV2(scored);
    }

    private static String appendCap(String caps, String name, double value) {
        String cap = name + "(" + (int) value + ")";
        return "-".equals(caps) ? cap : caps + "," + cap;
    }

    /** The one INFO line per scored slot that lets the old and new scores be compared in the logs. */
    private static void logInversionV2(AtmosphericData base, InversionV2Result r) {
        LOG.info("[INVERSION v2] {} {} {} old={} new={} regime={} base={} top={} strength={} viewpoint={} "
                        + "valley={} caps={} night={} optionB={} wind={}@{}",
                base.locationName(), base.solarEventTime().toLocalDate(), base.targetType(),
                formatScore(r.v1Score()), formatScore(r.stored()), r.regime(),
                formatMetres(r.baseMetres()), formatMetres(r.topMetres()),
                r.strengthCelsius() == null ? "-" : String.format("%.1f", r.strengthCelsius()),
                r.viewpointMetres(), r.valleyFloorMetres(), r.caps(),
                r.nightPercent() == null ? "-" : String.format("%.1f", r.nightPercent()), r.optionB(),
                r.windMs() == null ? "-" : String.format("%.1f", r.windMs()),
                r.windLevelHpa() == null ? "-" : r.windLevelHpa());
    }

    private static String formatScore(Double score) {
        if (score == null) {
            return "-";
        }
        return score == Math.rint(score) ? String.valueOf((int) Math.rint(score)) : String.format("%.1f", score);
    }

    private static String formatMetres(Double metres) {
        return metres == null ? "-" : String.valueOf(Math.round(metres));
    }

    /**
     * Returns a copy of {@code base} with a valley mist score and band for a {@code mist_prone}
     * site (valley-mist-plan M1a). The gate is the site fact alone — an unflagged site is returned
     * unchanged and never scored — because mist forming AROUND a place is geography the 11 km cell
     * cannot show (the {@code overlooksWater} pattern). Returns the original data unchanged too when
     * {@link ValleyMistCalculator} cannot score for want of inputs, which
     * {@code SlotAtmosphereWriter} records as an authoritative null.
     *
     * @param base      atmospheric data to augment
     * @param mistProne whether the location is flagged as one where mist forms around the camera
     * @return a new {@link AtmosphericData} with the mist reading populated where applicable
     */
    public AtmosphericData augmentWithValleyMist(AtmosphericData base, boolean mistProne) {
        if (!mistProne) {
            return base;
        }
        Double score = ValleyMistCalculator.calculate(base);
        if (score == null) {
            return base;
        }
        MistPotential potential = ValleyMistCalculator.classify(base, score);
        LOG.debug("Valley mist score for {}: {}/10 ({})", base.locationName(), score, potential);
        return base.withValleyMist(score, potential);
    }

    /**
     * Bands the event slot for a light sea mist at a coastal site (coastal-mist-plan Phase 1) and attaches the
     * carrier. Runs only where {@link CoastalMistCalculator#appliesTo} holds (the location has tide types and
     * is not {@code mist_prone}) and {@code photocast.coastal-mist.enabled} is true; otherwise {@code base} is
     * returned unchanged with a null carrier, so the writer stores a null band and {@code coastal_mist_scored
     * = false}. Never reads {@code base.tide()}: a failed tide fetch must not drop the band.
     *
     * <p>Logs one {@code [COASTAL MIST]} line per slot: INFO when the band is not NONE, DEBUG for NONE.
     *
     * @param base     atmospheric data to augment
     * @param location the location being forecast
     * @param date     the forecast date (for the log line)
     * @return a new {@link AtmosphericData} carrying the coastal mist result, or {@code base} unchanged
     */
    public AtmosphericData augmentWithCoastalMist(AtmosphericData base, LocationEntity location,
            LocalDate date) {
        if (!coastalMistProperties.isEnabled() || !CoastalMistCalculator.appliesTo(location)) {
            return base;
        }
        CoastalMistResult result = CoastalMistCalculator.calculate(base);
        if (result.band() == CoastalMistBand.NONE) {
            LOG.debug("[COASTAL MIST] {} {} {} vis={} gap={} low={} band={}", base.locationName(), date,
                    base.targetType(), result.visibilityMetres(), result.gapC(), result.lowCloudPct(),
                    result.band());
        } else {
            LOG.info("[COASTAL MIST] {} {} {} vis={} gap={} low={} band={}", base.locationName(), date,
                    base.targetType(), result.visibilityMetres(), result.gapC(), result.lowCloudPct(),
                    result.band());
        }
        return base.withCoastalMist(result);
    }

    /**
     * Warns when an inversion-eligible location arrives with no pressure-level temperature.
     *
     * <p>Without one the calculator caps the score below the STRONG band, so the inversion hot
     * topic stops firing entirely. That is the right call — STRONG should not be claimed without
     * a measured reversal — but it would otherwise be indistinguishable from a spell of genuinely
     * poor conditions, so it is logged rather than left to be inferred from an empty strip.
     *
     * @param base the atmospheric data about to be scored
     */
    private void warnIfNoVerticalProfile(AtmosphericData base) {
        if (base.weather() == null
                || base.weather().temperature925hPaCelsius() != null
                || base.weather().temperature850hPaCelsius() != null) {
            return;
        }
        LOG.warn("No pressure-level temperature for inversion-eligible location {} — "
                + "inversion score capped at {} (STRONG needs a measured reversal)",
                base.locationName(), InversionScoreCalculator.MARGINAL_CEILING);
    }

    /**
     * Augments {@code base} with bluebell photography condition scoring when applicable.
     *
     * <p>Only augments when:
     * <ul>
     *   <li>the target date falls within the configured bluebell {@link SeasonalWindow}</li>
     *   <li>the location has {@code BLUEBELL} in its location types</li>
     *   <li>a {@link BluebellExposure} is set on the location</li>
     * </ul>
     * Returns {@code base} unchanged when any condition is not met.
     *
     * @param base          atmospheric data to augment
     * @param locationTypes the location's type set
     * @param exposure      the location's bluebell exposure type, or null
     * @param targetDate    the calendar date being evaluated
     * @return a new {@link AtmosphericData} with bluebell score populated where applicable
     */
    public AtmosphericData augmentWithBluebellConditions(AtmosphericData base,
            Set<LocationType> locationTypes, BluebellExposure exposure,
            LocalDate targetDate) {
        if (!bluebellSeason.isActive(targetDate)) {
            return base;
        }
        if (locationTypes == null || !locationTypes.contains(LocationType.BLUEBELL)) {
            return base;
        }
        if (exposure == null) {
            return base;
        }
        BluebellConditionScore score = bluebellConditionService.score(base, exposure);
        LOG.debug("Bluebell score for {}: {}/10 ({})", base.locationName(), score.overall(),
                score.qualityLabel());
        return base.withBluebellConditionScore(score);
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
