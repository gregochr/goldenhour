package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.model.TonightWindow;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.solarutils.SolarCalculator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled job that polls NOAA SWPC and drives the aurora alert lifecycle.
 *
 * <p>Runs on the {@code aurora_polling} scheduler row's fixed delay (5 minutes). Each poll is one of
 * two kinds, chosen by whether tonight's dark window (nautical dusk to nautical dawn at Durham) has
 * opened yet, and each evaluates the state machine at most once:
 * <ol>
 *   <li><b>Daylight</b>: the forecast for tonight, a heads-up
 *       ({@link AuroraOrchestrator#runForecastLookahead}).</li>
 *   <li><b>Dark</b>: the higher of the forecast for the rest of tonight and the conditions now, over
 *       one NOAA snapshot ({@link AuroraOrchestrator#runNightPoll}). It raises, escalates or clears
 *       the alert, and never drops below the forecast, so a heads-up survives the evening.</li>
 * </ol>
 *
 * <p>The job reads its clock once per poll. That one instant decides which night "tonight" is,
 * whether it has started, and how much of it is left, so those can never be answered at different
 * moments.
 */
@Component
public class AuroraPollingJob {

    private static final Logger LOG = LoggerFactory.getLogger(AuroraPollingJob.class);

    private static final ZoneId UTC = ZoneId.of("UTC");

    /**
     * Durham, UK — representative UK reference latitude for twilight checks.
     */
    private static final double DURHAM_LAT = 54.776;

    /** Durham longitude. */
    private static final double DURHAM_LON = -1.575;

    /**
     * Buffer in minutes added before civil dawn and after civil dusk to approximate
     * nautical twilight (−12°). Civil twilight is at −6°; the gap to −12° is ~30–40 min
     * at northern UK latitudes across all seasons.
     */
    static final int NAUTICAL_BUFFER_MINUTES = 35;

    private final AuroraOrchestrator orchestrator;
    private final AuroraProperties properties;
    private final SolarCalculator solarCalculator;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final Clock clock;

    /**
     * True while a cycle is running, whichever route started it: the {@code aurora_polling} schedule
     * and the Scheduler screen's Run Now (both {@link #poll()}), or the admin
     * {@code POST /api/aurora/admin/run} ({@link #runCycleIfIdle()}).
     *
     * <p>{@link AuroraStateCache#evaluate} is a read-check-write that assumes one writer. Two
     * overlapping cycles both find it IDLE and both pay for a Claude call. Cycles could overlap: the
     * admin route reached the state machine from a request thread whenever it was called, and the
     * scheduler itself can start a poll while one is running — Run Now starts one at once, and
     * saving a new schedule for an active job (the Scheduler screen's Save) or resuming it re-arms
     * the job with an immediate first run, while re-arming cancels the old schedule without
     * interrupting the cycle it is running. A refusal is a skip for the schedule (the next poll is
     * five minutes away) and a 409 for the admin route.
     *
     * <p>An {@link AtomicBoolean} rather than a lock because nothing about a cycle belongs to a
     * thread: unlike a {@code ReentrantLock}, a second attempt from the thread already running a
     * cycle is refused like any other.
     */
    private final AtomicBoolean cycleRunning = new AtomicBoolean(false);

    /**
     * Constructs the polling job.
     *
     * @param orchestrator            aurora orchestrator (NOAA → AlertLevel → score)
     * @param properties              aurora configuration
     * @param solarCalculator         solar-utils calculator for twilight checks
     * @param dynamicSchedulerService the dynamic scheduler for job registration
     * @param clock                   the clock each poll reads its one instant from
     */
    public AuroraPollingJob(AuroraOrchestrator orchestrator,
            AuroraProperties properties,
            SolarCalculator solarCalculator,
            DynamicSchedulerService dynamicSchedulerService,
            Clock clock) {
        this.orchestrator = orchestrator;
        this.properties = properties;
        this.solarCalculator = solarCalculator;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.clock = clock;
    }

    /**
     * Registers the aurora polling job with the dynamic scheduler.
     */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget("aurora_polling", this::poll);
    }

    /**
     * The scheduler's target: one polling cycle, unless {@code aurora.enabled} is off or a cycle is
     * already running (see {@link #cycleRunning}). A skipped poll is only logged — the next is five
     * minutes away.
     *
     * <p>The fixed delay keeps one scheduled run from starting before the previous one finishes.
     * The first run is immediate: {@code DynamicSchedulerService} schedules fixed-delay jobs with no
     * initial delay, so the {@code initial_delay_ms} the V68 seed carries is not applied.
     */
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        if (runCycleIfIdle().isEmpty()) {
            LOG.warn("Aurora poll skipped — a cycle is already running");
        }
    }

    /**
     * Runs one polling cycle now, on the calling thread, unless one is already running. The admin
     * route takes this directly, so a manual run is the scheduled cycle itself and can never read
     * tonight differently from it. Unlike {@link #poll()} it does not consult
     * {@code aurora.enabled}.
     *
     * @return what the cycle did, or empty if a cycle was already running
     */
    public Optional<AuroraPollOutcome> runCycleIfIdle() {
        if (!cycleRunning.compareAndSet(false, true)) {
            return Optional.empty();
        }
        try {
            return Optional.of(executePoll());
        } finally {
            cycleRunning.set(false);
        }
    }

    /**
     * Core polling logic, extracted for unit-testability without triggering the scheduler.
     *
     * <p>It is daylight until tonight's window opens, so the window that names tonight also decides
     * which kind of poll this is — one computation, from one instant. At nautical dawn itself the
     * night is over (the window has moved on to the evening), which is the answer
     * {@link AuroraForecastRunService#currentNightDate()} gives at that instant too.
     *
     * @return what the poll did
     */
    AuroraPollOutcome executePoll() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(UTC));
        TonightWindow tonight = calculateTonightWindow(now);
        if (now.isBefore(tonight.dusk())) {
            return orchestrator.runForecastLookahead(tonight, now);
        }
        return orchestrator.runNightPoll(tonight, now);
    }

    /**
     * Calculates tonight's dark period, as a {@link TonightWindow}, for the instant {@code now}.
     *
     * <p>If {@code now} is before that day's nautical dawn (i.e. inside the overnight dark period),
     * "tonight" is the previous day's dusk to that day's dawn. Otherwise "tonight" is that day's
     * dusk to the next day's dawn. The date is scaffolding for the solar calculations; the
     * {@code isBefore} test on the instant makes the choice.
     *
     * <p>Nautical twilight (−12°) is approximated by applying a
     * {@value #NAUTICAL_BUFFER_MINUTES}-minute buffer to the civil twilight (−6°) times
     * from {@link SolarCalculator}.
     *
     * @param now the instant of the poll
     * @return the dark period {@code now} is inside, or the next one if it is daylight
     */
    TonightWindow calculateTonightWindow(ZonedDateTime now) {
        LocalDateTime nowUtc = now.withZoneSameInstant(UTC).toLocalDateTime();
        LocalDate today = nowUtc.toLocalDate();
        LocalDateTime nauticalDawnToday = nauticalDawn(today);

        if (nowUtc.isBefore(nauticalDawnToday)) {
            // We are in the current overnight dark period (after yesterday's dusk, before dawn)
            return new TonightWindow(
                    nauticalDusk(today.minusDays(1)).atZone(UTC),
                    nauticalDawnToday.atZone(UTC));
        }

        // Daytime or evening: tonight starts at today's dusk and ends at tomorrow's dawn
        return new TonightWindow(
                nauticalDusk(today).atZone(UTC),
                nauticalDawn(today.plusDays(1)).atZone(UTC));
    }

    private LocalDateTime nauticalDawn(LocalDate date) {
        return solarCalculator.civilDawn(DURHAM_LAT, DURHAM_LON, date, UTC)
                .minusMinutes(NAUTICAL_BUFFER_MINUTES);
    }

    private LocalDateTime nauticalDusk(LocalDate date) {
        return solarCalculator.civilDusk(DURHAM_LAT, DURHAM_LON, date, UTC)
                .plusMinutes(NAUTICAL_BUFFER_MINUTES);
    }
}
