package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.TideData;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.util.TimeSlotUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Builds individual briefing slots by combining weather data, solar event times,
 * tide data, and verdict evaluation for a single location-date-event combination.
 */
@Component
public class BriefingSlotBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(BriefingSlotBuilder.class);

    /** Minutes within which a high tide is considered coincident with the solar event. */
    private static final long TIDE_WINDOW_MINUTES = 90;

    /** Scale for decimal weather values. */
    private static final int DECIMAL_SCALE = 2;

    private final SolarService solarService;
    private final LocationService locationService;
    private final TideService tideService;
    private final LunarPhaseService lunarPhaseService;
    private final BriefingVerdictEvaluator verdictEvaluator;

    /**
     * Constructs a {@code BriefingSlotBuilder}.
     *
     * @param solarService      service for calculating sunrise/sunset times
     * @param locationService   service for location metadata (coastal checks)
     * @param tideService       service for tide data lookups
     * @param lunarPhaseService service for lunar phase and tide classification
     * @param verdictEvaluator  evaluator for slot verdicts and flag generation
     */
    public BriefingSlotBuilder(SolarService solarService, LocationService locationService,
            TideService tideService, LunarPhaseService lunarPhaseService,
            BriefingVerdictEvaluator verdictEvaluator) {
        this.solarService = solarService;
        this.locationService = locationService;
        this.tideService = tideService;
        this.lunarPhaseService = lunarPhaseService;
        this.verdictEvaluator = verdictEvaluator;
    }

    /**
     * Builds a briefing slot for a location at a specific date and event type.
     *
     * @param lw        the location and its fetched forecast data
     * @param date      the target date
     * @param eventType SUNRISE or SUNSET
     * @return the briefing slot, or null if the solar event time cannot be determined
     */
    public BriefingSlot buildSlot(LocationWeather lw, LocalDate date, TargetType eventType) {
        LocationEntity loc = lw.location();
        OpenMeteoForecastResponse forecast = lw.forecast();

        LocalDateTime solarTime;
        try {
            solarTime = eventType == TargetType.SUNRISE
                    ? solarService.sunriseUtc(loc.getLat(), loc.getLon(), date)
                    : solarService.sunsetUtc(loc.getLat(), loc.getLon(), date);
        } catch (Exception e) {
            LOG.debug("Cannot compute {} for {} on {}: {}",
                    eventType, loc.getName(), date, e.getMessage());
            return null;
        }

        // Find nearest hourly slot
        List<String> times = forecast.getHourly().getTime();
        int idx = TimeSlotUtils.findBestIndex(times, solarTime, eventType);
        OpenMeteoForecastResponse.Hourly h = forecast.getHourly();

        int lowCloud = h.getCloudCoverLow().get(idx);
        BigDecimal precip = BigDecimal.valueOf(h.getPrecipitation().get(idx))
                .setScale(DECIMAL_SCALE, RoundingMode.HALF_UP);
        int visibility = h.getVisibility().get(idx).intValue();
        int humidity = h.getRelativeHumidity2m().get(idx);
        Double temp = h.getTemperature2m() != null && idx < h.getTemperature2m().size()
                ? h.getTemperature2m().get(idx) : null;
        Double apparentTemp = h.getApparentTemperature() != null
                && idx < h.getApparentTemperature().size()
                ? h.getApparentTemperature().get(idx) : null;
        Integer weatherCode = h.getWeatherCode() != null && idx < h.getWeatherCode().size()
                ? h.getWeatherCode().get(idx) : null;
        BigDecimal windSpeed = BigDecimal.valueOf(h.getWindSpeed10m().get(idx))
                .setScale(DECIMAL_SCALE, RoundingMode.HALF_UP);

        // Tide data from DB
        TideResult tideResult = calculateTideData(loc, solarTime);

        // Determine weather verdict
        Verdict verdict = verdictEvaluator.determineVerdict(lowCloud, precip, visibility, humidity);

        // Coastal tide demotion: if coastal, tide data is present, but tide is not aligned
        // → override to STANDDOWN regardless of weather. If tide data is absent (tideState == null),
        // leave the weather-only verdict intact so missing data does not penalise the location.
        boolean tidesNotAligned = false;
        if (locationService.isCoastal(loc) && tideResult.tideState() != null
                && !tideResult.tideAligned() && verdict != Verdict.STANDDOWN) {
            verdict = Verdict.STANDDOWN;
            tidesNotAligned = true;
        }

        // Build flags
        BriefingVerdictEvaluator.WeatherMetrics weatherMetrics =
                new BriefingVerdictEvaluator.WeatherMetrics(lowCloud, precip, visibility, humidity);
        BriefingVerdictEvaluator.TideContext tideContext = new BriefingVerdictEvaluator.TideContext(
                tideResult.tideState(), tideResult.tideAligned(),
                tideResult.isKingTide(), tideResult.isSpringTide(),
                tideResult.lunarTideType(), tidesNotAligned);
        List<String> flags = verdictEvaluator.buildFlags(weatherMetrics, tideContext);

        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                lowCloud, precip, visibility, humidity, temp, apparentTemp, weatherCode, windSpeed);
        BriefingSlot.TideInfo tideInfo = new BriefingSlot.TideInfo(
                tideResult.tideState(), tideResult.tideAligned(),
                tideResult.nearestHighTime(), tideResult.nearestHighHeight(),
                tideResult.isKingTide(), tideResult.isSpringTide(),
                tideResult.lunarTideType(), tideResult.lunarPhase(),
                tideResult.moonAtPerigee());

        return new BriefingSlot(loc.getName(), solarTime, verdict, weather, tideInfo, flags);
    }

    /**
     * Intermediate result of tide data calculation for a coastal location.
     */
    record TideResult(String tideState, boolean tideAligned,
            LocalDateTime nearestHighTime, BigDecimal nearestHighHeight,
            boolean isKingTide, boolean isSpringTide,
            LunarTideType lunarTideType, String lunarPhase, Boolean moonAtPerigee) {

        static final TideResult NONE =
                new TideResult(null, false, null, null, false, false, null, null, null);
    }

    /**
     * Calculates tide data for a location at a given solar event time.
     * Returns {@link TideResult#NONE} for inland locations or when no tide data is available.
     *
     * @param loc       the location entity
     * @param solarTime the UTC time of the solar event
     * @return tide calculation result
     */
    private TideResult calculateTideData(LocationEntity loc, LocalDateTime solarTime) {
        if (!locationService.isCoastal(loc)) {
            return TideResult.NONE;
        }
        Optional<TideData> tideOpt = tideService.deriveTideData(loc.getId(), solarTime);
        if (tideOpt.isEmpty()) {
            return TideResult.NONE;
        }

        TideData td = tideOpt.get();
        String tideState = td.tideState().name();
        boolean tideAligned = tideService.calculateTideAligned(td, loc.getTideType());
        LocalDateTime nearestHighTime = td.nearestHighTideTime();
        BigDecimal nearestHighHeight = td.nextHighTideHeightMetres();
        boolean isKingTide = false;
        boolean isSpringTide = false;

        if (td.tideState() == TideState.HIGH && nearestHighTime != null
                && Math.abs(ChronoUnit.MINUTES.between(nearestHighTime, solarTime))
                        <= TIDE_WINDOW_MINUTES) {
            Optional<TideStats> statsOpt = tideService.getTideStats(loc.getId());
            if (statsOpt.isPresent()) {
                TideStats stats = statsOpt.get();
                BigDecimal height = td.nextHighTideHeightMetres();
                if (height != null && stats.p95HighMetres() != null
                        && height.compareTo(stats.p95HighMetres()) > 0) {
                    isKingTide = true;
                }
                if (height != null && stats.springTideThreshold() != null
                        && height.compareTo(stats.springTideThreshold()) > 0) {
                    isSpringTide = true;
                }
            }
        }

        // Lunar classification — deterministic from date
        LocalDate eventDate = solarTime.toLocalDate();
        LunarTideType lunarTideType = lunarPhaseService.classifyTide(eventDate);
        String lunarPhase = lunarPhaseService.getMoonPhase(eventDate);
        Boolean moonAtPerigee = lunarPhaseService.isMoonAtPerigee(eventDate);

        return new TideResult(tideState, tideAligned, nearestHighTime,
                nearestHighHeight, isKingTide, isSpringTide,
                lunarTideType, lunarPhase, moonAtPerigee);
    }

    /**
     * Location and its fetched forecast data (forecast may be null on failure).
     *
     * @param location the location entity
     * @param forecast the Open-Meteo forecast response, or null if fetch failed
     */
    public record LocationWeather(LocationEntity location, OpenMeteoForecastResponse forecast) {
    }
}
