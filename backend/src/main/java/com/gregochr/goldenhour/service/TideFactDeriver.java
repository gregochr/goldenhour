package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.TideData;
import com.gregochr.goldenhour.model.TideDerivation;
import com.gregochr.goldenhour.model.TideStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;

/**
 * The single seam for deriving tide facts for a coastal location at a solar event.
 *
 * <p>Before this component existed, two paths derived "the same" tide facts independently and could
 * drift: {@code ForecastDataAugmentor.buildTideSnapshot} (the scoring path) and
 * {@code BriefingSlotBuilder.calculateTideData} (the hot-topic / briefing path). Both bottomed out
 * in the same library calls ({@link TideService#deriveTideData}, {@link TideService#calculateTideAligned},
 * {@link TideService#getTideStats}, {@link LunarPhaseService#classifyTide} and friends) but assembled
 * them separately. This component performs that assembly <em>once</em>; both callers now adapt their
 * own presentation type from the single {@link TideDerivation} it returns and derive nothing
 * themselves.
 *
 * <p>The derivation is pure with respect to its inputs (a DB read plus deterministic lunar maths),
 * so it is safe to call from either the zero-Claude briefing pipeline (over all colour locations,
 * stand-down included) or the post-batch scoring pipeline (over survivors) at any time.
 */
@Component
public class TideFactDeriver {

    private static final Logger LOG = LoggerFactory.getLogger(TideFactDeriver.class);

    /**
     * Minutes by which the tight golden/blue-hour alignment window is extended beyond each edge to
     * form the widened window used for the scoring path's 3★ "imperfect but workable tide" band.
     */
    public static final long WIDENED_ALIGNMENT_EXTENSION_MINUTES = 60;

    private final TideService tideService;
    private final LunarPhaseService lunarPhaseService;
    private final SolarService solarService;

    /**
     * Constructs a {@code TideFactDeriver}.
     *
     * @param tideService       provides tide data, alignment, and historical statistics
     * @param lunarPhaseService provides lunar tide classification, moon phase, and perigee
     * @param solarService      provides the golden/blue-hour window used to size the tight
     *                          alignment window
     */
    public TideFactDeriver(TideService tideService, LunarPhaseService lunarPhaseService,
            SolarService solarService) {
        this.tideService = tideService;
        this.lunarPhaseService = lunarPhaseService;
        this.solarService = solarService;
    }

    /**
     * Derives the complete tide-fact set for a coastal location at a solar event, or empty when the
     * location is inland (no tide preference) or the tide cannot be derived (a data gap — no stored
     * extremes).
     *
     * <p>This is the one place {@link TideService#deriveDualWindowTideData} and
     * {@link LunarPhaseService#classifyTide} are called. The tight and widened alignment flags are
     * both derived from a single extremes fetch (same tide curve, two windows), so the scoring
     * path's 3★ widened band needs no second fetch. The statistical signals are raw and
     * <em>ungated</em>: each consumer applies its own gating (the briefing path gates the
     * king/spring flags on a high tide within ±90 minutes of the event; the scoring path leaves
     * them ungated).
     *
     * @param locationId the location primary key, or {@code null} for inland
     * @param eventTime  UTC time of the solar event
     * @param tideTypes  the location's tide preferences (empty/null if inland)
     * @param lat        observer latitude (for the golden/blue-hour window width)
     * @param lon        observer longitude (for the golden/blue-hour window width)
     * @param targetType SUNRISE or SUNSET
     * @return the derived tide facts, or empty when inland or the tide could not be derived
     */
    public Optional<TideDerivation> derive(Long locationId, LocalDateTime eventTime,
            Set<TideType> tideTypes, double lat, double lon, TargetType targetType) {
        boolean isCoastal = locationId != null && tideTypes != null && !tideTypes.isEmpty();
        if (!isCoastal) {
            return Optional.empty();
        }
        long tightWindowMinutes = tightAlignmentWindowMinutes(lat, lon, eventTime, targetType);
        long widenedWindowMinutes = tightWindowMinutes + WIDENED_ALIGNMENT_EXTENSION_MINUTES;
        Optional<TideService.DualWindowTideData> dualMaybe = tideService.deriveDualWindowTideData(
                locationId, eventTime, tightWindowMinutes, widenedWindowMinutes);
        if (dualMaybe.isEmpty()) {
            return Optional.empty();
        }
        TideData tideData = dualMaybe.get().tight();
        boolean tideAligned = tideService.calculateTideAligned(tideData, tideTypes);
        boolean widenedAligned = tideService.calculateTideAligned(dualMaybe.get().widened(), tideTypes);
        Double tideAlignmentQuality = tideAligned
                ? computeAlignmentQuality(locationId, eventTime, tideData, tideTypes, tightWindowMinutes)
                : null;
        if (tideAligned && tideAlignmentQuality == null) {
            // Should not occur — tideAligned already confirmed at least one want matches — but
            // the one avenue that COULD disagree is TideType.MID: it re-fetches extremes through
            // a second, time-separated query (TideService#nearestMidpointOffsetMinutes) rather
            // than reading the same tight-window TideData tideAligned was decided from, so a
            // tide refresh landing between the two calls is a genuine (if narrow) way for them to
            // part company. Logged rather than silently degrading C1's meanQuality sample, the
            // same "can't die silently" precedent InversionScoreCalculator sets for its own
            // gated-but-unexplained case.
            LOG.warn("tideAligned=true but tideAlignmentQuality computed null for locationId={} "
                    + "eventTime={} tideTypes={} — a MID want's second extremes fetch likely saw "
                    + "different rows than the first", locationId, eventTime, tideTypes);
        }

        // Lunar classification (deterministic, from moon phase + perigee).
        LocalDate eventDate = eventTime.toLocalDate();
        var lunarTideType = lunarPhaseService.classifyTide(eventDate);
        String lunarPhase = lunarPhaseService.getMoonPhase(eventDate);
        Boolean moonAtPerigee = lunarPhaseService.isMoonAtPerigee(eventDate);

        // Statistical size signals (empirical, from historical tide data). Carried as two
        // independent raw booleans so neither consumer's reconstruction is lossy.
        boolean heightAboveP95 = false;
        boolean heightAboveSpringThreshold = false;
        BigDecimal springTideThresholdMetres = null;
        BigDecimal avgRangeMetres = null;
        BigDecimal highTideHeight = tideData.nextHighTideHeightMetres();
        if (highTideHeight != null) {
            Optional<TideStats> statsMaybe = tideService.getTideStats(locationId);
            if (statsMaybe.isPresent()) {
                TideStats stats = statsMaybe.get();
                springTideThresholdMetres = stats.springTideThreshold();
                avgRangeMetres = stats.avgRangeMetres();
                heightAboveP95 = stats.p95HighMetres() != null
                        && highTideHeight.compareTo(stats.p95HighMetres()) > 0;
                heightAboveSpringThreshold = stats.springTideThreshold() != null
                        && highTideHeight.compareTo(stats.springTideThreshold()) > 0;
            }
        }

        return Optional.of(new TideDerivation(
                tideData.tideState(),
                tideData.nextHighTideTime(),
                tideData.nextHighTideHeightMetres(),
                tideData.nextLowTideTime(),
                tideData.nextLowTideHeightMetres(),
                tideAligned,
                widenedAligned,
                tideData.nearestHighTideTime(),
                tideData.nearestLowTideTime(),
                lunarTideType,
                lunarPhase,
                moonAtPerigee,
                heightAboveP95,
                heightAboveSpringThreshold,
                springTideThresholdMetres,
                avgRangeMetres,
                tideAlignmentQuality));
    }

    /**
     * How well-centred the water is in the wanted state at the light, on the same time axis
     * {@link TideService#calculateTideAligned} decided alignment on — 0.0 at the edge of the
     * tight alignment window, 1.0 dead centre. Only called once {@code tideAligned} is already
     * known true, so at least one wanted state is expected to yield a figure; when more than one
     * is aligned (a {@code {HIGH, LOW}} want, say), the best of them wins.
     *
     * <p>Deliberately not derived from {@code nearestSolarOffsetMinutes} (distance to the nearest
     * extreme of <em>either</em> kind, type-blind): that figure points the wrong way for a MID
     * want, where a better-centred match sits <em>farther</em> from either extreme, not nearer
     * one.
     *
     * @param locationId         the location primary key (for the MID case's midpoint lookup)
     * @param eventTime          UTC time of the solar event
     * @param tideData           the tight-window tide data alignment was decided on
     * @param tideTypes          the location's wanted tide states
     * @param tightWindowMinutes the tight alignment window half-width, the quality figure's own
     *                           denominator
     * @return the best quality across every aligned wanted state, or {@code null} if none yielded
     *     one (should not occur when {@code tideAligned} is true, but fails soft rather than
     *     asserting it)
     */
    private Double computeAlignmentQuality(Long locationId, LocalDateTime eventTime,
            TideData tideData, Set<TideType> tideTypes, long tightWindowMinutes) {
        Double best = null;
        for (TideType want : tideTypes) {
            Long offsetMinutes = offsetMinutesForWant(locationId, eventTime, tideData, want);
            if (offsetMinutes == null) {
                continue;
            }
            double quality = qualityFromOffset(offsetMinutes, tightWindowMinutes);
            if (best == null || quality > best) {
                best = quality;
            }
        }
        return best;
    }

    /**
     * Signed minutes from the light to the extreme (or, for MID, the bracketing midpoint) that
     * satisfies one wanted tide state — or {@code null} when that particular want is not itself
     * the one behind this slot's alignment.
     */
    private Long offsetMinutesForWant(Long locationId, LocalDateTime eventTime, TideData tideData,
            TideType want) {
        return switch (want) {
            case HIGH -> tideData.tideState() == TideState.HIGH
                    ? minutesBetween(eventTime, tideData.nearestHighTideTime()) : null;
            case LOW -> tideData.tideState() == TideState.LOW
                    ? minutesBetween(eventTime, tideData.nearestLowTideTime()) : null;
            case MID -> tideData.nearMidPoint()
                    ? tideService.nearestMidpointOffsetMinutes(locationId, eventTime).orElse(null)
                    : null;
        };
    }

    private static Long minutesBetween(LocalDateTime eventTime, LocalDateTime extremeTime) {
        return extremeTime == null ? null : ChronoUnit.MINUTES.between(eventTime, extremeTime);
    }

    /**
     * {@code 1 − |offsetMinutes| / tightWindowMinutes}, clamped to [0, 1] — 1.0 dead centre on the
     * light, ≈0 at the tight window's edge.
     */
    private static double qualityFromOffset(long offsetMinutes, long tightWindowMinutes) {
        if (tightWindowMinutes <= 0) {
            return 1.0;
        }
        double raw = 1.0 - (double) Math.abs(offsetMinutes) / tightWindowMinutes;
        return Math.max(0.0, Math.min(1.0, raw));
    }

    /**
     * Computes the tight alignment window half-width in minutes: half the blue+golden hour span
     * around the solar event. Exposed so the scoring path's widened-alignment overlay can size its
     * wider window off the same single formula.
     *
     * @param lat        observer latitude
     * @param lon        observer longitude
     * @param eventTime  UTC time of the solar event
     * @param targetType SUNRISE or SUNSET
     * @return the tight alignment window half-width in minutes
     */
    public long tightAlignmentWindowMinutes(double lat, double lon, LocalDateTime eventTime,
            TargetType targetType) {
        boolean isSunrise = targetType == TargetType.SUNRISE;
        SolarService.SolarWindow window = solarService.goldenBlueWindow(
                lat, lon, eventTime.toLocalDate(), isSunrise);
        return Duration.between(
                isSunrise ? window.blueHourStart() : window.goldenHourStart(),
                isSunrise ? window.goldenHourEnd() : window.blueHourEnd()
        ).toMinutes() / 2;
    }
}
