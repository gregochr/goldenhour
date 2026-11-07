package com.gregochr.goldenhour.service;

import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.LunarPosition;
import com.gregochr.solarutils.MoonriseMoonset;
import com.gregochr.solarutils.MoonriseMoonsetCalculator;
import com.gregochr.solarutils.SolarCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether the moon spoils a meteor shower, by asking whether a bright moon is <em>up while
 * the shower is worth watching</em> rather than how lit the moon is at noon.
 *
 * <p>The old gate compared the moon's illumination at noon UTC with 50%. That is wrong in both
 * directions: a half-lit moon that has set before the shower's best hours costs nothing (the
 * Leonids of 17 Nov 2026: 50.1% lit at noon, set about 23:35, best 03:00 to dawn), and a moon that
 * is up and bright through the best hours spoils it whatever the noon figure said.
 *
 * <p><strong>The window.</strong> {@link MeteorHotTopicStrategy.Shower#bestHours()} is parsed into
 * a window on the shower's night ({@code peak} is the evening date): {@code "HH:mm–HH:mm"} or
 * {@code "HH:mm–dawn"}. An hour of 12 or later falls on the evening date, an earlier hour on the
 * next morning; {@code dawn} is civil dawn at the reference point on the morning date. All clock
 * times are {@code Europe/London}. Both ends are built with the zone's rules, so the night the
 * clocks change still samples its true elapsed time.
 *
 * <p><strong>The rule.</strong> The window is sampled every {@value #SAMPLE_MINUTES} minutes, both
 * ends inclusive. A sample is <em>bright-moon-up</em> when illumination is at least
 * {@value #BRIGHT_ILLUMINATION} and the moon's altitude is above the horizon. The shower is
 * <em>washed out</em> when bright-moon-up samples are more than half of all samples; exactly half
 * is not washed out. The altitude is the geometric altitude of the moon's centre, with no
 * refraction correction, so it can disagree with {@link MoonriseMoonsetCalculator} by a minute or
 * two at the horizon; a sample exactly on the horizon counts as down.
 *
 * <p><strong>Fail soft.</strong> A {@code bestHours} string that cannot be parsed, or a
 * {@code dawn} that comes at or before the start, falls back to the old noon rule (illumination of
 * at least {@value #BRIGHT_ILLUMINATION} at noon UTC means washed out) and logs a warning, so one
 * bad string can never throw out of the hot-topic aggregator.
 *
 * <p>The reported figure is the mean illumination over the window, so the number shown matches the
 * night rather than noon.
 */
@Component
public class MeteorMoonGate {

    private static final Logger LOG = LoggerFactory.getLogger(MeteorMoonGate.class);

    /**
     * Lunar illumination percent below which the sky is genuinely dark for meteors. Shared with the
     * hot-topic chip and the almanac caveat so all three agree on what "dark" means.
     */
    static final int DARK_MOON_PCT = 25;

    /** Reference latitude: the centre of the region the app serves (northern England). */
    private static final double REFERENCE_LAT = 54.8;

    /**
     * Reference longitude, degrees east. The moon's altitude differs by well under a degree across
     * the region, so one point serves all of it.
     */
    private static final double REFERENCE_LON = -2.0;

    /** Shower hours are quoted as UK civil time. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /** Minutes between samples of the moon over the window. */
    private static final int SAMPLE_MINUTES = 15;

    /** Illumination fraction at or above which the moon is bright enough to wash a shower out. */
    private static final double BRIGHT_ILLUMINATION = 0.5;

    /** Hour of day from which a clock time in {@code bestHours} belongs to the evening date. */
    private static final int EVENING_FROM_HOUR = 12;

    private static final double PERCENT = 100.0;

    private static final Pattern BEST_HOURS =
            Pattern.compile("^(\\d{2}):(\\d{2})\\u2013(?:(\\d{2}):(\\d{2})|dawn)$");

    /**
     * What the moon does to a shower's best hours.
     *
     * @param illuminationPct mean illumination over the window, rounded to a whole percent
     * @param washedOut       true when a bright moon is up for more than half of the window
     * @param downThroughout  true when the moon is below the horizon for the whole window
     * @param moonset         UK local time the moon sets inside the window, when it does
     * @param moonrise        UK local time the moon rises inside the window, when it does
     */
    public record MoonOverShower(int illuminationPct, boolean washedOut, boolean downThroughout,
            Optional<LocalTime> moonset, Optional<LocalTime> moonrise) { }

    private final LunarCalculator lunarCalculator;
    private final SolarCalculator solarCalculator;
    private final MoonriseMoonsetCalculator moonriseMoonsetCalculator;

    /**
     * Constructs a {@code MeteorMoonGate}.
     *
     * @param lunarCalculator           moon altitude and illumination
     * @param solarCalculator           civil dawn, the end of "HH:mm–dawn" windows
     * @param moonriseMoonsetCalculator moonrise and moonset times inside the window
     */
    public MeteorMoonGate(LunarCalculator lunarCalculator, SolarCalculator solarCalculator,
            MoonriseMoonsetCalculator moonriseMoonsetCalculator) {
        this.lunarCalculator = lunarCalculator;
        this.solarCalculator = solarCalculator;
        this.moonriseMoonsetCalculator = moonriseMoonsetCalculator;
    }

    /**
     * Assesses the moon over a shower's best hours on a given night.
     *
     * @param shower the shower, whose {@code bestHours} gives the window
     * @param night  the evening date of the night of maximum
     * @return the assessment; never throws, falling back to the noon rule on an unusable window
     */
    public MoonOverShower assess(MeteorHotTopicStrategy.Shower shower, LocalDate night) {
        try {
            ZonedDateTime[] window = parseWindow(shower.bestHours(), night);
            if (window != null) {
                return evaluate(window[0], window[1]);
            }
        } catch (RuntimeException e) {
            LOG.warn("Meteor moon gate: could not evaluate '{}' for {}: {}",
                    shower.bestHours(), night, e.toString());
            return noonRule(night);
        }
        LOG.warn("Meteor moon gate: unusable best hours '{}' for {}, using the noon rule",
                shower.bestHours(), night);
        return noonRule(night);
    }

    /**
     * Evaluates the moon over an explicit window.
     *
     * @param start the first instant of the window
     * @param end   the last instant of the window (inclusive)
     * @return the assessment
     */
    MoonOverShower evaluate(ZonedDateTime start, ZonedDateTime end) {
        int samples = 0;
        int brightUp = 0;
        int up = 0;
        double illuminationSum = 0;
        for (ZonedDateTime t = start; !t.isAfter(end); t = t.plusMinutes(SAMPLE_MINUTES)) {
            LunarPosition moon = lunarCalculator.calculate(t, REFERENCE_LAT, REFERENCE_LON);
            samples++;
            illuminationSum += moon.illumination();
            if (moon.altitude() > 0) {
                up++;
                if (moon.illumination() >= BRIGHT_ILLUMINATION) {
                    brightUp++;
                }
            }
        }
        int pct = (int) Math.round(illuminationSum / samples * PERCENT);
        boolean washedOut = brightUp * 2 > samples;
        boolean downThroughout = up == 0;
        return new MoonOverShower(pct, washedOut, downThroughout,
                eventWithin(start, end, true), eventWithin(start, end, false));
    }

    /**
     * Number of samples {@link #evaluate} takes over a window.
     *
     * @param start the first instant
     * @param end   the last instant (inclusive)
     * @return the sample count
     */
    static int sampleCount(ZonedDateTime start, ZonedDateTime end) {
        long minutes = Duration.between(start, end).toMinutes();
        return minutes < 0 ? 0 : (int) (minutes / SAMPLE_MINUTES) + 1;
    }

    private Optional<LocalTime> eventWithin(ZonedDateTime start, ZonedDateTime end, boolean set) {
        LocalDate from = start.withZoneSameInstant(LONDON).toLocalDate();
        LocalDate to = end.withZoneSameInstant(LONDON).toLocalDate();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            MoonriseMoonset events = moonriseMoonsetCalculator.calculate(
                    date, REFERENCE_LAT, REFERENCE_LON, LONDON);
            Optional<ZonedDateTime> event = set ? events.moonset() : events.moonrise();
            if (event.isPresent() && !event.get().isBefore(start) && !event.get().isAfter(end)) {
                return Optional.of(event.get().withZoneSameInstant(LONDON)
                        .toLocalTime().truncatedTo(ChronoUnit.MINUTES));
            }
        }
        return Optional.empty();
    }

    /** The window as {start, end}, or null when the string is unusable. */
    ZonedDateTime[] parseWindow(String bestHours, LocalDate night) {
        Matcher m = BEST_HOURS.matcher(bestHours == null ? "" : bestHours);
        if (!m.matches()) {
            return null;
        }
        ZonedDateTime start = onNight(night, m.group(1), m.group(2));
        ZonedDateTime end;
        if (m.group(3) == null) {
            LocalDateTime dawn = solarCalculator.civilDawn(
                    REFERENCE_LAT, REFERENCE_LON, night.plusDays(1), LONDON);
            if (dawn == null) {
                return null;
            }
            end = dawn.atZone(LONDON);
        } else {
            end = onNight(night, m.group(3), m.group(4));
        }
        if (start == null || end == null) {
            return null;
        }
        return end.isAfter(start) ? new ZonedDateTime[] {start, end} : null;
    }

    /** The clock time on the shower's night, or null if the digits do not parse. */
    static ZonedDateTime onNight(LocalDate night, String hh, String mm) {
        try {
            int hour = Integer.parseInt(hh);
            LocalDate date = hour >= EVENING_FROM_HOUR ? night : night.plusDays(1);
            return date.atTime(LocalTime.of(hour, Integer.parseInt(mm))).atZone(LONDON);
        } catch (NumberFormatException e) {
            // The regex admits only two digits, so this is unreachable today; the catch keeps the
            // method total.
            return null;
        }
    }

    /** The old rule, kept as the fallback: bright at noon UTC means washed out. */
    private MoonOverShower noonRule(LocalDate night) {
        double illumination = lunarCalculator.calculate(
                night.atTime(LocalTime.NOON).atZone(ZoneOffset.UTC), REFERENCE_LAT, REFERENCE_LON)
                .illumination();
        return new MoonOverShower((int) Math.round(illumination * PERCENT),
                illumination >= BRIGHT_ILLUMINATION, false, Optional.empty(), Optional.empty());
    }
}
