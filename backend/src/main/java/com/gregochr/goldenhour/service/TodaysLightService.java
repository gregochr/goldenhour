package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.TodaysLightResponse;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Today's light at the caller's home, as the masthead's light rule draws it.
 *
 * <p>The masthead band renders the day as a gradient and labels four of its times. This service
 * answers both from one place: it resolves the caller's saved home, asks {@link SolarService} for
 * that point's real solar times, and returns them already converted to the UK civil clock.
 *
 * <p><b>It never geocodes.</b> {@code UserSettingsService.getSettings} resolves a human-readable
 * place name through an uncached postcodes.io call, and this runs on the app's most render-heavy
 * path — the masthead, on every load of the window-first arm. So the label is built from the
 * <em>stored</em> postcode, which is a column read. That is the same reason
 * {@code getHomeLocation} exists for "Close to home".
 *
 * <p><b>No home, no light.</b> A null return is the empty state, and the masthead answers it with a
 * dim rule and a nudge to set a postcode. There is no fallback location: the alternative considered
 * was the region selected on Plan, and there is no such selection to read. Inventing one would put
 * a label on the band naming somewhere the reader never chose.
 */
@Service
public class TodaysLightService {

    /** Solar events are for UK locations, so the rule's axis is a {@code Europe/London} day. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /** The clock format the time row renders — 24-hour, zero-padded. */
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /** Minutes in a day, the denominator every stop position is a fraction of. */
    private static final double MINUTES_PER_DAY = 1440.0;

    private final SolarService solarService;
    private final UserSettingsService settingsService;
    private final Clock clock;

    /**
     * Constructs a {@code TodaysLightService}.
     *
     * @param solarService    the solar time calculator
     * @param settingsService resolves the caller's saved home
     * @param clock           supplies "today" on the UK civil calendar
     */
    public TodaysLightService(SolarService solarService, UserSettingsService settingsService, Clock clock) {
        this.solarService = solarService;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    /**
     * Today's light at the caller's home.
     *
     * @param auth the current authentication context
     * @return the day's display times and gradient stops, or null when no home postcode is saved
     *         (or the day has no sunrise or sunset to draw)
     */
    public TodaysLightResponse getTodaysLight(Authentication auth) {
        UserSettingsService.HomeLocation home = settingsService.getHomeLocation(auth);
        if (home.latitude() == null || home.longitude() == null || home.postcode() == null) {
            return null;
        }
        return buildFor(home.latitude(), home.longitude(), home.postcode(),
                ForecastHorizon.today(clock));
    }

    /**
     * Builds the response for one point and date. Package-private so the arithmetic can be tested
     * against a known coastline and date without an authentication context.
     *
     * @param lat      latitude in decimal degrees
     * @param lon      longitude in decimal degrees
     * @param postcode the caller's stored home postcode, which labels the row
     * @param date     the UK civil date to draw
     * @return the day's light, or null when it has no sunrise or sunset
     */
    TodaysLightResponse buildFor(double lat, double lon, String postcode, LocalDate date) {
        LocalDateTime sunrise = solarService.sunriseUtc(lat, lon, date);
        LocalDateTime sunset = solarService.sunsetUtc(lat, lon, date);
        // A day with no sunrise or no sunset has no rule to draw. Impossible in the UK, and the
        // honest answer rather than a 500 if a saved postcode ever geocodes outside it.
        if (sunrise == null || sunset == null) {
            return null;
        }
        // A twilight boundary that does not bracket its own event is not a time. At 60.8°N — Unst,
        // a real UK postcode — the sun never reaches −6° at the solstice, and solar-utils answers
        // 01:00 for BOTH civil dawn and civil dusk. Taken at face value the row prints
        // "01:00 blue · 03:32 golden · 22:43 golden · 01:00 blue": the same fabricated minute
        // twice, one of them claiming to be a blue hour two and a half hours after it ended. So an
        // unusable boundary is treated exactly like a missing one.
        LocalDateTime civilDawn = usableOr(solarService.civilDawnUtc(lat, lon, date),
                t -> t.isBefore(sunrise), sunrise.minusMinutes(30));
        LocalDateTime civilDusk = usableOr(solarService.civilDuskUtc(lat, lon, date),
                t -> t.isAfter(sunset), sunset.plusMinutes(30));
        SolarService.SolarWindow morning = solarService.goldenBlueWindow(lat, lon, date, true);
        SolarService.SolarWindow evening = solarService.goldenBlueWindow(lat, lon, date, false);

        List<TodaysLightResponse.Stop> stops = ascending(List.of(
                new TodaysLightResponse.Stop("NIGHT_START", 0),
                stop("NAUTICAL_DAWN", civilDawn.minusMinutes(35), date),
                stop("CIVIL_DAWN", civilDawn, date),
                stop("SUNRISE", sunrise, date),
                stop("GOLDEN_MORNING_END", orElse(morning.goldenHourEnd(), sunrise.plusMinutes(60)), date),
                stop("SOLAR_NOON", orElse(solarService.solarNoonUtc(lat, lon, date), midpoint(sunrise, sunset)), date),
                stop("GOLDEN_EVENING_START", orElse(evening.goldenHourStart(), sunset.minusMinutes(60)), date),
                stop("SUNSET", sunset, date),
                stop("CIVIL_DUSK", civilDusk, date),
                stop("NAUTICAL_DUSK", civilDusk.plusMinutes(35), date),
                new TodaysLightResponse.Stop("NIGHT_END", 100)));

        return new TodaysLightResponse(
                "Home · " + postcode,
                postcode,
                clockOf(civilDawn),
                clockOf(sunrise),
                clockOf(sunset),
                clockOf(civilDusk),
                stops);
    }

    /**
     * A stop at the local-clock position of a UTC instant, on the axis of one local day.
     *
     * <p><b>The date is part of the position, not a formality.</b> The rule's axis is a single
     * {@code Europe/London} day, and several of these events routinely land on a neighbouring one:
     * at 59.5°N — Kirkwall, a real UK postcode — midsummer civil dusk is 00:03 the following
     * morning. Read as minutes-of-day alone that is 0.2%, which puts the last light of the day at
     * the far LEFT of the rule; {@link #ascending} then quietly drags it back up to sunset's
     * position and the evening blue hour disappears from the gradient with nothing to show for it.
     * An event on an earlier day belongs at the start of the axis and one on a later day at the
     * end, so that is where they go.
     *
     * @param key the stop's event name
     * @param utc the UTC time of that event
     * @param day the local day the rule draws
     * @return the stop
     */
    private static TodaysLightResponse.Stop stop(String key, LocalDateTime utc, LocalDate day) {
        LocalDateTime local = toLondon(utc);
        if (local.toLocalDate().isBefore(day)) {
            return new TodaysLightResponse.Stop(key, 0);
        }
        if (local.toLocalDate().isAfter(day)) {
            return new TodaysLightResponse.Stop(key, 100);
        }
        double minutes = local.toLocalTime().toSecondOfDay() / 60.0;
        return new TodaysLightResponse.Stop(key, round(clamp(minutes / MINUTES_PER_DAY * 100)));
    }

    /**
     * Forces the stop list to be non-decreasing in position.
     *
     * <p>A CSS gradient whose stops run backwards does not error — the browser silently pulls the
     * offending stop up to its predecessor, which turns a wrong number into a correct-looking
     * picture. Doing it here instead means the served payload says what is drawn. It bites on the
     * two spring/autumn days where a twilight boundary crosses local midnight and its
     * minutes-of-day wraps to the far end of the axis.
     *
     * @param stops the stops in event order
     * @return the same stops, each at least as far along as the one before it
     */
    private static List<TodaysLightResponse.Stop> ascending(List<TodaysLightResponse.Stop> stops) {
        List<TodaysLightResponse.Stop> out = new ArrayList<>(stops.size());
        double floor = 0;
        for (TodaysLightResponse.Stop s : stops) {
            double position = Math.max(floor, s.position());
            floor = position;
            out.add(new TodaysLightResponse.Stop(s.key(), position));
        }
        return out;
    }

    /**
     * Converts a UTC instant to the UK civil clock.
     *
     * @param utc the UTC time
     * @return the same instant in {@code Europe/London}
     */
    private static LocalDateTime toLondon(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(LONDON).toLocalDateTime();
    }

    /**
     * Formats a UTC instant as a UK civil clock time.
     *
     * @param utc the UTC time
     * @return {@code HH:mm} in {@code Europe/London}
     */
    private static String clockOf(LocalDateTime utc) {
        return toLondon(utc).format(CLOCK);
    }

    /**
     * The midpoint of two instants, used when solar noon is unavailable.
     *
     * @param a the earlier instant
     * @param b the later instant
     * @return the instant halfway between them
     */
    private static LocalDateTime midpoint(LocalDateTime a, LocalDateTime b) {
        return a.plusSeconds(Duration.between(a, b).getSeconds() / 2);
    }

    /**
     * The first value, or the fallback when it is null.
     *
     * @param value    the preferred value
     * @param fallback the value to use when {@code value} is null
     * @return whichever is non-null
     */
    private static LocalDateTime orElse(LocalDateTime value, LocalDateTime fallback) {
        return value != null ? value : fallback;
    }

    /**
     * The first value when it is both present and sane, or the fallback.
     *
     * @param value    the preferred value
     * @param usable   the test the value must pass to be believed
     * @param fallback the value to use when {@code value} is null or fails the test
     * @return whichever is usable
     */
    private static LocalDateTime usableOr(LocalDateTime value, Predicate<LocalDateTime> usable,
            LocalDateTime fallback) {
        return value != null && usable.test(value) ? value : fallback;
    }

    /**
     * Clamps a position onto the rule.
     *
     * @param position the raw percentage
     * @return the same value, held within 0–100
     */
    private static double clamp(double position) {
        return Math.max(0, Math.min(100, position));
    }

    /**
     * Rounds a position to two decimal places — finer than a pixel on any rule this wide, and it
     * keeps the payload free of 17-digit binary artefacts.
     *
     * @param position the raw percentage
     * @return the rounded percentage
     */
    private static double round(double position) {
        return Math.round(position * 100) / 100.0;
    }
}
