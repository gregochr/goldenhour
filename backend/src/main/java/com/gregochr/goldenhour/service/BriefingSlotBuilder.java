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
    private final WoodlandVerdictEvaluator woodlandVerdictEvaluator;

    /**
     * Constructs a {@code BriefingSlotBuilder}.
     *
     * @param solarService      service for calculating sunrise/sunset times
     * @param locationService   service for location metadata (coastal checks)
     * @param tideFactDeriver   the single seam for deriving tide facts
     * @param verdictEvaluator  evaluator for slot verdicts and flag generation
     * @param woodlandVerdictEvaluator evaluator for canopy locations, whose polarity is inverted
     */
    public BriefingSlotBuilder(SolarService solarService, LocationService locationService,
            TideFactDeriver tideFactDeriver,
            BriefingVerdictEvaluator verdictEvaluator,
            WoodlandVerdictEvaluator woodlandVerdictEvaluator) {
        this.solarService = solarService;
        this.locationService = locationService;
        this.tideFactDeriver = tideFactDeriver;
        this.verdictEvaluator = verdictEvaluator;
        this.woodlandVerdictEvaluator = woodlandVerdictEvaluator;
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

        // A wood is judged by different rules, because the two subjects invert: flat overcast is a
        // stand-down for a sunset and the ideal under a canopy. Routing here rather than branching
        // inside BriefingVerdictEvaluator keeps one polarity per class — see
        // WoodlandVerdictEvaluator's class javadoc for the four rules that flip.
        if (loc.isWoodlandOnly()) {
            return buildWoodlandSlot(loc, solarTime, lowCloud, midCloud, highCloud, precip,
                    visibility, humidity, temp, apparentTemp, weatherCode, windSpeed);
        }

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
                tideResult.heightAboveP95(), tideResult.heightAboveSpringThreshold(),
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
                tideResult.heightAboveP95(), tideResult.heightAboveSpringThreshold(),
                tideResult.lunarTideType(), tideResult.lunarPhase(),
                tideResult.moonAtPerigee(), tideResult.nearestSolarOffsetMinutes(),
                tideResult.nearestExtremeKind(), tideResult.tideOnTheLight(),
                tideResult.nearestSolarOffsetPhrase());

        return new BriefingSlot(loc.getId(), loc.getName(), solarTime, verdict, weather,
                tideInfo, flags,
                standdownReason);
    }

    /**
     * Builds a slot for a canopy location, using {@link WoodlandVerdictEvaluator}.
     *
     * <p>Shares the {@link BriefingSlot} shape and the {@link Verdict} enum with the sky path — only
     * the rules and the flag wording differ, so everything downstream (roll-ups, the grid, the
     * drill-down) needs no knowledge of the fork. Tide data is not derived: a wood is not coastal,
     * and a tide fact on a woodland card would be noise.
     */
    private BriefingSlot buildWoodlandSlot(LocationEntity loc, LocalDateTime solarTime,
            int lowCloud, int midCloud, int highCloud, BigDecimal precip, int visibility,
            int humidity, Double temp, Double apparentTemp, Integer weatherCode,
            BigDecimal windSpeed) {
        WoodlandVerdictEvaluator.WoodlandMetrics metrics =
                new WoodlandVerdictEvaluator.WoodlandMetrics(
                        lowCloud, midCloud, visibility, precip, windSpeed);

        Verdict verdict = woodlandVerdictEvaluator.determineVerdict(metrics);
        List<String> flags = woodlandVerdictEvaluator.buildFlags(metrics);
        String standdownReason = verdict == Verdict.STANDDOWN
                ? woodlandVerdictEvaluator.deriveStanddownReason(metrics)
                : null;

        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                lowCloud, precip, visibility, humidity, temp, apparentTemp, weatherCode, windSpeed,
                midCloud, highCloud);

        return BriefingSlot.canopySlot(loc.getId(), loc.getName(), solarTime, verdict, weather,
                flags, standdownReason);
    }

    /**
     * Intermediate result of tide data calculation for a coastal location.
     *
     * @param nearestSolarOffsetMinutes signed minutes from the solar event to whichever tide
     *                                  extreme (high or low) lands nearest it — tide minus light,
     *                                  positive when the water comes after the sun — or null when
     *                                  no extreme of either kind was found near this event. See
     *                                  {@link #nearestTideEitherKind}
     * @param nearestExtremeKind        {@code "HW"} or {@code "LW"} for whichever extreme
     *                                  {@code nearestSolarOffsetMinutes} names, or null
     * @param tideOnTheLight            true when that nearest extreme falls inside
     *                                  {@link TideFactDeriver}'s dynamic tight alignment window for
     *                                  this location, date and event; null when no extreme was
     *                                  found. Deliberately not {@code tideAligned}, which tests the
     *                                  location's configured {@code TideType} preference — a
     *                                  different question (CLAUDE.md's tide-axis rule)
     * @param nearestSolarOffsetPhrase  the same fact in words, e.g. {@code "HW 19:52 · 36m before
     *                                  sunset"}, built from {@link TideWording} so the map tab never
     *                                  formats a tide clock time itself; null alongside the three
     *                                  fields above
     */
    record TideResult(String tideState, boolean tideAligned,
            LocalDateTime nearestHighTime, BigDecimal nearestHighHeight,
            boolean heightAboveP95, boolean heightAboveSpringThreshold,
            LunarTideType lunarTideType, String lunarPhase, Boolean moonAtPerigee,
            Integer nearestSolarOffsetMinutes, String nearestExtremeKind, Boolean tideOnTheLight,
            String nearestSolarOffsetPhrase) {

        static final TideResult NONE =
                new TideResult(null, false, null, null, false, false, null, null, null,
                        null, null, null, null);
    }

    /**
     * The tide extreme (of either kind) sitting closest to a solar event, paired with its signed
     * offset in minutes.
     *
     * @param kind           {@code "HW"} or {@code "LW"}
     * @param offsetMinutes  signed minutes from the event to this extreme (positive = after)
     * @param time           the extreme's own UTC time, kept for the earlier-wins tie-break
     */
    private record NearestTide(String kind, int offsetMinutes, LocalDateTime time) {
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
        boolean heightAboveP95 = false;
        boolean heightAboveSpringThreshold = false;
        if (d.tideState() == TideState.HIGH && d.nearestHighTideTime() != null
                && Math.abs(ChronoUnit.MINUTES.between(d.nearestHighTideTime(), solarTime))
                        <= TIDE_WINDOW_MINUTES) {
            heightAboveP95 = d.heightAboveP95();
            heightAboveSpringThreshold = d.heightAboveSpringThreshold();
        }

        // Map tab tide-alignment glyph/tiebreaker: the nearest extreme of EITHER kind vs the
        // light, type-blind on purpose — matching TideRunBuilder.waterInTheLight's shape. Never
        // tideAligned above, which is gated on this location's configured TideType PREFERENCE, a
        // different question (CLAUDE.md's tide-axis rule: "never OR the height test / preference
        // test into an astronomical one").
        Integer nearestOffsetMinutes = null;
        String nearestKind = null;
        Boolean tideOnTheLight = null;
        String nearestOffsetPhrase = null;
        NearestTide nearest = nearestTideEitherKind(
                d.nearestHighTideTime(), d.nearestLowTideTime(), solarTime);
        if (nearest != null) {
            nearestOffsetMinutes = nearest.offsetMinutes();
            nearestKind = nearest.kind();
            long tightWindowMinutes = tideFactDeriver.tightAlignmentWindowMinutes(
                    loc.getLat(), loc.getLon(), solarTime, eventType);
            tideOnTheLight = Math.abs(nearest.offsetMinutes()) <= tightWindowMinutes;
            String solarWord = eventType == TargetType.SUNRISE ? "sunrise" : "sunset";
            nearestOffsetPhrase = nearestKind + " "
                    + TideWording.clock(TideWording.londonMinutesOfDay(nearest.time()))
                    + " · " + TideWording.offsetPhrase(nearestOffsetMinutes, solarWord);
        }

        return new TideResult(d.tideState().name(), d.tideAligned(), d.nearestHighTideTime(),
                d.nextHighTideHeightMetres(), heightAboveP95, heightAboveSpringThreshold,
                d.lunarTideType(), d.lunarPhase(), d.moonAtPerigee(),
                nearestOffsetMinutes, nearestKind, tideOnTheLight, nearestOffsetPhrase);
    }

    /**
     * The extreme of EITHER kind sitting closest to a solar event.
     *
     * <p>Ties break toward the earlier water, not toward a type — the same rule
     * {@code TideRunBuilder.nearestSolar} uses, so two surfaces asking "which water is nearest"
     * cannot disagree on a tie.
     *
     * @param highTime  the nearest HIGH extreme to the event (within the ±12h search window
     *                  {@link TideService#buildTideData} already applied), or null if none found
     * @param lowTime   the nearest LOW extreme to the event, or null if none found
     * @param eventTime the solar event, UTC
     * @return the nearer of the two, or null when neither a high nor a low extreme was found
     */
    private static NearestTide nearestTideEitherKind(LocalDateTime highTime, LocalDateTime lowTime,
            LocalDateTime eventTime) {
        NearestTide high = highTime == null ? null
                : new NearestTide("HW",
                        (int) ChronoUnit.MINUTES.between(eventTime, highTime), highTime);
        NearestTide low = lowTime == null ? null
                : new NearestTide("LW",
                        (int) ChronoUnit.MINUTES.between(eventTime, lowTime), lowTime);
        if (high == null) {
            return low;
        }
        if (low == null) {
            return high;
        }
        long highAbs = Math.abs(high.offsetMinutes());
        long lowAbs = Math.abs(low.offsetMinutes());
        if (highAbs != lowAbs) {
            return highAbs < lowAbs ? high : low;
        }
        return high.time().isBefore(low.time()) ? high : low;
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
