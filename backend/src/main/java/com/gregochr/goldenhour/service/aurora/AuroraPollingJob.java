package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.solarutils.SolarCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
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
 * <p>The polling sequence:
 * <ol>
 *   <li>Skip if the sun is above nautical twilight (−12°) at a representative UK location —
 *       aurora is not visible until it is properly dark.</li>
 *   <li>Delegate to {@link AuroraOrchestrator#run()}, which fetches NOAA data, derives the
 *       {@link com.gregochr.goldenhour.entity.AlertLevel}, evaluates the state machine, and
 *       (on NOTIFY) scores locations via Claude.</li>
 * </ol>
 */
@Component
public class AuroraPollingJob {

    private static final Logger LOG = LoggerFactory.getLogger(AuroraPollingJob.class);

    /**
     * Durham, UK — representative UK reference latitude for the nautical twilight check.
     * Aurora is not visible until the sun is below −12° altitude at northern UK latitudes.
     */
    private static final double DURHAM_LAT = 54.776;

    /** Durham longitude. */
    private static final double DURHAM_LON = -1.575;

    /**
     * Buffer in minutes added before civil dawn and after civil dusk to approximate
     * nautical twilight (−12°). Civil twilight is at −6°; the gap to −12° is ~30–40 min
     * at northern UK latitudes across all seasons.
     */
    private static final int NAUTICAL_BUFFER_MINUTES = 35;

    private final AuroraOrchestrator orchestrator;
    private final AuroraProperties properties;
    private final SolarCalculator solarCalculator;

    /**
     * Constructs the polling job.
     *
     * @param orchestrator    aurora orchestrator (NOAA → AlertLevel → score)
     * @param properties      aurora configuration
     * @param solarCalculator solar-utils calculator for twilight check
     */
    public AuroraPollingJob(AuroraOrchestrator orchestrator,
            AuroraProperties properties,
            SolarCalculator solarCalculator) {
        this.orchestrator = orchestrator;
        this.properties = properties;
        this.solarCalculator = solarCalculator;
    }

    /**
     * Executes one aurora polling cycle.
     *
     * <p>The initial 60-second delay prevents NOAA API calls immediately on startup.
     * The fixed-delay schedule ensures the next poll does not begin until this one finishes.
     */
    @Scheduled(fixedDelayString = "PT${aurora.poll-interval-minutes:5}M",
               initialDelayString = "PT1M")
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        executePoll();
    }

    /**
     * Core polling logic, extracted for unit-testability without triggering the scheduler.
     */
    void executePoll() {
        if (isDaylight()) {
            LOG.debug("Aurora poll skipped — above nautical twilight");
            return;
        }
        orchestrator.run();
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
