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
 * <p>Runs on a fixed delay (the {@code aurora_polling} scheduler row, 5 minutes), so a slow poll never
 * overlaps the next scheduled one. Each poll is one of two kinds, chosen by whether tonight's dark
 * window — nautical dusk to nautical dawn at Durham — has opened yet:
 * <ol>
 *   <li><b>Daylight</b> — the forecast lookahead alone, a heads-up for tonight
 *       ({@link AuroraOrchestrator#runForecastLookahead}).</li>
 *   <li><b>Dark</b> — the lookahead and then the real-time path, over one NOAA snapshot
 *       ({@link AuroraOrchestrator#runNightPoll}). The real-time path confirms, escalates or clears
 *       the alert. It reads tonight through the lookahead's own figure, so it never clears what the
 *       lookahead would raise again on the next poll.</li>
 * </ol>
 *
 * <p>Every poll reads the clock once. That one instant decides which night "tonight" is, whether it
 * has started, and how much of it is left, so those questions can never be answered at different
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
     * overlapping cycles both find it IDLE and both pay for a Claude call, and the admin route used to
     * reach it from a request thread while the schedule was mid-cycle. An {@link AtomicBoolean}
     * rather than a lock because a refusal must not wait: the schedule skips (the next poll is five
     * minutes away) and the admin route answers 409.
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
     * <p>The initial 60-second delay prevents NOAA API calls immediately on startup.
     * The fixed-delay schedule ensures the next poll does not begin until this one finishes.
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
     * Runs one polling cycle now unless one is already running. The admin route takes this directly,
     * so a manual run is the scheduled cycle itself and can never read tonight differently from it.
     * Unlike {@link #poll()} it does not consult {@code aurora.enabled}.
     *
     * @return what the cycle did, or empty if a cycle was already running
     */
    public Optional<AuroraOrchestrator.PollOutcome> runCycleIfIdle() {
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
    AuroraOrchestrator.PollOutcome executePoll() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(UTC));
        TonightWindow tonight = calculateTonightWindow(now);
        if (now.isBefore(tonight.dusk())) {
            return new AuroraOrchestrator.PollOutcome(
                    orchestrator.runForecastLookahead(tonight, now), null);
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
