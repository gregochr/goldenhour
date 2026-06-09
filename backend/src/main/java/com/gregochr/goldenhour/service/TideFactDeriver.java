package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.TideData;
import com.gregochr.goldenhour.model.TideDerivation;
import com.gregochr.goldenhour.model.TideStats;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

        // Lunar classification (deterministic, from moon phase + perigee).
        LocalDate eventDate = eventTime.toLocalDate();
        var lunarTideType = lunarPhaseService.classifyTide(eventDate);
        String lunarPhase = lunarPhaseService.getMoonPhase(eventDate);
        Boolean moonAtPerigee = lunarPhaseService.isMoonAtPerigee(eventDate);

        // Statistical size signals (empirical, from historical tide data). Carried as two
        // independent raw booleans so neither consumer's reconstruction is lossy.
        boolean heightAboveP95 = false;
        boolean heightAboveSpringThreshold = false;
        BigDecimal highTideHeight = tideData.nextHighTideHeightMetres();
        if (highTideHeight != null) {
            Optional<TideStats> statsMaybe = tideService.getTideStats(locationId);
            if (statsMaybe.isPresent()) {
                TideStats stats = statsMaybe.get();
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
                heightAboveSpringThreshold));
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
