package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.util.ForecastHorizon;
import com.gregochr.goldenhour.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Fires a job a fixed number of minutes before sunrise or sunset
 * (solar-relative-refresh-plan A2).
 *
 * <p>This trigger drives the two jobs that produce every forecast the app shows, so its overriding
 * rule is the plan's: <b>where it must choose between a solar fire time and <em>some</em> fire time,
 * it chooses some fire time.</b> Spring stops a job for good when {@link #nextExecution} returns
 * null, so this class never returns null and never throws. The contract, each line pinned by a test:
 * <ol>
 *   <li><b>Instants, not wall-clock times.</b> For UK civil date D, {@code fire(D) = eventInstant(D)
 *       - offset}, the event being the calculator's UTC time for D at the reference point. Nothing
 *       is computed in local civil time, so a DST change cannot create a nonexistent or doubled
 *       hour.</li>
 *   <li><b>Never before D at 00:05 UK.</b> The pipeline treats the UK civil date of its trigger as
 *       "today"; a morning cycle firing at 23:59 on D-1 would evaluate events that have already
 *       happened. The fire is clamped up to 00:05 on D.</li>
 *   <li><b>Never null.</b> If the solar calculation throws, returns null, or returns the midnight
 *       sentinel (solar-utils returns midnight-UTC of the date when an event does not occur), the
 *       next execution of the job's stored cron (evaluated in UTC) is returned instead, with a
 *       WARN. If even that is impossible (no valid cron, which the switch endpoint prevents), the
 *       trigger returns a time {@value #LAST_RESORT_HOURS} hours on with an ERROR: a stale forecast
 *       once a day beats a job that is never scheduled again, and beats a tight loop.</li>
 *   <li><b>Strictly after the last scheduled execution</b> as well as after now, so the clamp, the
 *       fallback or a clock adjustment can never make two candidates coincide into a double
 *       fire.</li>
 *   <li><b>Sanity bounds.</b> A sunrise fire must fall in [00:05, 06:00] UK and a sunset fire in
 *       [10:00, 20:00] UK; outside that the cron is used (WARN). At the default reference point the
 *       real ranges are well inside, so the bounds only ever catch a bug.</li>
 *   <li><b>One INFO line per computed fire</b>: job, event, event instant, offset, fire instant and
 *       whether it was clamped or fell back.</li>
 * </ol>
 *
 * <p>The class is pure: it reads the clock only from the {@link TriggerContext}, so tests drive it
 * with a fixed clock. {@link #fireFor} and {@link #nextAfter} do the arithmetic without logging and
 * are what the admin preview and the catch-up policy call, so no surface can disagree with what
 * will actually fire.
 */
public class SolarOffsetTrigger implements Trigger {

    private static final Logger LOG = LoggerFactory.getLogger(SolarOffsetTrigger.class);

    /** The UK civil calendar. */
    static final ZoneId LONDON = ZoneId.of("Europe/London");

    /** Earliest fire on a UK day (rule 2). */
    static final LocalTime EARLIEST_LOCAL = LocalTime.of(0, 5);

    /** Latest sane sunrise fire, UK local time (rule 5). */
    static final LocalTime LATEST_SUNRISE_FIRE = LocalTime.of(6, 0);

    /** Earliest sane sunset fire, UK local time (rule 5). */
    static final LocalTime EARLIEST_SUNSET_FIRE = LocalTime.of(10, 0);

    /** Latest sane sunset fire, UK local time (rule 5). */
    static final LocalTime LATEST_SUNSET_FIRE = LocalTime.of(20, 0);

    /** How far on the last-resort time is, when no solar time and no cron are available. */
    static final int LAST_RESORT_HOURS = 24;

    /** How many UK days ahead the search looks for a fire after the threshold. */
    private static final int MAX_DAYS_SEARCHED = 10;

    private static final ZoneOffset UTC = ZoneOffset.UTC;

    /** Supplies the calculator's UTC event time for a date at the reference point. */
    @FunctionalInterface
    public interface EventSource {

        /**
         * The UTC time of a solar event on a date.
         *
         * @param event the event
         * @param date  the date
         * @return the UTC event time; the calculator's midnight sentinel when the event does not
         *         occur
         */
        LocalDateTime utc(SolarEvent event, LocalDate date);
    }

    /**
     * One day's computed fire.
     *
     * @param day           the UK civil date
     * @param eventInstant  the solar event instant, or null when the calculation failed
     * @param fire          the fire instant, or null when the solar calculation was unusable
     * @param clamped       whether the fire was moved up to 00:05 UK (rule 2)
     * @param fallbackReason why the solar fire is unusable, or null when it is usable
     */
    public record Fire(LocalDate day, Instant eventInstant, Instant fire, boolean clamped,
            String fallbackReason) {

        /**
         * Whether this day has a usable solar fire.
         *
         * @return true when {@link #fire()} is the solar fire time
         */
        public boolean usable() {
            return fallbackReason == null;
        }
    }

    /**
     * The next execution, with how it was reached.
     *
     * @param instant        the next execution
     * @param fire           the solar day it came from, or the failing day when it fell back
     * @param fallback       whether the instant is the cron's (or the last resort) rather than solar
     * @param fallbackReason why, when {@code fallback}
     */
    public record Next(Instant instant, Fire fire, boolean fallback, String fallbackReason) {
    }

    private final String jobKey;
    private final SolarEvent event;
    private final int offsetMinutes;
    private final EventSource source;
    private final CronExpression cron;

    /**
     * Creates a trigger.
     *
     * @param jobKey        the job key, for logs
     * @param event         the solar event
     * @param offsetMinutes minutes before the event to fire, 0..720
     * @param storedCron    the job's stored cron, the fallback; an invalid or blank one leaves only
     *                      the last resort
     * @param source        the calculator
     * @throws IllegalArgumentException if the event is null or the offset is outside 0..720
     */
    public SolarOffsetTrigger(String jobKey, SolarEvent event, int offsetMinutes, String storedCron,
            EventSource source) {
        if (event == null) {
            throw new IllegalArgumentException("a solar trigger needs a solar event");
        }
        if (offsetMinutes < 0 || offsetMinutes > 720) {
            throw new IllegalArgumentException("solar offset must be 0..720 minutes, got "
                    + offsetMinutes);
        }
        this.jobKey = jobKey;
        this.event = event;
        this.offsetMinutes = offsetMinutes;
        this.source = source;
        this.cron = parseCron(storedCron);
    }

    private static CronExpression parseCron(String storedCron) {
        if (storedCron == null || storedCron.isBlank()) {
            return null;
        }
        try {
            return CronExpression.parse(storedCron);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The solar event this trigger is timed against.
     *
     * @return the event
     */
    public SolarEvent event() {
        return event;
    }

    /**
     * The offset in minutes before the event.
     *
     * @return minutes
     */
    public int offsetMinutes() {
        return offsetMinutes;
    }

    /**
     * Computes one UK day's fire (rules 1, 2 and 5), without logging.
     *
     * <p>Never throws: any failure of the calculation is reported as an unusable {@link Fire}.
     *
     * @param day the UK civil date
     * @return the day's fire
     */
    public Fire fireFor(LocalDate day) {
        LocalDateTime eventUtc;
        try {
            eventUtc = source.utc(event, day);
        } catch (RuntimeException e) {
            return unusable(day, null, "the solar calculation threw " + e.getClass().getSimpleName());
        }
        if (eventUtc == null) {
            return unusable(day, null, "the solar calculation returned no time");
        }
        Instant eventInstant = eventUtc.toInstant(UTC);
        if (eventUtc.equals(day.atStartOfDay())) {
            return unusable(day, eventInstant, "the event does not occur on this date "
                    + "(the calculator returned its midnight sentinel)");
        }
        Instant fire = eventInstant.minus(Duration.ofMinutes(offsetMinutes));
        Instant earliest = day.atTime(EARLIEST_LOCAL).atZone(LONDON).toInstant();
        boolean clamped = fire.isBefore(earliest);
        if (clamped) {
            fire = earliest;
        }
        LocalTime local = fire.atZone(LONDON).toLocalTime();
        LocalTime min = event == SolarEvent.SUNRISE ? EARLIEST_LOCAL : EARLIEST_SUNSET_FIRE;
        LocalTime max = event == SolarEvent.SUNRISE ? LATEST_SUNRISE_FIRE : LATEST_SUNSET_FIRE;
        if (local.isBefore(min) || local.isAfter(max)) {
            return unusable(day, eventInstant, "the fire time " + local + " UK is outside the sane "
                    + "range " + min + "-" + max + " for a " + event.name().toLowerCase());
        }
        return new Fire(day, eventInstant, fire, clamped, null);
    }

    private static Fire unusable(LocalDate day, Instant eventInstant, String reason) {
        return new Fire(day, eventInstant, null, false, reason);
    }

    /**
     * Computes the next fire strictly after both instants (rule 4), without logging, and never
     * returns null (rule 3).
     *
     * @param now  the current instant
     * @param last the last scheduled execution, or null
     * @return the next execution
     */
    public Next nextAfter(Instant now, Instant last) {
        Instant threshold = last != null && last.isAfter(now) ? last : now;
        LocalDate day = ForecastHorizon.civilDate(threshold);
        for (int i = 0; i < MAX_DAYS_SEARCHED; i++) {
            Fire fire = fireFor(day.plusDays(i));
            if (!fire.usable()) {
                return fallback(threshold, fire);
            }
            if (fire.fire().isAfter(threshold)) {
                return new Next(fire.fire(), fire, false, null);
            }
        }
        return fallback(threshold, unusable(day, null,
                "no solar fire after " + threshold + " within " + MAX_DAYS_SEARCHED + " days"));
    }

    private Next fallback(Instant threshold, Fire failed) {
        if (cron != null) {
            LocalDateTime next = cron.next(LocalDateTime.ofInstant(threshold, UTC));
            if (next != null) {
                return new Next(next.toInstant(UTC), failed, true, failed.fallbackReason());
            }
        }
        return new Next(threshold.plus(Duration.ofHours(LAST_RESORT_HOURS)), failed, true,
                failed.fallbackReason() + "; and no stored cron could supply a time");
    }

    @Override
    public Instant nextExecution(TriggerContext triggerContext) {
        Instant now = triggerContext.getClock().instant();
        Instant last = triggerContext.lastScheduledExecution();
        Next next;
        try {
            next = nextAfter(now, last);
        } catch (RuntimeException e) {
            // Unreachable by construction (every step above is guarded); a Trigger that throws or
            // returns null would stop the job for good, so even this is turned into a time.
            LOG.error("Solar trigger for '{}' failed unexpectedly; using the last resort",
                    LogSanitizer.sanitize(jobKey), e);
            Instant threshold = last != null && last.isAfter(now) ? last : now;
            Fire failed = unusable(ForecastHorizon.civilDate(threshold), null, "unexpected error");
            next = fallback(threshold, failed);
        }
        log(next);
        return next.instant();
    }

    private void log(Next next) {
        Fire fire = next.fire();
        if (next.fallback()) {
            LOG.warn("Solar trigger '{}' ({} - {} min) fell back to {}: {}",
                    LogSanitizer.sanitize(jobKey), event, offsetMinutes, next.instant(),
                    LogSanitizer.sanitize(next.fallbackReason()));
        } else {
            LOG.info("Solar trigger '{}': event={} eventInstant={} offset={}min fire={} "
                    + "clamped={} fallback=false", LogSanitizer.sanitize(jobKey), event,
                    fire.eventInstant(), offsetMinutes, next.instant(), fire.clamped());
        }
    }
}
