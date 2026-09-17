package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingWindowTide;
import com.gregochr.goldenhour.model.SeaState;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Derives the per-window tide rollup the window-first Plan tab's tide row renders: state and
 * direction, the nearest extreme with its offset from the window, the day's range against the
 * location's own mean, the sea state, the day's tide shape, and the coastal location all of that is
 * measured at.
 *
 * <h2>A component, so the projector stays dependency-free</h2>
 *
 * <p>{@code PlanWindowProjector} is a static utility over data already on the response. Tide data
 * needs three repositories, so it is assembled here and handed to the projector as a map, exactly as
 * the badges are — a repository injected into the projector would make every future window field
 * look like a licence for another one.
 *
 * <h2>Serve time, with no carrier</h2>
 *
 * <p>Called as part of {@code getCachedBriefingForApi} and never at build time. Everything read here
 * is a DB-only read: {@code tide_extreme} is refreshed weekly and reaches months ahead, and
 * {@code marine_wave} (V123) exists precisely so refresh-time-fetched marine data survives a
 * restart. Nothing is written back to {@code daily_briefing_cache}, so there is no carrier to keep
 * in step and no migration.
 *
 * <h2>One location for the whole forecast</h2>
 *
 * <p>The representative is chosen once across every date in the briefing, not per window, for the
 * reason a tide run picks one for the whole run: choosing per window lets the curve jump coastlines
 * between Tuesday and Thursday, so a reader comparing two windows compares two places. See
 * {@link TideRepresentativeSelector}, whose selection rule this shares and whose warn-once state it
 * deliberately does not.
 *
 * <h2>Never synthesise</h2>
 *
 * <p>No coastal roster, no stored extremes, or no resolvable representative means an empty map and
 * no tide row anywhere — the row falls back to {@code BriefingSlot.tide}'s per-location fact line. A
 * single date with no day carrying both a high and a low water loses its own windows and no others.
 * A missing sea state is <em>not</em> such a case: waves reach only T+4 while tides now reach months
 * ahead, so that field degrades on its own and the rest of the rollup stands.
 *
 * <p>The trace is the one place with a narrower rule, and it has always had one: {@link
 * TideCurveCalculator#bracket} extends the series past both ends of the day and {@link
 * TideCurveCalculator#fillInteriorGaps} closes a gap left by a lost extreme, because a curve drawn
 * flat across water it cannot see makes a claim of its own. Both are shape and only shape — no
 * stated number in the rollup reads the extended series.
 *
 * <h2>The curve maths lives in {@code TideCurveCalculator}</h2>
 *
 * <p>The cosine interpolation, the bracket/gap-fill shape corrections and the day-clock arithmetic
 * are lifted into {@link TideCurveCalculator}, a package-private class holding what used to be this
 * class's own private statics. The per-slot tide facts on {@code BriefingSlot.TideInfo} need the
 * identical curve, so a second copy there would be exactly the drift this class's own {@code
 * TideFactDeriver} dependency already exists to prevent for the alignment window. This class still
 * owns the figures {@link TideCurveCalculator} must never see: the day's real, unsynthesised
 * extremes ({@link #pointsOn}, feeding {@link #rangeOn} and {@link #meanRangeOn}) — see their own
 * javadoc for why that split is load-bearing.
 */
@Component
public class WindowTideRollupBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(WindowTideRollupBuilder.class);

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private final LocationRepository locationRepository;
    private final TideExtremeRepository tideExtremeRepository;
    private final MarineWaveRepository marineWaveRepository;
    private final TideService tideService;
    private final SolarService solarService;
    private final TideFactDeriver tideFactDeriver;

    /**
     * This builder's own representative selector.
     *
     * <p>Its warn-once state must not be shared with {@code TideRunBuilder}: a misconfigured anchor
     * would then warn for whichever of the two ran first and stay silent for the other, which is the
     * failure the warning exists to catch.
     */
    private final TideRepresentativeSelector representativeSelector;

    /**
     * Constructs a {@code WindowTideRollupBuilder}.
     *
     * @param locationRepository    supplies the enabled coastal roster
     * @param tideExtremeRepository stored tide extrema — a DB-only read, never an API call
     * @param marineWaveRepository  the shared sea-state carrier
     * @param tideService           supplies the representative's historical range statistics and the
     *                              tide-state rule the per-slot tide facts already use
     * @param solarService          supplies the representative's own sunrise and sunset
     * @param tideFactDeriver       sizes the tide-state window exactly as the per-slot tide facts
     *                              do, so the row and the drill-down beneath it cannot call the
     *                              same water HIGH and MID
     * @param preferredAnchor       location name to draw for when present and drawable; blank
     *                              restores pure biggest-range selection. Deliberately the same
     *                              property the tide run reads, so a configured anchor makes the two
     *                              surfaces name the same place
     */
    public WindowTideRollupBuilder(LocationRepository locationRepository,
            TideExtremeRepository tideExtremeRepository,
            MarineWaveRepository marineWaveRepository,
            TideService tideService,
            SolarService solarService,
            TideFactDeriver tideFactDeriver,
            @Value("${photocast.tide-run.preferred-anchor:}") String preferredAnchor) {
        this.locationRepository = locationRepository;
        this.tideExtremeRepository = tideExtremeRepository;
        this.marineWaveRepository = marineWaveRepository;
        this.tideService = tideService;
        this.solarService = solarService;
        this.tideFactDeriver = tideFactDeriver;
        this.representativeSelector = new TideRepresentativeSelector(preferredAnchor);
    }

    /**
     * Builds the tide rollup for every window in the briefing.
     *
     * <p><b>Fail-soft by construction.</b> This runs on the serve path of the app's busiest
     * endpoint, and it produces one decorative row. A repository or solar failure degrades the row
     * to its fallback rather than failing the whole briefing, which is the same trade
     * {@code getCachedBriefing} already makes for the live aurora overlay.
     *
     * @param days the served briefing's days; may be null or empty
     * @return a rollup per window, keyed by date and solar event — empty when none can be derived,
     *         and missing individual keys for any window whose date has no drawable tide
     */
    public Map<PlanWindowProjector.WindowKey, BriefingWindowTide> build(List<BriefingDay> days) {
        if (days == null || days.isEmpty()) {
            return Map.of();
        }
        try {
            return buildOrThrow(days);
        } catch (RuntimeException e) {
            // Logged with the throwable, not just its message: the row degrades silently by
            // design, so the log is the only place the failure is ever visible.
            LOG.warn("Window tide rollup failed — the tide row falls back to per-slot facts", e);
            return Map.of();
        }
    }

    private Map<PlanWindowProjector.WindowKey, BriefingWindowTide> buildOrThrow(
            List<BriefingDay> days) {
        List<LocalDate> dates = days.stream()
                .map(BriefingDay::date).filter(Objects::nonNull).distinct().sorted().toList();
        if (dates.isEmpty()) {
            return Map.of();
        }
        List<LocationEntity> coastal = locationRepository.findCoastalLocations();
        if (coastal.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<TideExtremeEntity>> byLocation = fetchExtremes(coastal, dates);
        if (byLocation.isEmpty()) {
            return Map.of();
        }
        LocationEntity representative = representativeSelector.select(
                coastal, byLocation, dates, WindowTideRollupBuilder::rangeOn);
        if (representative == null) {
            return Map.of();
        }

        List<TideExtremeEntity> extremes = byLocation.get(representative.getId());
        BigDecimal avgRange = tideService.getTideStats(representative.getId())
                .map(TideStats::avgRangeMetres).orElse(null);

        Map<PlanWindowProjector.WindowKey, BriefingWindowTide> rollups = new HashMap<>();
        for (BriefingDay day : days) {
            if (day.date() == null) {
                continue;
            }
            for (BriefingEventSummary summary : day.eventSummaries()) {
                BriefingWindowTide rollup =
                        rollup(representative, extremes, avgRange, day.date(), summary.targetType());
                if (rollup != null) {
                    rollups.put(new PlanWindowProjector.WindowKey(day.date(), summary.targetType()),
                            rollup);
                }
            }
        }
        return rollups;
    }

    /**
     * Fetches every coastal location's extremes across the briefing, plus a day either side.
     *
     * <p>The margin is what lets the shape be interpolated rather than extrapolated at both ends of
     * a day: without an extreme before midnight the trace has nothing to rise or fall from. A local
     * day's extrema can also sit either side of the UTC date boundary, so the window is the local
     * span converted to UTC rather than the bare dates.
     */
    private Map<Long, List<TideExtremeEntity>> fetchExtremes(List<LocationEntity> coastal,
            List<LocalDate> dates) {
        List<Long> ids = coastal.stream()
                .map(LocationEntity::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        LocalDateTime from = dates.get(0).minusDays(1).atStartOfDay(LONDON)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime to = dates.get(dates.size() - 1).plusDays(2).atStartOfDay(LONDON)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        Map<Long, List<TideExtremeEntity>> byLocation = new LinkedHashMap<>();
        for (TideExtremeEntity extreme : tideExtremeRepository
                .findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(ids, from, to)) {
            byLocation.computeIfAbsent(extreme.getLocationId(), k -> new ArrayList<>()).add(extreme);
        }
        return byLocation;
    }

    /**
     * This builder's drawability and range probe, handed to the shared representative selector.
     *
     * <p>The window row needs both a high and a low water in the local day, for the same reason the
     * tide run does: without both there is no range to state and no shape to draw. Supplied as a
     * probe rather than shared so a change to one surface's definition of "drawable" cannot silently
     * re-select the other's representative.
     *
     * @param extremes one location's stored extremes, or null when it has none
     * @param date     the local day to measure
     * @return the day's range in metres, or empty when the day cannot be drawn
     */
    private static OptionalDouble rangeOn(List<TideExtremeEntity> extremes, LocalDate date) {
        List<TideCurveCalculator.Point> points = pointsOn(extremes, date);
        double high = points.stream().filter(p -> p.high())
                .mapToDouble(TideCurveCalculator.Point::height).max().orElse(Double.NaN);
        double low = points.stream().filter(p -> !p.high())
                .mapToDouble(TideCurveCalculator.Point::height).min().orElse(Double.NaN);
        return Double.isNaN(high) || Double.isNaN(low)
                ? OptionalDouble.empty() : OptionalDouble.of(high - low);
    }

    /**
     * The rollup for one window, or null when the representative has no drawable day on that date.
     *
     * <p>The solar event is the <em>representative's own</em> sunrise or sunset, never the window's
     * earliest-slot time: every figure in the row has to describe the one place the row names, or
     * the offset would measure one coastline's water against another's light.
     */
    private BriefingWindowTide rollup(LocationEntity location, List<TideExtremeEntity> extremes,
            BigDecimal avgRange, LocalDate date, TargetType targetType) {
        OptionalDouble range = rangeOn(extremes, date);
        if (range.isEmpty()) {
            return null;
        }
        boolean sunrise = targetType == TargetType.SUNRISE;
        LocalDateTime sunriseUtc = solarService.sunriseUtc(location.getLat(), location.getLon(), date);
        LocalDateTime sunsetUtc = solarService.sunsetUtc(location.getLat(), location.getLon(), date);
        LocalDateTime eventUtc = sunrise ? sunriseUtc : sunsetUtc;
        int eventMinutes = TideCurveCalculator.clockMinutesFrom(eventUtc, date);

        TideExtremeEntity nearest = nearestExtreme(extremes, eventUtc);
        if (nearest == null) {
            return null;
        }
        List<TideCurveCalculator.Point> series = TideCurveCalculator.seriesAround(extremes, date);
        TideCurveCalculator.Shape shape = TideCurveCalculator.shape(series);
        // Computed once: read for windowLevel below and for heightAtWindow in the constructor —
        // heightAt is a pure function of series and eventMinutes, so a second call would be the
        // exact duplicate-of-the-lift this class's own javadoc argues against.
        double heightAtEvent = TideCurveCalculator.heightAt(series, eventMinutes);

        // The SAME window the per-slot tide facts are classified at — half the blue+golden span at
        // this location, on this date, for this event. A fixed +/-60 minutes would be a second rule:
        // at 54.5N around the equinox the real half-width is ~41 minutes, so a high water 50 minutes
        // off sunset would read HIGH in this row and MID in the drill-down slot directly beneath it,
        // for one location on one evening.
        long stateWindowMinutes = tideFactDeriver.tightAlignmentWindowMinutes(
                location.getLat(), location.getLon(), eventUtc, targetType);

        return new BriefingWindowTide(
                location.getName(),
                tideService.classifyTideState(extremes, eventUtc, stateWindowMinutes),
                TideCurveCalculator.directionAt(series, eventMinutes),
                nearest.getType() == TideExtremeType.HIGH ? "HW" : "LW",
                TideWording.clock(TideCurveCalculator.clockMinutesFrom(nearest.getEventTime(), date)),
                TideWording.offsetPhrase(
                        ChronoUnit.MINUTES.between(eventUtc, nearest.getEventTime()),
                        sunrise ? "sunrise" : "sunset"),
                TideWording.metres(range.getAsDouble()),
                rangeAnomaly(meanRangeOn(extremes, date), avgRange),
                seas(location.getId(), date, targetType),
                shape.curve(),
                TideCurveCalculator.positionOf(eventMinutes),
                shape.levelOf(heightAtEvent),
                positionOf(sunriseUtc, date),
                positionOf(sunsetUtc, date),
                extremesOn(extremes, date),
                TideWording.metres(heightAtEvent));
    }

    /**
     * Where a solar event falls on {@code date}'s local clock axis, 0.0 at midnight to 1.0 at the
     * next — the same fraction {@link #rollup} computes for {@code windowPosition}, but for a
     * solar event that may not be the one this window itself is.
     *
     * @param solarUtc the event's UTC instant, or null when {@link SolarService} reports none for
     *                 that day and location — its contract carries no non-null guarantee, and
     *                 {@code NlcTwilightWindowCalculator} already treats the identical call as
     *                 nullable for the same reason. Not observed from the vendored solar-utils
     *                 implementation even at a genuine polar day, which returns a degenerate
     *                 midnight instant rather than null — this guards the declared contract, not
     *                 a behaviour seen today
     * @param date     the local day to place it on
     * @return the 0–1 position, or null when {@code solarUtc} is null
     */
    private static Double positionOf(LocalDateTime solarUtc, LocalDate date) {
        if (solarUtc == null) {
            return null;
        }
        return TideCurveCalculator.positionOf(TideCurveCalculator.clockMinutesFrom(solarUtc, date));
    }

    /**
     * Every extreme in the representative's local day, positioned on the same axis
     * {@link #positionOf} and {@code windowPosition} use.
     *
     * <p>Built from {@link #pointsOn}, the same real-extremes-only list {@code range} and
     * {@code rangeAnomaly} already read — never {@link TideCurveCalculator#seriesAround}'s
     * bracketed or gap-filled series, whose bookends and implied troughs are shape only and name
     * no water actually measured.
     *
     * @param extremes one location's stored extremes
     * @param date     the local day to list
     * @return the day's extremes, ascending
     */
    private static List<BriefingWindowTide.Extreme> extremesOn(List<TideExtremeEntity> extremes,
            LocalDate date) {
        return pointsOn(extremes, date).stream()
                .map(p -> new BriefingWindowTide.Extreme(
                        p.high() ? "HW" : "LW",
                        TideCurveCalculator.positionOf(p.minutes()),
                        TideWording.clock(p.minutes())))
                .toList();
    }

    /**
     * The nearest extreme of <em>either</em> kind to the window.
     *
     * <p>Either kind, deliberately: the row's job is to name the water closest to the light, and
     * restricting it to one kind would state a low water five hours away on a morning when high
     * water lands on sunrise — the exact defect fixed in the tide run's verdict (#402).
     */
    private static TideExtremeEntity nearestExtreme(List<TideExtremeEntity> extremes,
            LocalDateTime eventUtc) {
        return extremes.stream()
                .filter(e -> e.getHeightMetres() != null)
                .min(Comparator.comparingLong(
                        e -> Math.abs(ChronoUnit.MINUTES.between(eventUtc, e.getEventTime()))))
                .orElse(null);
    }

    /**
     * The sea state for this window's own event.
     *
     * <p>No fallback to the day's other event, unlike the tide run's: a run describes a whole day,
     * while this row describes one window, and borrowing the sunset sample for a sunrise row would
     * state a sea the reader will not be standing in. Null is the correct answer past T+4, where
     * {@code marine_wave} stops.
     */
    private String seas(Long locationId, LocalDate date, TargetType targetType) {
        return marineWaveRepository
                .findByLocation_IdAndEvaluationDateAndEventType(locationId, date, targetType)
                .map(MarineWaveEntity::getSignificantWaveHeightMetres)
                .map(hs -> TideWording.metres(hs) + " · " + SeaState.fromHs(hs).label())
                .orElse(null);
    }

    /**
     * How the day's range compares with the location's own mean, in words.
     *
     * <p><b>Compared like with like, which is why this is not the headline range.</b> The stated
     * {@code range} is the day's biggest swing — {@code max(highs) - min(lows)} — because that is
     * the number a photographer acts on. The baseline it would naively be compared against is
     * {@code AVG(every high) - AVG(every low)}, a <em>mean</em>. Subtracting a mean from an extreme
     * is biased positive by the day's own diurnal inequality, and the bias is systematic rather
     * than noise: a textbook-average day at the fixture coast comes out +0.25 m, five times the
     * {@value TideWording#MIN_ANOMALY_METRES} m display threshold, so "about average" would be
     * unreachable and every window would read "above an average tide". This row renders on every
     * window of every day, so that bias would be the normal case, not an edge one. The comparison
     * therefore uses the day's <em>mean</em> range against the mean baseline; the two figures in
     * the row measure different things on purpose.
     *
     * <p>{@code TideRunBuilder} and {@code CoastalTideFactsBuilder} still use the extreme-minus-mean
     * form. Deliberately left alone: both fire only on spring and king days, where the day genuinely
     * is above average and the bias is swamped, and changing them is a change to a shipped surface
     * rather than to this one.
     *
     * <p>Null and {@code "about average"} are different statements: null means there was no
     * historical baseline, or no derivable mean range, to compare against — which the row must not
     * render as "average".
     */
    private static String rangeAnomaly(OptionalDouble meanRange, BigDecimal avgRangeMetres) {
        if (avgRangeMetres == null || meanRange.isEmpty()) {
            return null;
        }
        double anomaly = meanRange.getAsDouble() - avgRangeMetres.doubleValue();
        if (Math.abs(anomaly) < TideWording.MIN_ANOMALY_METRES) {
            return "about average";
        }
        return TideWording.metres(Math.abs(anomaly))
                + (anomaly > 0 ? " above an average tide" : " below an average tide");
    }

    /**
     * The day's <em>mean</em> range — the average high minus the average low — or empty when the
     * day carries no high or no low.
     *
     * <p>Exists only to be compared against {@code TideStats.avgRangeMetres}, which is built the
     * same way over the whole history. Never displayed: see {@link #rangeAnomaly}.
     *
     * @param extremes one location's stored extremes
     * @param date     the local day to measure
     * @return the day's mean range in metres, or empty
     */
    private static OptionalDouble meanRangeOn(List<TideExtremeEntity> extremes, LocalDate date) {
        List<TideCurveCalculator.Point> points = pointsOn(extremes, date);
        OptionalDouble high = points.stream().filter(TideCurveCalculator.Point::high)
                .mapToDouble(TideCurveCalculator.Point::height).average();
        OptionalDouble low = points.stream().filter(p -> !p.high())
                .mapToDouble(TideCurveCalculator.Point::height).average();
        return high.isEmpty() || low.isEmpty()
                ? OptionalDouble.empty()
                : OptionalDouble.of(high.getAsDouble() - low.getAsDouble());
    }

    /**
     * The extremes of one local day, ascending, dropping any with no stored height.
     *
     * <p><b>Real points only, never {@link TideCurveCalculator}'s bracketed or gap-filled ones.</b>
     * This feeds the day's <em>stated</em> range and mean — see {@link #rangeOn} and {@link
     * #meanRangeOn} — and a stated figure may never inherit a synthesised point the way the trace
     * does; see {@link TideCurveCalculator#fillInteriorGaps}'s own javadoc for why that split is
     * deliberate.
     */
    private static List<TideCurveCalculator.Point> pointsOn(List<TideExtremeEntity> extremes,
            LocalDate date) {
        if (extremes == null) {
            return List.of();
        }
        List<TideCurveCalculator.Point> points = new ArrayList<>();
        for (TideExtremeEntity extreme : extremes) {
            if (extreme.getHeightMetres() == null
                    || !TideCurveCalculator.localDate(extreme.getEventTime()).equals(date)) {
                continue;
            }
            points.add(TideCurveCalculator.point(extreme, date));
        }
        points.sort(Comparator.comparingInt(TideCurveCalculator.Point::minutes));
        return points;
    }
}
