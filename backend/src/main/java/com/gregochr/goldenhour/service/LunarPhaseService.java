package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.solarutils.LunarCalculator;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

/**
 * Moon phase, perigee proximity and spring/king tide classification, measured from the ephemeris
 * rather than from calendar arithmetic.
 *
 * <h2>Why this class no longer does its own astronomy</h2>
 *
 * <p>It used to walk a mean synodic month from a reference new moon and a mean anomalistic month
 * from a reference perigee. Both epochs were wrong and both mean-month models ignore the periodic
 * terms that make the real Moon wander either side of the mean, so the errors compounded. Measured
 * against an independent Meeus implementation:
 *
 * <ul>
 *   <li>{@code REFERENCE_NEW_MOON = 2000-01-06} was read as midnight; the true new moon was that
 *       day at <b>18:15 UTC</b>, so the epoch started 0.761 days early. Across 111 events the
 *       service's idea of "new moon" ran <b>+0.595 days late on average</b>, spread over 1.15 days.
 *       The syzygy test was wrong on roughly <b>17 days a year</b>.</li>
 *   <li>{@code REFERENCE_PERIGEE = 2025-01-04} was 3.45 days off the true perigee
 *       (2025-01-07 22:52 UTC). With the mean-month drift on top, perigee proximity was wrong by
 *       <b>up to 4.65 days</b> — against a window that was then 0.5 days wide, which made the king
 *       tide classification noise. It fired on one day in twelve months, and not the right one.</li>
 *   <li>Illuminated fraction, derived from the same mean phase, was out by up to <b>7.1
 *       percentage points</b> and landed the wrong side of the meteor showers' 50% moon gate on
 *       26 days in three years.</li>
 * </ul>
 *
 * <p>{@link LunarCalculator} from {@code solar-utils} was already a bean in this application and is
 * already accurate: its illumination minima and maxima match the same Meeus reference to within
 * <b>0.7 hours</b> — the resolution floor of the check — and its distance minima to within about
 * two hours. So this class delegates and keeps no ephemeris of its own. There is no epoch here to
 * be wrong, and no mean month to drift.
 *
 * <h2>How "nearest new moon" and "nearest perigee" are found</h2>
 *
 * <p>By locating the turning points of the quantities themselves — illumination for syzygy,
 * geocentric distance for perigee — rather than by counting cycles from a fixed date. A coarse scan
 * finds the local extrema in a window guaranteed to contain one, and a ternary search refines the
 * nearest to the minute. The functions are smooth and each interval between turning points is
 * monotonic, so the refinement is well posed.
 *
 * <p><b>Local extrema, not the global one.</b> A window wide enough to guarantee a hit can hold two,
 * and perigees differ in depth, so the deepest is not always the nearest. Taking the global minimum
 * would silently pick the wrong perigee whenever the further one happened to be the closer approach.
 *
 * <h2>Coordinates are irrelevant here, and that is measured</h2>
 *
 * <p>{@code LunarCalculator.calculate} takes a latitude and longitude, but illumination, phase and
 * distance are geocentric: at one instant they are identical to ten decimal places at 55°N/1.5°W,
 * 0°/0°, 45°S/170°E and 80°N/60°W. Only {@code altitude}/{@code azimuth} are topocentric, and this
 * class asks for neither. A fixed reference point is therefore not an approximation.
 */
@Service
public class LunarPhaseService {

    /**
     * Within this many days of true syzygy a date counts as a spring tide.
     *
     * <p>Unchanged in intent from the old {@code 0.034} lunation window (≈1.004 days), but it is now
     * measured from the true new or full moon instead of a mean one that could be a day out.
     */
    static final double NEW_FULL_MOON_WINDOW_DAYS = 1.0;

    /**
     * Within this many days of true perigee a syzygy is a <em>perigean</em> spring — a king tide.
     *
     * <p><b>Widened from 0.5, and the number is derived rather than chosen.</b> Over a 30-year
     * baseline (767 syzygies, about 27 beat periods of the perigee/syzygy cycle) this window fires
     * on 22.2% of syzygies, which is <b>5.5 king tides a year</b> — the bottom of the "5–10 times
     * per year" the pill's own copy claims. The old 0.5-day window would give 2.0 a year even with
     * correct astronomy, and gave 1.0 with the arithmetic it had.
     *
     * <p>±1.5 days is also the conventional threshold for a perigean spring tide, so the window is
     * defensible on the astronomy and not only on the copy.
     *
     * <p>Note the offset is <em>not</em> uniformly distributed: ±1.5 days of 13.8 would be 10.9% if
     * it were, and the measured 22.2% is double that, because the anomalistic month is compressed
     * near syzygy so perigees bunch there. Do not re-derive this figure from the ratio of windows.
     */
    static final double PERIGEE_WINDOW_DAYS = 1.5;

    /**
     * Half-width of the new-moon and full-moon searches, in days.
     *
     * <p>Sized on the <b>synodic</b> month, not on the syzygy interval. New moons and full moons
     * alternate every 14.765 days, but this class searches for each kind <em>separately</em> — the
     * supermoon topic needs full moons specifically — and successive new moons are 29.53 days apart,
     * so the nearest one can be 14.8 days away. An 8-day window sized on the alternating interval
     * compiles, passes a spot check on a date near syzygy, and then throws on the first date in the
     * middle of a lunation; that is what {@code daysToNearestTurningPoint}'s loud failure is for.
     *
     * <p>16 days leaves a margin over the 14.8, and guarantees a turning point strictly inside the
     * window edges — which the test requires, since it needs a sample either side.
     */
    private static final int SYZYGY_SEARCH_DAYS = 16;

    /**
     * Half-width of the perigee search, in days.
     *
     * <p>Perigee-to-perigee runs 24.6–28.6 days — the interval is far from constant, which is
     * exactly what the old mean-month model got wrong — so the nearest is never more than 14.3 days
     * away and 15 leaves a margin.
     */
    private static final int PERIGEE_SEARCH_DAYS = 15;

    /** Coarse scan step. Fine enough that no turning point hides between two samples. */
    private static final Duration COARSE_STEP = Duration.ofHours(6);

    /** Refinement stops here: far below any threshold this class compares against. */
    private static final Duration REFINE_TO = Duration.ofMinutes(1);

    /**
     * Geocentric quantities are coordinate-independent (measured — see the class javadoc), so the
     * reference point is arbitrary and its only job is to be constant.
     */
    private static final double REFERENCE_LAT = 0.0;

    /** Companion to {@link #REFERENCE_LAT}. */
    private static final double REFERENCE_LON = 0.0;

    /**
     * Beyond this many memoised dates the caches are dropped wholesale.
     *
     * <p>A search is a few hundred ephemeris evaluations, so memoising matters: the almanac asks for
     * 90 consecutive dates and the hot-topic strategies re-ask on every serve. The bound exists only
     * so a long-lived process cannot accumulate without limit; clearing is safe because every entry
     * is a pure function of its key and is simply recomputed.
     */
    private static final int MAX_CACHED_DATES = 4096;

    private final LunarCalculator lunarCalculator;

    private final Map<LocalDate, ZonedDateTime> newMoonInstant = new ConcurrentHashMap<>();
    private final Map<LocalDate, ZonedDateTime> fullMoonInstant = new ConcurrentHashMap<>();
    private final Map<LocalDate, ZonedDateTime> perigeeInstant = new ConcurrentHashMap<>();

    /**
     * Constructs a {@code LunarPhaseService}.
     *
     * @param lunarCalculator the shared solar-utils ephemeris, supplied by {@code AppConfig}
     */
    public LunarPhaseService(LunarCalculator lunarCalculator) {
        this.lunarCalculator = lunarCalculator;
    }

    /**
     * Returns the human-readable moon phase name.
     *
     * @param date the calendar date
     * @return one of: "New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous",
     *         "Full Moon", "Waning Gibbous", "Last Quarter", "Waning Crescent"
     */
    public String getMoonPhase(LocalDate date) {
        return lunarCalculator.calculate(noon(date), REFERENCE_LAT, REFERENCE_LON)
                .phase().displayName();
    }

    /**
     * Returns the illuminated fraction of the Moon's disc at midday on the given date.
     *
     * <p>Read straight off the ephemeris. It used to be derived from a mean-phase cosine, which is
     * the wrong shape as well as the wrong timing — the meteor-shower strategies gate on this at
     * 50%, and the old value crossed that boundary on the wrong day 26 times in three years.
     *
     * @param date the calendar date
     * @return illuminated fraction in [0.0, 1.0]
     */
    public double getIlluminationFraction(LocalDate date) {
        return lunarCalculator.calculate(noon(date), REFERENCE_LAT, REFERENCE_LON).illumination();
    }

    /**
     * Returns true when the date falls within {@value #NEW_FULL_MOON_WINDOW_DAYS} day of a true
     * new moon.
     *
     * @param date the calendar date
     * @return true if near a new moon
     */
    public boolean isNewMoon(LocalDate date) {
        return daysFromNearestNewMoon(date) <= NEW_FULL_MOON_WINDOW_DAYS;
    }

    /**
     * Returns true when the date falls within {@value #NEW_FULL_MOON_WINDOW_DAYS} day of a true
     * full moon.
     *
     * @param date the calendar date
     * @return true if near a full moon
     */
    public boolean isFullMoon(LocalDate date) {
        return daysFromNearestFullMoon(date) <= NEW_FULL_MOON_WINDOW_DAYS;
    }

    /**
     * Returns true when the date is near either syzygy — the condition for a spring tide.
     *
     * @param date the calendar date
     * @return true if near a new or full moon
     */
    public boolean isNewOrFullMoon(LocalDate date) {
        return isNewMoon(date) || isFullMoon(date);
    }

    /**
     * Returns days from midday on the given date to the nearest true new moon.
     *
     * @param date the calendar date
     * @return days to the nearest new moon, always &ge; 0.0
     */
    public double daysFromNearestNewMoon(LocalDate date) {
        return daysBetween(noon(date), nearestNewMoon(date));
    }

    /**
     * Returns days from midday on the given date to the nearest true full moon.
     *
     * @param date the calendar date
     * @return days to the nearest full moon, always &ge; 0.0
     */
    public double daysFromNearestFullMoon(LocalDate date) {
        return daysBetween(noon(date), nearestFullMoon(date));
    }

    /**
     * Returns the number of days between the given date and the nearest true perigee.
     *
     * <p>Found by minimising geocentric distance, so it carries the real variation in the
     * anomalistic month rather than assuming a constant 27.55 days.
     *
     * @param date the calendar date
     * @return days to the nearest perigee, always &ge; 0.0
     */
    public double daysFromNearestPerigee(LocalDate date) {
        return daysBetween(noon(date), nearestPerigee(date));
    }

    /**
     * Returns true if the Moon is near perigee on the given date.
     *
     * @param date the calendar date
     * @return true if within {@value #PERIGEE_WINDOW_DAYS} days of perigee
     */
    public boolean isMoonAtPerigee(LocalDate date) {
        return daysFromNearestPerigee(date) <= PERIGEE_WINDOW_DAYS;
    }

    /**
     * Classifies the astronomical tide type for a given date.
     *
     * <p><b>This is the lunar axis only, and it answers "what kind of event is this".</b> It says
     * nothing about how high the water actually gets at any particular port — that is a separate
     * question with a separate source ({@code TideSizeIndex}, from stored extremes), and the two
     * must not be substituted for one another. A king tide is a <em>perigean spring</em>: a lunar
     * configuration, not a height percentile.
     *
     * <p>Nor does it answer <em>when</em> the biggest water arrives. A port's largest tide lags
     * syzygy by a day or two — the age of the tide, a property of the coastline — so a date that is
     * astronomically a spring tide is not necessarily the day the foreshore is widest.
     * <p><b>King-ness is a property of the event, not of the date.</b> The perigee test is applied
     * to the <em>syzygy instant</em>, not to this date — so every date of a run gets the same
     * answer. Testing each date separately looks equivalent and is not: a spring run spans two or
     * three days, and with a 1.0-day syzygy window and a 1.5-day perigee window the dates on the
     * perigee side of the syzygy can qualify while the far side does not. That labels one tidal
     * event two ways across its own days, which is exactly the class of self-contradiction the tide
     * surfaces have repeatedly had to have removed. It also inflates the rate: measured per-date it
     * fires 6.7 times a year, measured per-event 5.5.
     *
     * @param date the calendar date
     * @return {@link LunarTideType#KING_TIDE} if the nearest syzygy is perigean,
     *         {@link LunarTideType#SPRING_TIDE} if near syzygy but not perigean,
     *         {@link LunarTideType#REGULAR_TIDE} otherwise
     */
    public LunarTideType classifyTide(LocalDate date) {
        ZonedDateTime midday = noon(date);
        ZonedDateTime newMoon = nearestNewMoon(date);
        ZonedDateTime fullMoon = nearestFullMoon(date);
        ZonedDateTime syzygy = daysBetween(midday, newMoon) <= daysBetween(midday, fullMoon)
                ? newMoon : fullMoon;

        if (daysBetween(midday, syzygy) > NEW_FULL_MOON_WINDOW_DAYS) {
            return LunarTideType.REGULAR_TIDE;
        }
        // Perigee measured from the syzygy, and searched around the syzygy's own date so a run's
        // first and last day cannot resolve different perigees.
        double syzygyToPerigee = daysBetween(syzygy, nearestPerigee(syzygy.toLocalDate()));
        return syzygyToPerigee <= PERIGEE_WINDOW_DAYS
                ? LunarTideType.KING_TIDE : LunarTideType.SPRING_TIDE;
    }

    private ZonedDateTime nearestNewMoon(LocalDate date) {
        return cached(newMoonInstant, date, d -> nearestTurningPoint(
                noon(d), SYZYGY_SEARCH_DAYS, this::illumination, true));
    }

    private ZonedDateTime nearestFullMoon(LocalDate date) {
        return cached(fullMoonInstant, date, d -> nearestTurningPoint(
                noon(d), SYZYGY_SEARCH_DAYS, this::illumination, false));
    }

    private ZonedDateTime nearestPerigee(LocalDate date) {
        return cached(perigeeInstant, date, d -> nearestTurningPoint(
                noon(d), PERIGEE_SEARCH_DAYS, this::distanceKm, true));
    }

    /** Absolute days between two instants. */
    private static double daysBetween(ZonedDateTime a, ZonedDateTime b) {
        return Math.abs(Duration.between(a, b).toMinutes()) / (double) Duration.ofDays(1).toMinutes();
    }

    private double illumination(ZonedDateTime instant) {
        return lunarCalculator.calculate(instant, REFERENCE_LAT, REFERENCE_LON).illumination();
    }

    private double distanceKm(ZonedDateTime instant) {
        return lunarCalculator.calculate(instant, REFERENCE_LAT, REFERENCE_LON).distanceKm();
    }

    private static ZonedDateTime noon(LocalDate date) {
        return date.atTime(12, 0).atZone(ZoneOffset.UTC);
    }

    private ZonedDateTime cached(Map<LocalDate, ZonedDateTime> cache, LocalDate date,
            java.util.function.Function<LocalDate, ZonedDateTime> compute) {
        ZonedDateTime hit = cache.get(date);
        if (hit != null) {
            return hit;
        }
        if (cache.size() >= MAX_CACHED_DATES) {
            cache.clear();
        }
        ZonedDateTime value = compute.apply(date);
        cache.put(date, value);
        return value;
    }

    /**
     * The nearest turning point of {@code f} to {@code centre}, within ±{@code halfWindowDays}.
     *
     * <p>Coarse scan for local extrema, refine each, take the nearest. The caller's window must be
     * wide enough that at least one turning point falls strictly inside it.
     *
     * @param wantMinimum true to look for minima (new moon by illumination, perigee by distance),
     *                    false for maxima (full moon)
     */
    private ZonedDateTime nearestTurningPoint(ZonedDateTime centre, int halfWindowDays,
            ToDoubleFunction<ZonedDateTime> f, boolean wantMinimum) {
        ZonedDateTime start = centre.minusDays(halfWindowDays);
        int steps = (int) (Duration.ofDays(2L * halfWindowDays).toMinutes() / COARSE_STEP.toMinutes());

        double[] samples = new double[steps + 1];
        for (int i = 0; i <= steps; i++) {
            samples[i] = f.applyAsDouble(start.plus(COARSE_STEP.multipliedBy(i)));
        }

        List<ZonedDateTime> found = new ArrayList<>(2);
        for (int i = 1; i < steps; i++) {
            boolean turning = wantMinimum
                    ? samples[i] < samples[i - 1] && samples[i] < samples[i + 1]
                    : samples[i] > samples[i - 1] && samples[i] > samples[i + 1];
            if (turning) {
                found.add(refine(start.plus(COARSE_STEP.multipliedBy(i - 1)),
                        start.plus(COARSE_STEP.multipliedBy(i + 1)), f, wantMinimum));
            }
        }
        if (found.isEmpty()) {
            // Unreachable for the windows this class uses, and a silent wrong answer would be worse
            // than a loud one: every caller is about to compare the result to a threshold. This
            // fired the first time the syzygy window was sized on the 14.8-day syzygy interval
            // rather than the 29.5-day synodic month — see SYZYGY_SEARCH_DAYS.
            throw new IllegalStateException(
                    "no lunar turning point within " + halfWindowDays + " days of " + centre);
        }

        ZonedDateTime nearest = found.get(0);
        for (ZonedDateTime instant : found) {
            if (daysBetween(centre, instant) < daysBetween(centre, nearest)) {
                nearest = instant;
            }
        }
        return nearest;
    }

    /** Ternary search for the extremum of a unimodal interval, to {@link #REFINE_TO}. */
    private static ZonedDateTime refine(ZonedDateTime low, ZonedDateTime high,
            ToDoubleFunction<ZonedDateTime> f, boolean wantMinimum) {
        ZonedDateTime lo = low;
        ZonedDateTime hi = high;
        while (Duration.between(lo, hi).compareTo(REFINE_TO) > 0) {
            Duration third = Duration.between(lo, hi).dividedBy(3);
            ZonedDateTime a = lo.plus(third);
            ZonedDateTime b = hi.minus(third);
            boolean keepLeft = wantMinimum
                    ? f.applyAsDouble(a) < f.applyAsDouble(b)
                    : f.applyAsDouble(a) > f.applyAsDouble(b);
            if (keepLeft) {
                hi = b;
            } else {
                lo = a;
            }
        }
        return lo.plus(Duration.between(lo, hi).dividedBy(2));
    }
}
