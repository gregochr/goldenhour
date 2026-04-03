package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.model.TonightWindow;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.solarutils.SolarCalculator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Scheduled job that polls NOAA SWPC and drives the aurora notification lifecycle.
 *
 * <p>Runs on a fixed delay (configurable via {@code aurora.poll-interval-minutes}, default 5 min).
 * A fixed-delay schedule prevents overlap if a run takes longer than expected.
 *
 * <p>Two trigger paths per cycle:
 * <ol>
 *   <li><b>Forecast lookahead</b> — runs any time, day or night. Checks the NOAA Kp forecast
 *       for tonight's dark period (nautical dusk → nautical dawn). Fires a NOTIFY during
 *       daylight hours so the user can plan ahead. Uses
 *       {@link AuroraOrchestrator#runForecastLookahead(TonightWindow)}.</li>
 *   <li><b>Real-time</b> — only runs after nautical twilight. Checks current Kp and OVATION
 *       probability. Confirms, escalates, or clears a daytime forecast alert once darkness
 *       falls. Uses {@link AuroraOrchestrator#run()}.</li>
 * </ol>
 *
 * <p>Both paths share the same {@link AuroraStateCache}, so a daytime forecast NOTIFY
 * suppresses an evening real-time check at the same level (no duplicate alert). A real-time
 * escalation (e.g. Kp 5 forecast → actual Kp 7) produces a second NOTIFY correctly.
 */
@Component
public class AuroraPollingJob {

    private static final Logger LOG = LoggerFactory.getLogger(AuroraPollingJob.class);

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

    /**
     * Constructs the polling job.
     *
     * @param orchestrator            aurora orchestrator (NOAA → AlertLevel → score)
     * @param properties              aurora configuration
     * @param solarCalculator         solar-utils calculator for twilight checks
     * @param dynamicSchedulerService the dynamic scheduler for job registration
     */
    public AuroraPollingJob(AuroraOrchestrator orchestrator,
            AuroraProperties properties,
            SolarCalculator solarCalculator,
            DynamicSchedulerService dynamicSchedulerService) {
        this.orchestrator = orchestrator;
        this.properties = properties;
        this.solarCalculator = solarCalculator;
        this.dynamicSchedulerService = dynamicSchedulerService;
    }

    /**
     * Registers the aurora polling job with the dynamic scheduler.
     */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget("aurora_polling", this::poll);
    }

    /**
     * Executes one aurora polling cycle.
     *
     * <p>The initial 60-second delay prevents NOAA API calls immediately on startup.
     * The fixed-delay schedule ensures the next poll does not begin until this one finishes.
     */
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        executePoll();
    }

    /**
     * Core polling logic, extracted for unit-testability without triggering the scheduler.
     *
     * <p>Always runs the forecast-lookahead path (no daylight gate). Only runs the real-time
     * path when it is dark (sun below nautical twilight).
     */
    void executePoll() {
        // Path 1: Forecast lookahead — runs anytime, day or night
        TonightWindow window = calculateTonightWindow();
        orchestrator.runForecastLookahead(window);

        // Path 2: Real-time — only after nautical twilight
        if (!isDaylight()) {
            orchestrator.run();
        } else {
            LOG.debug("Aurora real-time poll skipped — above nautical twilight");
        }
    }

    /**
     * Calculates tonight's dark period as a {@link TonightWindow}.
     *
     * <p>If the current time is before today's nautical dawn (i.e. we are currently in the
     * overnight dark period), "tonight" is yesterday's dusk to today's dawn. Otherwise
     * "tonight" is today's dusk to tomorrow's dawn.
     *
     * <p>Nautical twilight (−12°) is approximated by applying a
     * {@value #NAUTICAL_BUFFER_MINUTES}-minute buffer to the civil twilight (−6°) times
     * from {@link SolarCalculator}.
     *
     * @return the upcoming (or current) dark period
     */
    TonightWindow calculateTonightWindow() {
        ZoneId utc = ZoneId.of("UTC");
        LocalDate today = LocalDate.now(utc);
        LocalDateTime now = LocalDateTime.now(utc);

        // Today's nautical dawn: civil dawn minus the nautical buffer
        LocalDateTime nauticalDawnToday = solarCalculator
                .civilDawn(DURHAM_LAT, DURHAM_LON, today, utc)
                .minusMinutes(NAUTICAL_BUFFER_MINUTES);

        if (now.isBefore(nauticalDawnToday)) {
            // We are in the current overnight dark period (after yesterday's dusk, before dawn)
            LocalDateTime nauticalDuskYesterday = solarCalculator
                    .civilDusk(DURHAM_LAT, DURHAM_LON, today.minusDays(1), utc)
                    .plusMinutes(NAUTICAL_BUFFER_MINUTES);
            return new TonightWindow(
                    nauticalDuskYesterday.atZone(utc),
                    nauticalDawnToday.atZone(utc));
        }

        // Daytime or evening: tonight starts at today's dusk and ends at tomorrow's dawn
        LocalDateTime nauticalDuskToday = solarCalculator
                .civilDusk(DURHAM_LAT, DURHAM_LON, today, utc)
                .plusMinutes(NAUTICAL_BUFFER_MINUTES);
        LocalDateTime nauticalDawnTomorrow = solarCalculator
                .civilDawn(DURHAM_LAT, DURHAM_LON, today.plusDays(1), utc)
                .minusMinutes(NAUTICAL_BUFFER_MINUTES);
        return new TonightWindow(
                nauticalDuskToday.atZone(utc),
                nauticalDawnTomorrow.atZone(utc));
    }

    /**
     * Returns {@code true} if it is currently too bright for aurora at Durham, UK.
     *
     * <p>Uses civil twilight (−6°) times from {@link SolarCalculator} and adds a
     * {@value #NAUTICAL_BUFFER_MINUTES}-minute buffer on each side to approximate
     * nautical twilight (−12°). Aurora requires proper darkness.
     *
     * @return {@code true} if too bright for aurora visibility
     */
    boolean isDaylight() {
        ZoneId utc = ZoneId.of("UTC");
        LocalDate today = LocalDate.now(utc);
        LocalDateTime dawn = solarCalculator.civilDawn(DURHAM_LAT, DURHAM_LON, today, utc);
        LocalDateTime dusk = solarCalculator.civilDusk(DURHAM_LAT, DURHAM_LON, today, utc);
        LocalDateTime now = LocalDateTime.now(utc);
        return now.isAfter(dawn.minusMinutes(NAUTICAL_BUFFER_MINUTES))
                && now.isBefore(dusk.plusMinutes(NAUTICAL_BUFFER_MINUTES));
    }
}
