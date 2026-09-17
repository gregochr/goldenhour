package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.model.BriefingWindowTide;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The day's tide shape: cosine-interpolated heights between stored extremes, normalised to the
 * sampled series' own span — the one curve interpolation the app has.
 *
 * <p><b>Lifted out of {@code WindowTideRollupBuilder}, not duplicated.</b> Every method here was a
 * private static on that class; the window rollup's own tide row and the per-slot tide facts on
 * {@code BriefingSlot.TideInfo} both need the identical cosine, so a second copy would be the
 * exact defect the shared {@code TideWording} vocabulary already exists to prevent for the words
 * around a tide, applied to its maths instead.
 *
 * <p>Package-private: nothing outside {@code service} draws a tide curve.
 *
 * <p><b>Never a historical comparison.</b> {@link #bracket} and {@link #fillInteriorGaps}
 * synthesise points that affect the trace and everything read off it — the level, the direction,
 * and a <em>today</em>-scoped figure like the day's own high water or the height at the light.
 * That is licensed: those describe today's curve, the same category as the rendered trace itself.
 * What is never licensed is comparing today against <em>history</em> from anything this class
 * returns — a range or an anomaly measured against a location's own mean, which must come from
 * real, unsynthesised rows or the comparison is corrupted by a point nothing ever measured. See
 * {@code WindowTideRollupBuilder}'s own {@code pointsOn} for why that split is load-bearing, and
 * why its {@code rangeOn}/{@code meanRangeOn} never call {@link #seriesAround}.
 */
final class TideCurveCalculator {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static final int MINUTES_PER_DAY = 1440;

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

    /**
     * Interval between samples on the day's tide shape, in minutes.
     *
     * <p>Thirty minutes gives 49 points across the day — about one sample every two pixels on the
     * design's 104px sparkline, which is smoother than the trace can show. The full-width tide-run
     * chart samples far finer because it is ten times as wide.
     */
    private static final int SAMPLE_MINUTES = 30;

    /** Three decimals of a 24px trace is a fortieth of a pixel; more is payload, not detail. */
    private static final int CURVE_SCALE = 1000;

    private TideCurveCalculator() {
    }

    /**
     * One point on the curve, as minutes from the local midnight of the day being described —
     * either a real stored extreme, or a synthetic one {@link #fillInteriorGaps} or {@link
     * #bracket} minted to keep the trace honest across a gap or past its measured ends.
     *
     * @param high    true for a high water, false for a low
     * @param minutes minutes from the day's own local midnight; negative or past 1440 for a point
     *                that falls on a neighbouring day
     * @param height  the water height in metres — measured for a real extreme, copied from the
     *                nearest measured opposite-kind extreme for a synthetic one
     */
    record Point(boolean high, int minutes, double height) {
    }

    /**
     * The day's trace and the scale it was normalised on.
     *
     * <p>One object rather than two derivations because a mark placed on the trace must sit
     * <em>on</em> the line: normalising the mark against a separately recomputed span is how a
     * mark comes to float a pixel off its own curve.
     *
     * @param curve the normalised samples
     * @param min   the lowest sampled height, in metres
     * @param span  the sampled height range, in metres
     */
    record Shape(List<Double> curve, double min, double span) {

        /**
         * Places a raw height on the same 0–1 scale the curve was normalised to.
         *
         * <p>Clamped, because the scale is derived from the samples and the instant being placed
         * is almost never one of them: a peak falling between two samples is genuinely higher than
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
     * Every stored extreme within a day either side, as minutes from this day's local midnight.
     *
     * <p>Wider than a plain same-day filter because the shape needs something to interpolate
     * towards before the day's first extreme and after its last.
     *
     * <p>Interior gaps are filled before the ends are bracketed, because {@link #bracket} reads the
     * first and last points and the fill must not be what it reads.
     */
    static List<Point> seriesAround(List<TideExtremeEntity> extremes, LocalDate date) {
        List<Point> points = new ArrayList<>();
        for (TideExtremeEntity extreme : extremes) {
            LocalDate local = localDate(extreme.getEventTime());
            if (extreme.getHeightMetres() == null
                    || local.isBefore(date.minusDays(1)) || local.isAfter(date.plusDays(1))) {
                continue;
            }
            points.add(point(extreme, date));
        }
        points.sort(Comparator.comparingInt(Point::minutes));
        return bracket(fillInteriorGaps(points));
    }

    /**
     * Inserts the opposite extreme a lost row implies, midway between any two consecutive
     * same-kind extremes.
     *
     * <p>Tides alternate, so HIGH followed by HIGH is physically impossible: it means a stored
     * extreme is missing. Cosine-interpolating straight across such a gap draws hours of confident
     * slack water pinned at high-water level, which is worse than drawing nothing: the reader
     * cannot tell it from a real stand of tide.
     *
     * <p><b>Shape only.</b> The licence is {@link #bracket}'s bookends' — it affects the trace, the
     * level read off it and the direction, and no other stated figure. Same-kind adjacency only,
     * with no time-gap heuristic — a long gap between alternating kinds is a neap tide or a
     * shallow-water coast, not a hole. Heights come from {@link #counterpartHeight} against the
     * real points, so the fill copies a measured opposite-kind height rather than inventing one.
     *
     * @param points this day's window of real extremes, ascending
     * @return the same points with an implied extreme between every same-kind pair
     */
    private static List<Point> fillInteriorGaps(List<Point> points) {
        List<Point> filled = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            Point current = points.get(i);
            filled.add(current);
            if (i + 1 < points.size() && points.get(i + 1).high() == current.high()) {
                int midpoint = (current.minutes() + points.get(i + 1).minutes()) / 2;
                filled.add(new Point(!current.high(), midpoint, counterpartHeight(points, current)));
            }
        }
        return filled;
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
    static List<Point> bracket(List<Point> points) {
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
     * The day's tide shape: cosine-interpolated heights sampled every {@link #SAMPLE_MINUTES},
     * normalised to 0–1 across the sampled series' own span.
     *
     * <p>Cosine because a real tide is very close to sinusoidal between high and low water.
     * Normalised against the samples rather than against the day's stated range so the trace cannot
     * clip: the series reaches a day either side, so a sample near midnight can legitimately sit
     * outside the day's own extremes.
     */
    static Shape shape(List<Point> series) {
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
    static double heightAt(List<Point> series, int minutes) {
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
    static BriefingWindowTide.Direction directionAt(List<Point> series, int minutes) {
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

    static Point point(TideExtremeEntity extreme, LocalDate date) {
        return new Point(extreme.getType() == TideExtremeType.HIGH,
                clockMinutesFrom(extreme.getEventTime(), date),
                extreme.getHeightMetres().doubleValue());
    }

    /**
     * The Europe/London local calendar day a stored UTC instant falls on.
     *
     * @param utc the instant, UTC
     * @return the local date
     */
    static LocalDate localDate(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(LONDON).toLocalDate();
    }

    /**
     * Where an instant falls on {@code date}'s local clock axis, in minutes — negative for the
     * previous day, past 1440 for the next.
     *
     * <p><b>Clock position, not elapsed duration.</b> The axis is a local day read 00:00 to 24:00,
     * so a spring-forward day still spans 1440 units even though only 1380 minutes pass. Every
     * stated <em>duration</em> is measured between UTC instants instead, so no number a reader acts
     * on inherits this axis's fiction.
     */
    static int clockMinutesFrom(LocalDateTime utc, LocalDate date) {
        LocalDate localDate = localDate(utc);
        long dayOffset = ChronoUnit.DAYS.between(date, localDate);
        return (int) (dayOffset * MINUTES_PER_DAY) + TideWording.londonMinutesOfDay(utc);
    }

    static double round(double value) {
        return Math.round(value * CURVE_SCALE) / (double) CURVE_SCALE;
    }

    /**
     * Where a minute offset from a local midnight falls on the day, 0.0 at 00:00 to 1.0 at the
     * next — the same clamped, rounded axis {@code BriefingWindowTide.windowPosition} and the
     * strip's sunrise/sunset marks share, so every caller stating "how far through the day" agrees
     * on the one formula rather than each rounding its own copy.
     *
     * @param minutes minutes from local midnight, as {@link #clockMinutesFrom} returns them
     * @return the position, clamped to 0.0–1.0
     */
    static double positionOf(int minutes) {
        return round(Math.min(1.0, Math.max(0.0, (double) minutes / MINUTES_PER_DAY)));
    }
}
