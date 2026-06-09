package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.TideDerivation;
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
    private final TideFactDeriver tideFactDeriver;
    private final BriefingVerdictEvaluator verdictEvaluator;

    /**
     * Constructs a {@code BriefingSlotBuilder}.
     *
     * @param solarService      service for calculating sunrise/sunset times
     * @param locationService   service for location metadata (coastal checks)
     * @param tideFactDeriver   the single seam for deriving tide facts
     * @param verdictEvaluator  evaluator for slot verdicts and flag generation
     */
    public BriefingSlotBuilder(SolarService solarService, LocationService locationService,
            TideFactDeriver tideFactDeriver,
            BriefingVerdictEvaluator verdictEvaluator) {
        this.solarService = solarService;
        this.locationService = locationService;
        this.tideFactDeriver = tideFactDeriver;
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
        return buildSlot(lw, date, eventType, null);
    }

    /**
     * Builds a briefing slot for a location at a specific date and event type,
     * optionally incorporating solar horizon cloud data.
     *
     * @param lw              the location and its fetched forecast data
     * @param date            the target date
     * @param eventType       SUNRISE or SUNSET
     * @param horizonForecast cloud-only forecast at the solar horizon point (nullable)
     * @return the briefing slot, or null if the solar event time cannot be determined
     */
    public BriefingSlot buildSlot(LocationWeather lw, LocalDate date, TargetType eventType,
            OpenMeteoForecastResponse horizonForecast) {
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
        int midCloud = (h.getCloudCoverMid() != null && idx < h.getCloudCoverMid().size())
                ? h.getCloudCoverMid().get(idx) : 0;
        int highCloud = (h.getCloudCoverHigh() != null && idx < h.getCloudCoverHigh().size())
                ? h.getCloudCoverHigh().get(idx) : 0;
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

        // Extract low cloud for 3 hours leading into the event (earliest first)
        List<Integer> lowCloudTrend = extractLowCloudTrend(h, idx);

        // Tide data from DB (using elevation-based window)
        TideResult tideResult = calculateTideData(loc, solarTime, eventType);

        // Determine weather verdict (base check)
        Verdict verdict = verdictEvaluator.determineVerdict(lowCloud, precip, visibility, humidity);

        // Demote for mid-cloud blanket (can only make verdict worse)
        verdict = verdictEvaluator.applyMidCloudDemotion(verdict, midCloud);

        // Demote for building cloud trend (GO → MARGINAL only)
        boolean buildingDetected = verdict == Verdict.GO;
        verdict = verdictEvaluator.applyCloudTrendDemotion(verdict, lowCloudTrend);
        buildingDetected = buildingDetected && verdict == Verdict.MARGINAL;

        // Demote for clear all layers (GO → MARGINAL only)
        BriefingVerdictEvaluator.WeatherMetrics clearSkyMetrics =
                new BriefingVerdictEvaluator.WeatherMetrics(
                        lowCloud, precip, visibility, humidity, midCloud, highCloud, false);
        Verdict preClearSkyVerdict = verdict;
        verdict = verdictEvaluator.applyClearSkyDemotion(verdict, clearSkyMetrics);
        logClearAllLayersDiagnostic(loc.getName(), eventType, lowCloud, midCloud, highCloud,
                preClearSkyVerdict, verdict);

        // Demote for solar horizon low cloud
        Integer horizonLowCloud = extractHorizonLowCloud(horizonForecast, idx);
        if (horizonLowCloud != null) {
            verdict = verdictEvaluator.applyHorizonCloudDemotion(verdict, horizonLowCloud);
        }

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
                new BriefingVerdictEvaluator.WeatherMetrics(
                        lowCloud, precip, visibility, humidity, midCloud, highCloud,
                        buildingDetected);
        BriefingVerdictEvaluator.TideContext tideContext = new BriefingVerdictEvaluator.TideContext(
                tideResult.tideState(), tideResult.tideAligned(),
                tideResult.isKingTide(), tideResult.isSpringTide(),
                tideResult.lunarTideType(), tidesNotAligned);
        List<String> flags = verdictEvaluator.buildFlags(weatherMetrics, tideContext);

        String standdownReason = verdict == Verdict.STANDDOWN
                ? verdictEvaluator.deriveStanddownReason(weatherMetrics, tidesNotAligned,
                        horizonLowCloud)
                : null;

        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                lowCloud, precip, visibility, humidity, temp, apparentTemp, weatherCode, windSpeed,
                midCloud, highCloud);
        BriefingSlot.TideInfo tideInfo = new BriefingSlot.TideInfo(
                tideResult.tideState(), tideResult.tideAligned(),
                tideResult.nearestHighTime(), tideResult.nearestHighHeight(),
                tideResult.isKingTide(), tideResult.isSpringTide(),
                tideResult.lunarTideType(), tideResult.lunarPhase(),
                tideResult.moonAtPerigee());

        return new BriefingSlot(loc.getName(), solarTime, verdict, weather, tideInfo, flags,
                standdownReason);
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
    private TideResult calculateTideData(LocationEntity loc, LocalDateTime solarTime,
            TargetType eventType) {
        if (!locationService.isCoastal(loc)) {
            return TideResult.NONE;
        }
        Optional<TideDerivation> derived = tideFactDeriver.derive(
                loc.getId(), solarTime, loc.getTideType(), loc.getLat(), loc.getLon(), eventType);
        if (derived.isEmpty()) {
            return TideResult.NONE;
        }
        TideDerivation d = derived.get();

        // Apply the briefing's high-tide alignment gate around the raw statistical signals from the
        // single derivation. The deriver returns ungated king/spring height flags; the briefing only
        // surfaces them when a high tide falls within +/-90 minutes of the solar event.
        boolean isKingTide = false;
        boolean isSpringTide = false;
        if (d.tideState() == TideState.HIGH && d.nearestHighTideTime() != null
                && Math.abs(ChronoUnit.MINUTES.between(d.nearestHighTideTime(), solarTime))
                        <= TIDE_WINDOW_MINUTES) {
            isKingTide = d.heightAboveP95();
            isSpringTide = d.heightAboveSpringThreshold();
        }

        return new TideResult(d.tideState().name(), d.tideAligned(), d.nearestHighTideTime(),
                d.nextHighTideHeightMetres(), isKingTide, isSpringTide,
                d.lunarTideType(), d.lunarPhase(), d.moonAtPerigee());
    }

    /**
     * Extracts low cloud values for up to 3 hours leading into the event hour.
     *
     * <p>Returns [hour-2, hour-1, eventHour] where available. If the event hour index
     * is near the start of the forecast array, returns fewer hours.
     *
     * @param h   the hourly forecast data
     * @param idx the event hour index
     * @return low cloud values (earliest first, last element is event hour)
     */
    static List<Integer> extractLowCloudTrend(OpenMeteoForecastResponse.Hourly h, int idx) {
        List<Integer> cloudLow = h.getCloudCoverLow();
        if (cloudLow == null || cloudLow.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, idx - 2);
        return cloudLow.subList(start, idx + 1);
    }

    /**
     * Extracts low cloud cover at a given hour index from a horizon forecast response.
     *
     * @param horizonForecast the cloud-only horizon forecast (nullable)
     * @param idx             the hourly index to extract
     * @return low cloud percentage, or null if unavailable
     */
    static Integer extractHorizonLowCloud(OpenMeteoForecastResponse horizonForecast, int idx) {
        if (horizonForecast == null || horizonForecast.getHourly() == null) {
            return null;
        }
        List<Integer> cloudLow = horizonForecast.getHourly().getCloudCoverLow();
        if (cloudLow == null || idx >= cloudLow.size()) {
            return null;
        }
        return cloudLow.get(idx);
    }

    /**
     * Diagnostic logging for clear-all-layers demotion check. Temporary — remove once root cause
     * is identified.
     */
    private void logClearAllLayersDiagnostic(String locationName, TargetType eventType,
            int lowCloud, int midCloud, int highCloud,
            Verdict before, Verdict after) {
        int threshold = BriefingVerdictEvaluator.CLEAR_ALL_LAYERS_MAX;
        if (before != Verdict.GO) {
            LOG.debug("[CLEAR-ALL-LAYERS] location={} {} low={} mid={} high={} "
                            + "fired=false verdict={}→{} reason=already_demoted",
                    locationName, eventType, lowCloud, midCloud, highCloud, before, after);
            return;
        }
        if (lowCloud >= threshold) {
            LOG.debug("[CLEAR-ALL-LAYERS] location={} {} low={} mid={} high={} "
                            + "fired=false verdict={}→{} reason=low_cloud_above_threshold",
                    locationName, eventType, lowCloud, midCloud, highCloud, before, after);
        } else if (midCloud >= threshold) {
            LOG.debug("[CLEAR-ALL-LAYERS] location={} {} low={} mid={} high={} "
                            + "fired=false verdict={}→{} reason=mid_cloud_above_threshold",
                    locationName, eventType, lowCloud, midCloud, highCloud, before, after);
        } else if (highCloud >= threshold) {
            LOG.debug("[CLEAR-ALL-LAYERS] location={} {} low={} mid={} high={} "
                            + "fired=false verdict={}→{} reason=high_cloud_above_threshold",
                    locationName, eventType, lowCloud, midCloud, highCloud, before, after);
        } else {
            LOG.debug("[CLEAR-ALL-LAYERS] location={} {} low={} mid={} high={} "
                            + "fired=true verdict={}→{}",
                    locationName, eventType, lowCloud, midCloud, highCloud, before, after);
        }
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
