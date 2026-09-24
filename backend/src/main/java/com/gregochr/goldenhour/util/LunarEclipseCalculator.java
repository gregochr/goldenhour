package com.gregochr.goldenhour.util;

import com.gregochr.goldenhour.model.LunarEclipseSight;
import com.gregochr.goldenhour.service.LunarEclipseCatalog.LunarEclipse;
import com.gregochr.goldenhour.service.evaluation.PromptUtils;
import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.LunarPosition;
import com.gregochr.solarutils.MoonriseMoonset;
import com.gregochr.solarutils.MoonriseMoonsetCalculator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Turns one catalogued {@link LunarEclipse}'s UTC contacts into what a given observer sees.
 *
 * <p>Unlike {@link EclipseCalculator} — which reduces a solar eclipse's Besselian elements, since
 * magnitude itself varies by observer — a lunar eclipse's depth is fixed by the catalogue and the
 * same everywhere it is visible at all. All this class computes is geometry: the Moon's altitude
 * and bearing at greatest eclipse, whether and when it rises or sets during the umbral span, and
 * whether the umbral phase is watchable from here at all. It calls {@code solar-utils}'
 * {@link LunarCalculator} and {@link MoonriseMoonsetCalculator} directly — the same call pattern
 * {@code SupermoonHotTopicStrategy} already uses — and makes no network calls of its own.
 *
 * <h2>The eligibility rule</h2>
 *
 * <p>A location counts as able to see the eclipse ({@link LunarEclipseSight#visible()}) when the
 * Moon's altitude at greatest eclipse is at least {@value #VISIBLE_ALTITUDE_DEG}°, <b>or</b> its
 * altitude is at least {@value #VISIBLE_ALTITUDE_DEG}° for a contiguous
 * {@value #VISIBLE_CONTIGUOUS_MINUTES} minutes somewhere inside the umbral span {@code [u1, u4]}.
 * The second arm exists because greatest eclipse is not always the best-placed instant: an eclipse
 * whose peak comes just before moonset, or just after moonrise, can still give a genuinely watchable
 * stretch of umbral phase even though the Moon is low or below the horizon at the exact moment of
 * maximum coverage. Sampling once a minute across the whole span (a few hundred calls, once per
 * catalogue entry per location, at build time — not per request) is cheap and exact enough that no
 * shortcut is worth the risk of missing a short, low, genuinely visible window.
 *
 * <h2>Moonrise and moonset are one paired arc, not two independent nearest-neighbour lookups</h2>
 *
 * <p>{@link MoonriseMoonsetCalculator} answers for one London civil date at a time, and the Moon
 * rises and sets roughly once every 24h50m — so a rise near one end of a civil date's day is
 * typically paired with a set on the <em>next</em> civil date, not a leftover set from earlier the
 * same morning. Querying only the umbral span's own civil date(s) and picking whichever candidate
 * is merely nearest the window (as an earlier version of this class did) can pick that leftover: for
 * a 2028-12-31 evening eclipse rising in shadow at 15:36 GMT, the same civil date's only moonset
 * candidate is 08:34 GMT <em>that same morning</em> — hours <b>before</b> the rise, not after it —
 * producing a {@link LunarEclipseSight} whose moonset precedes its own moonrise (Codex review, #911).
 * The real paired moonset is the following morning, 2029-01-01.
 *
 * <p>So this class queries a full day of margin either side of the umbral span (the London civil
 * dates of {@code U1 − 1}, {@code U1}, {@code U4} and {@code U4 + 1}, deduplicated — up to four
 * distinct dates) and picks the two events as one unit rather than two independent lookups:
 * {@link #pickMoonrise} is the most recent rise at or before U4 — an in-window rise when one exists,
 * else the rise that began the Moon's current visible arc even if that was the evening before; then
 * {@link #pickMoonset} is the <em>earliest</em> set strictly after that specific moonrise, wherever
 * its civil date falls. Deriving moonset from the already-chosen moonrise this way makes
 * {@code moonset > moonrise} true by construction whenever both are present — never a separate
 * invariant to remember to check — which is also why {@link LunarEclipseSight} itself validates it
 * defensively in its own compact constructor: a future change to this ordering breaking that
 * invariant fails at construction rather than shipping a chronologically impossible sight.
 *
 * <h2>Why this is a {@code @Component}, unlike everything else in {@code util}</h2>
 *
 * <p>Every other class in this package is a stateless static utility with no Spring dependency.
 * This one is not, because — unlike {@link EclipseCalculator}, which needs nothing but the
 * elements it is handed — it genuinely depends on injected state: the {@link LunarCalculator} and
 * {@link MoonriseMoonsetCalculator} beans, which is what lets tests substitute controlled fakes to
 * pin the eligibility rule's exact boundary (see {@code LunarEclipseCalculatorTest}) rather than
 * relying on a real eclipse that happens to sit at 30 minutes. Belonging beside
 * {@link EclipseCalculator} conceptually (see above) does not require matching its
 * dependency-injection shape.
 */
@Component
public class LunarEclipseCalculator {

    /** Minimum altitude, in degrees, above the astronomical horizon to count as watchable. */
    static final double VISIBLE_ALTITUDE_DEG = 3.0;

    /** Minimum contiguous minutes at or above {@link #VISIBLE_ALTITUDE_DEG} to count as watchable. */
    static final int VISIBLE_CONTIGUOUS_MINUTES = 30;

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final double DEGREES_PER_CIRCLE = 360.0;

    private final LunarCalculator lunarCalculator;
    private final MoonriseMoonsetCalculator moonriseMoonsetCalculator;

    /**
     * Constructs a {@code LunarEclipseCalculator}.
     *
     * @param lunarCalculator           lunar position (altitude, azimuth) calculator
     * @param moonriseMoonsetCalculator moonrise/moonset time calculator
     */
    public LunarEclipseCalculator(
            LunarCalculator lunarCalculator, MoonriseMoonsetCalculator moonriseMoonsetCalculator) {
        this.lunarCalculator = lunarCalculator;
        this.moonriseMoonsetCalculator = moonriseMoonsetCalculator;
    }

    /**
     * What one observer sees of the given lunar eclipse.
     *
     * @param eclipse   the catalogued eclipse
     * @param latitude  observer latitude in decimal degrees, north positive
     * @param longitude observer longitude in decimal degrees, east positive (so a UK location is
     *                  negative, matching {@code LocationEntity.lon})
     * @return the local sight — never null; {@link LunarEclipseSight#visible()} says whether
     *     anything of the umbral phase can actually be seen from here
     */
    public LunarEclipseSight sight(LunarEclipse eclipse, double latitude, double longitude) {
        ZonedDateTime maxUtc = eclipse.max().atZone(ZoneOffset.UTC);
        LunarPosition atMax = lunarCalculator.calculate(maxUtc, latitude, longitude);
        int altAtMax = (int) Math.round(atMax.altitude());
        int azAtMax = roundedDegrees(atMax.azimuth());
        String cardinal = PromptUtils.toCardinal(azAtMax);

        ZonedDateTime u1Utc = eclipse.u1().atZone(ZoneOffset.UTC);
        ZonedDateTime u4Utc = eclipse.u4().atZone(ZoneOffset.UTC);
        ZonedDateTime u1London = u1Utc.withZoneSameInstant(LONDON);
        ZonedDateTime u4London = u4Utc.withZoneSameInstant(LONDON);

        MoonEvents events = moonEventsNear(u1London, u4London, latitude, longitude);
        ZonedDateTime moonrise = pickMoonrise(events.rises(), u4London);
        ZonedDateTime moonset = pickMoonset(events.sets(), moonrise, u1London, u4London);

        boolean setsInShadow = withinSpan(moonset, u1London, u4London);
        boolean risesInShadow = withinSpan(moonrise, u1London, u4London);

        ZonedDateTime visibleStart = risesInShadow ? moonrise : u1London;
        ZonedDateTime visibleEnd = setsInShadow ? moonset : u4London;

        boolean visible = atMax.altitude() >= VISIBLE_ALTITUDE_DEG
                || hasContiguousVisibleWindow(u1Utc, u4Utc, latitude, longitude);

        return new LunarEclipseSight(
                altAtMax,
                azAtMax,
                cardinal,
                toLocalDateTime(moonset),
                toLocalDateTime(moonrise),
                setsInShadow,
                risesInShadow,
                visibleStart.toLocalDateTime(),
                visibleEnd.toLocalDateTime(),
                visible);
    }

    /**
     * Whether the Moon's altitude reaches {@link #VISIBLE_ALTITUDE_DEG} for a contiguous
     * {@link #VISIBLE_CONTIGUOUS_MINUTES} minutes anywhere between {@code u1} and {@code u4}
     * inclusive, sampling once a minute.
     *
     * <p><b>A streak's duration is measured from its first qualifying sample to its current one</b>
     * — {@code minute - streakStartMinute} — not by counting samples. Counting samples instead would
     * be off by one: a window sampled at minute 0 through minute 29 (30 samples) spans only 29
     * minutes of wall-clock time, and reporting it as a 30-minute streak would call a 29-minute
     * window visible. {@code LunarEclipseCalculatorTest} pins both sides of this boundary.
     */
    private boolean hasContiguousVisibleWindow(
            ZonedDateTime u1Utc, ZonedDateTime u4Utc, double latitude, double longitude) {
        long totalMinutes = Duration.between(u1Utc, u4Utc).toMinutes();
        long streakStartMinute = -1;
        for (long minute = 0; minute <= totalMinutes; minute++) {
            ZonedDateTime t = u1Utc.plusMinutes(minute);
            double altitude = lunarCalculator.calculate(t, latitude, longitude).altitude();
            if (altitude >= VISIBLE_ALTITUDE_DEG) {
                if (streakStartMinute < 0) {
                    streakStartMinute = minute;
                }
                if (minute - streakStartMinute >= VISIBLE_CONTIGUOUS_MINUTES) {
                    return true;
                }
            } else {
                streakStartMinute = -1;
            }
        }
        return false;
    }

    /**
     * Every moonrise/moonset candidate on the London civil dates a day either side of
     * {@code [u1, u4]} — see the class javadoc for why a full day of margin is needed on both ends.
     */
    private MoonEvents moonEventsNear(ZonedDateTime u1London, ZonedDateTime u4London, double lat, double lon) {
        LocalDate u1Date = u1London.toLocalDate();
        LocalDate u4Date = u4London.toLocalDate();
        // A TreeSet dedupes and sorts: 3 distinct dates when u1/u4 share a civil date (the common
        // case), 4 when the umbral span itself already crosses London midnight.
        SortedSet<LocalDate> dates = new TreeSet<>(
                List.of(u1Date.minusDays(1), u1Date, u4Date, u4Date.plusDays(1)));

        List<ZonedDateTime> rises = new ArrayList<>();
        List<ZonedDateTime> sets = new ArrayList<>();
        for (LocalDate date : dates) {
            MoonriseMoonset events = moonriseMoonsetCalculator.calculate(date, lat, lon, LONDON);
            events.moonrise().ifPresent(rises::add);
            events.moonset().ifPresent(sets::add);
        }
        return new MoonEvents(rises, sets);
    }

    /**
     * The rise operative for this window: the most recent moonrise at or before U4. An in-window
     * rise is, definitionally, at or before U4 and closer to it than the previous cycle's rise
     * (candidates are spaced ~24h50m apart), so it wins the {@code max} naturally; when there is no
     * in-window rise this instead returns the rise that began the Moon's current visible arc, even
     * from the evening before, which is what lets {@link #pickMoonset} find the correct paired set.
     */
    private static ZonedDateTime pickMoonrise(List<ZonedDateTime> rises, ZonedDateTime u4) {
        return rises.stream()
                .filter(r -> !r.isAfter(u4))
                .max(Comparator.naturalOrder())
                // Defensive: physically there is always a rise within a day of u4, so this arm
                // should be unreachable in practice, but never surface a null over an empty list.
                .or(() -> rises.stream().min(Comparator.naturalOrder()))
                .orElse(null);
    }

    /**
     * The set that ends the arc {@code moonrise} began: the earliest moonset strictly after it,
     * wherever its own civil date falls. Deriving moonset from moonrise this way — rather than
     * picking each independently against the window — is what guarantees {@code moonset >
     * moonrise} whenever both are present; see the class javadoc.
     *
     * <p>{@code moonrise == null} is the one case with no arc to pair against — physically this
     * should not happen (the Moon rises roughly daily), so this falls back to the old
     * nearest-to-window heuristic: a set inside {@code [windowStart, windowEnd]} if one exists,
     * else the earliest at or after {@code windowStart}, else the latest of all.
     */
    private static ZonedDateTime pickMoonset(
            List<ZonedDateTime> sets, ZonedDateTime moonrise, ZonedDateTime windowStart, ZonedDateTime windowEnd) {
        if (moonrise != null) {
            return sets.stream()
                    .filter(s -> s.isAfter(moonrise))
                    .min(Comparator.naturalOrder())
                    .or(() -> sets.stream().max(Comparator.naturalOrder()))
                    .orElse(null);
        }
        return sets.stream()
                .filter(s -> !s.isBefore(windowStart) && !s.isAfter(windowEnd))
                .min(Comparator.naturalOrder())
                .or(() -> sets.stream().filter(s -> !s.isBefore(windowStart)).min(Comparator.naturalOrder()))
                .or(() -> sets.stream().max(Comparator.naturalOrder()))
                .orElse(null);
    }

    /** Candidate moonrise/moonset instants gathered from the (up to four) relevant civil dates. */
    private record MoonEvents(List<ZonedDateTime> rises, List<ZonedDateTime> sets) {
    }

    private static boolean withinSpan(ZonedDateTime instant, ZonedDateTime start, ZonedDateTime end) {
        return instant != null && !instant.isBefore(start) && !instant.isAfter(end);
    }

    private static LocalDateTime toLocalDateTime(ZonedDateTime instant) {
        return instant == null ? null : instant.toLocalDateTime();
    }

    /**
     * Rounds a bearing to the nearest whole degree and normalises it into {@code [0, 360)}.
     *
     * <p><b>Order matters.</b> Normalising before rounding can produce exactly {@code 360} — a raw
     * azimuth of, say, 359.6° normalises to 359.6° (already in range) and then rounds up to 360,
     * which is due north but does not read as it. Rounding first and normalising the integer result
     * avoids that: {@code Math.round(359.6) == 360}, and {@code 360 % 360 == 0}.
     */
    private static int roundedDegrees(double degrees) {
        long rounded = Math.round(degrees);
        return (int) ((rounded % (long) DEGREES_PER_CIRCLE + (long) DEGREES_PER_CIRCLE) % (long) DEGREES_PER_CIRCLE);
    }
}
