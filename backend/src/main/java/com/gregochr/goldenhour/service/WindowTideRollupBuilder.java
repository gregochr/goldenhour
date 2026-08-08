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
 */
@Component
public class WindowTideRollupBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(WindowTideRollupBuilder.class);

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static final int MINUTES_PER_DAY = 1440;

    /**
     * Interval between samples on the day's tide shape, in minutes.
     *
     * <p>Thirty minutes gives 49 points across the day — about one sample every two pixels on the
     * design's 104px sparkline, which is smoother than the trace can show. The full-width tide-run
     * chart samples far finer because it is ten times as wide.
     */
    private static final int SAMPLE_MINUTES = 30;

    /**
     * A semidiurnal tidal cycle, in minutes (12h25m).
     *
     * <p>Only used to extend the series half a cycle past its ends when the stored extremes do not
     * bracket the whole day. Without it the curve flat-lines from midnight to the first extreme and
     * reads as slack water that is not there — the same correction {@code TideRunRow} makes, with
     * the same constant. In production the fetch reaches a day either side, so this is a data-edge
     * fallback rather than the normal path.
     */
    private static final int CYCLE_MINUTES = 745;

    /** Below this span the day has no shape to normalise, and the trace is drawn flat. */
    private static final double FLAT_SPAN_METRES = 1e-9;

    /** Where a flat trace sits — mid-height, so it reads as "no shape" rather than as low water. */
    private static final double FLAT_LEVEL = 0.5;

    /** Three decimals of a 24px trace is a fortieth of a pixel; more is payload, not detail. */
    private static final int CURVE_SCALE = 1000;

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
        List<Point> points = pointsOn(extremes, date);
        double high = points.stream().filter(p -> p.high())
                .mapToDouble(Point::height).max().orElse(Double.NaN);
        double low = points.stream().filter(p -> !p.high())
                .mapToDouble(Point::height).min().orElse(Double.NaN);
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
        LocalDateTime eventUtc = sunrise
                ? solarService.sunriseUtc(location.getLat(), location.getLon(), date)
                : solarService.sunsetUtc(location.getLat(), location.getLon(), date);
        int eventMinutes = clockMinutesFrom(eventUtc, date);

        TideExtremeEntity nearest = nearestExtreme(extremes, eventUtc);
        if (nearest == null) {
            return null;
        }
        List<Point> series = seriesAround(extremes, date);
        Shape shape = shape(series);

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
                directionAt(series, eventMinutes),
                nearest.getType() == TideExtremeType.HIGH ? "HW" : "LW",
                TideWording.clock(clockMinutesFrom(nearest.getEventTime(), date)),
                TideWording.offsetPhrase(
                        ChronoUnit.MINUTES.between(eventUtc, nearest.getEventTime()),
                        sunrise ? "sunrise" : "sunset"),
                TideWording.metres(range.getAsDouble()),
                rangeAnomaly(meanRangeOn(extremes, date), avgRange),
                seas(location.getId(), date, targetType),
                shape.curve(),
                round(Math.min(1.0, Math.max(0.0, (double) eventMinutes / MINUTES_PER_DAY))),
                shape.levelOf(heightAt(series, eventMinutes)));
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
        List<Point> points = pointsOn(extremes, date);
        OptionalDouble high = points.stream().filter(Point::high)
                .mapToDouble(Point::height).average();
        OptionalDouble low = points.stream().filter(p -> !p.high())
                .mapToDouble(Point::height).average();
        return high.isEmpty() || low.isEmpty()
                ? OptionalDouble.empty()
                : OptionalDouble.of(high.getAsDouble() - low.getAsDouble());
    }

    /** One extreme, as minutes from the local midnight of the day being described. */
    private record Point(boolean high, int minutes, double height) {
    }

    /** The extremes of one local day, ascending, dropping any with no stored height. */
    private static List<Point> pointsOn(List<TideExtremeEntity> extremes, LocalDate date) {
        if (extremes == null) {
            return List.of();
        }
        List<Point> points = new ArrayList<>();
        for (TideExtremeEntity extreme : extremes) {
            if (extreme.getHeightMetres() == null || !localDate(extreme).equals(date)) {
                continue;
            }
            points.add(point(extreme, date));
        }
        points.sort(Comparator.comparingInt(Point::minutes));
        return points;
    }

    /**
     * Every stored extreme within a day either side, as minutes from this day's local midnight.
     *
     * <p>Wider than {@link #pointsOn} because the shape needs something to interpolate towards
     * before the day's first extreme and after its last.
     */
    private static List<Point> seriesAround(List<TideExtremeEntity> extremes, LocalDate date) {
        List<Point> points = new ArrayList<>();
        for (TideExtremeEntity extreme : extremes) {
            LocalDate local = localDate(extreme);
            if (extreme.getHeightMetres() == null
                    || local.isBefore(date.minusDays(1)) || local.isAfter(date.plusDays(1))) {
                continue;
            }
            points.add(point(extreme, date));
        }
        points.sort(Comparator.comparingInt(Point::minutes));
        return bracket(points);
    }

    /**
     * Extends the series half a cycle past each end when it does not already span the whole day.
     *
     * <p>The bookend takes the opposite kind and the height of the nearest opposite-kind extreme it
     * can see, falling back to a mirror of its neighbour when the series holds only one kind. It
     * exists so a day whose stored extremes start at 05:00 does not draw five hours of slack water;
     * it is not a claim about water that was never measured, and it only ever affects the trace's
     * shape, never a stated number.
     */
    private static List<Point> bracket(List<Point> points) {
        if (points.isEmpty()) {
            return points;
        }
        List<Point> series = new ArrayList<>(points);
        Point first = series.get(0);
        if (first.minutes() > 0) {
            series.add(0, new Point(!first.high(), first.minutes() - CYCLE_MINUTES / 2,
                    counterpartHeight(points, first)));
        }
        Point last = series.get(series.size() - 1);
        if (last.minutes() < MINUTES_PER_DAY) {
            series.add(new Point(!last.high(), last.minutes() + CYCLE_MINUTES / 2,
                    counterpartHeight(points, last)));
        }
        return series;
    }

    /** The height of the nearest opposite-kind extreme, or a mirror of {@code anchor}'s own. */
    private static double counterpartHeight(List<Point> points, Point anchor) {
        return points.stream()
                .filter(p -> p.high() != anchor.high())
                .min(Comparator.comparingInt(p -> Math.abs(p.minutes() - anchor.minutes())))
                .map(Point::height)
                .orElse(anchor.height());
    }

    /**
     * The day's trace and the scale it was normalised on.
     *
     * <p>One object rather than two derivations because the window's mark must sit <em>on</em> the
     * line: normalising the mark against a separately recomputed span is how a mark comes to float
     * a pixel off its own curve.
     *
     * @param curve the normalised samples
     * @param min   the lowest sampled height, in metres
     * @param span  the sampled height range, in metres
     */
    private record Shape(List<Double> curve, double min, double span) {

        /**
         * Places a raw height on the same 0–1 scale the curve was normalised to.
         *
         * <p>Clamped, because the scale is derived from the samples and the window's own instant is
         * almost never one of them: a peak falling between two samples is genuinely higher than
         * anything sampled, and would otherwise put the mark just outside the 0–1 range the payload
         * documents. The error is under half a percent of the day's range — sub-pixel on the trace
         * — so clamping loses nothing a reader could see.
         */
        double levelOf(double height) {
            if (span < FLAT_SPAN_METRES) {
                return FLAT_LEVEL;
            }
            return round(Math.min(1.0, Math.max(0.0, (height - min) / span)));
        }
    }

    /**
     * The day's tide shape: cosine-interpolated heights sampled every {@link #SAMPLE_MINUTES},
     * normalised to 0–1 across the sampled series' own span.
     *
     * <p>Cosine because a real tide is very close to sinusoidal between high and low water, so this
     * is a fair rendering of the shape rather than a decorative squiggle — the same interpolation
     * {@code TideRunRow} draws. Normalised against the samples rather than against the day's stated
     * range so the trace cannot clip: the series reaches a day either side, so a sample near
     * midnight can legitimately sit outside the day's own extremes.
     */
    private static Shape shape(List<Point> series) {
        List<Double> heights = new ArrayList<>();
        for (int m = 0; m <= MINUTES_PER_DAY; m += SAMPLE_MINUTES) {
            heights.add(heightAt(series, m));
        }
        double min = heights.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double span = heights.stream().mapToDouble(Double::doubleValue).max().orElseThrow() - min;
        Shape shape = new Shape(List.of(), min, span);
        return new Shape(heights.stream().map(shape::levelOf).toList(), min, span);
    }

    /**
     * Cosine-interpolated height at one minute offset, clamped to the series' ends.
     *
     * <p>Called only for a series {@link #bracket} has returned non-empty, which is therefore at
     * least two points long: a lone point always gains a bookend.
     */
    private static double heightAt(List<Point> series, int minutes) {
        int i = 0;
        while (i < series.size() - 2 && series.get(i + 1).minutes() < minutes) {
            i++;
        }
        Point a = series.get(i);
        Point b = series.get(i + 1);
        int span = b.minutes() - a.minutes();
        if (span <= 0) {
            return b.height();
        }
        double f = Math.min(1.0, Math.max(0.0, (minutes - (double) a.minutes()) / span));
        double eased = (1 - Math.cos(Math.PI * f)) / 2;
        return a.height() + (b.height() - a.height()) * eased;
    }

    /**
     * Whether the water is rising or falling at one instant.
     *
     * <p>Read from the kind of the <em>next</em> extreme rather than from a height comparison, so a
     * pair of equal-height readings still resolves rather than defaulting.
     */
    private static BriefingWindowTide.Direction directionAt(List<Point> series, int minutes) {
        for (Point point : series) {
            if (point.minutes() > minutes) {
                return point.high()
                        ? BriefingWindowTide.Direction.RISING
                        : BriefingWindowTide.Direction.FALLING;
            }
        }
        // Past the end of everything stored: the tide turns at the last extreme, so it is now
        // heading the other way.
        Point last = series.get(series.size() - 1);
        return last.high()
                ? BriefingWindowTide.Direction.FALLING
                : BriefingWindowTide.Direction.RISING;
    }

    private static Point point(TideExtremeEntity extreme, LocalDate date) {
        return new Point(extreme.getType() == TideExtremeType.HIGH,
                clockMinutesFrom(extreme.getEventTime(), date),
                extreme.getHeightMetres().doubleValue());
    }

    private static LocalDate localDate(TideExtremeEntity extreme) {
        return extreme.getEventTime().atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(LONDON).toLocalDate();
    }

    /**
     * Where an instant falls on {@code date}'s local clock axis, in minutes — negative for the
     * previous day, past 1440 for the next.
     *
     * <p><b>Clock position, not elapsed duration.</b> The axis is a local day read 00:00 to 24:00,
     * which is what {@code TideRunRow} and {@code solarDayGeometry} already draw, so a spring-forward
     * day still spans 1440 units even though only 1380 minutes pass. Every stated <em>duration</em>
     * — the offset between the water and the sun — is measured between UTC instants instead, so no
     * number a reader acts on inherits this axis's fiction.
     */
    private static int clockMinutesFrom(LocalDateTime utc, LocalDate date) {
        LocalDateTime local = utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(LONDON)
                .toLocalDateTime();
        long dayOffset = ChronoUnit.DAYS.between(date, local.toLocalDate());
        return (int) (dayOffset * MINUTES_PER_DAY + local.toLocalTime().toSecondOfDay() / 60);
    }

    private static double round(double value) {
        return Math.round(value * CURVE_SCALE) / (double) CURVE_SCALE;
    }
}
