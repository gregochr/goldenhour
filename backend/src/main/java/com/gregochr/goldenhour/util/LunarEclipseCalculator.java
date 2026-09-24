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
 * <h2>Moonrise and moonset across a civil-date boundary</h2>
 *
 * <p>{@link MoonriseMoonsetCalculator} answers for one London civil date at a time, but an umbral
 * span can straddle local midnight (a late-evening U1 with an after-midnight U4, or vice versa), so
 * this class always checks both the London civil date of U1 and — when it differs — the London
 * civil date of U4, and takes whichever candidate moonrise/moonset actually falls inside or nearest
 * the span. Checking only one date is exactly the bug this would otherwise reproduce: it would
 * silently return the wrong day's moonset for a span that crosses the boundary.
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
        ZonedDateTime moonset = events.pick(events.sets, u1London, u4London);
        ZonedDateTime moonrise = events.pick(events.rises, u1London, u4London);

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
     * Every moonrise/moonset candidate on the London civil date(s) spanning {@code [u1, u4]}.
     */
    private MoonEvents moonEventsNear(ZonedDateTime u1London, ZonedDateTime u4London, double lat, double lon) {
        LocalDate startDate = u1London.toLocalDate();
        LocalDate endDate = u4London.toLocalDate();

        List<ZonedDateTime> rises = new ArrayList<>();
        List<ZonedDateTime> sets = new ArrayList<>();
        addEvents(rises, sets, startDate, lat, lon);
        if (!endDate.equals(startDate)) {
            addEvents(rises, sets, endDate, lat, lon);
        }
        return new MoonEvents(rises, sets);
    }

    private void addEvents(List<ZonedDateTime> rises, List<ZonedDateTime> sets, LocalDate date,
            double lat, double lon) {
        MoonriseMoonset events = moonriseMoonsetCalculator.calculate(date, lat, lon, LONDON);
        events.moonrise().ifPresent(rises::add);
        events.moonset().ifPresent(sets::add);
    }

    /** Candidate moonrise/moonset instants gathered from the one or two relevant civil dates. */
    private record MoonEvents(List<ZonedDateTime> rises, List<ZonedDateTime> sets) {

        /**
         * The candidate that falls inside {@code [windowStart, windowEnd]} if one exists; failing
         * that, the earliest candidate at or after {@code windowStart}; failing that, the latest
         * candidate of all. Null when there are no candidates at all.
         */
        private ZonedDateTime pick(List<ZonedDateTime> candidates, ZonedDateTime windowStart, ZonedDateTime windowEnd) {
            return candidates.stream()
                    .filter(c -> !c.isBefore(windowStart) && !c.isAfter(windowEnd))
                    .min(Comparator.naturalOrder())
                    .or(() -> candidates.stream()
                            .filter(c -> !c.isBefore(windowStart))
                            .min(Comparator.naturalOrder()))
                    .or(() -> candidates.stream().max(Comparator.naturalOrder()))
                    .orElse(null);
        }
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
