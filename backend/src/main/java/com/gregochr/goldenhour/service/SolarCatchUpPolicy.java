package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * Decides whether a solar job that missed today's fire while the application was down should run
 * once immediately on startup (solar-relative-refresh-plan D1).
 *
 * <p>A Spring trigger computes from "now", so a restart just after a job's fire time would
 * otherwise cost a whole day with no forecast. The rule, all of which must hold:
 * <ul>
 *   <li>the job is {@code SOLAR_OFFSET} and today's (UK date) solar fire has already passed;</li>
 *   <li>no {@code pipeline_run} of the job's cycle type has a {@code trigger_time} at or after
 *       that fire, so it never double-fires and survives restarts (a RUNNING run resumed by
 *       {@code PipelineOrchestratorStartup} already has its {@code trigger_time}, so the check
 *       holds whichever of the two {@code ApplicationReadyEvent} listeners runs first);</li>
 *   <li>useful lead remains: a sunrise job only before {@value #MORNING_WINDOW_CLOSES} UK, a
 *       sunset job only while now is at least {@value #SUNSET_LEAD_MINUTES} minutes before
 *       sunset.</li>
 * </ul>
 *
 * <p>Kept out of {@link DynamicSchedulerService}, which is a generic scheduler and should not know
 * about pipeline runs. This class reads only; the scheduler decides what to do with the answer. It
 * does not catch exceptions: the caller must, so a failure here can never stop a schedule being
 * installed.
 */
@Component
public class SolarCatchUpPolicy {

    /** The morning window closes at this UK local time (D1). */
    static final String MORNING_WINDOW_CLOSES = "05:00";

    /** An afternoon catch-up needs at least this long before sunset (D1). */
    static final int SUNSET_LEAD_MINUTES = 90;

    private static final LocalTime MORNING_CLOSES = LocalTime.of(5, 0);

    private final SolarTriggerFactory triggerFactory;
    private final PipelineRunRepository pipelineRuns;
    private final Clock clock;

    /**
     * Constructs the policy.
     *
     * @param triggerFactory builds the same trigger the scheduler uses
     * @param pipelineRuns   the pipeline run history
     * @param clock          the application clock
     */
    public SolarCatchUpPolicy(SolarTriggerFactory triggerFactory,
            PipelineRunRepository pipelineRuns, Clock clock) {
        this.triggerFactory = triggerFactory;
        this.pipelineRuns = pipelineRuns;
        this.clock = clock;
    }

    /**
     * Whether a job should run once immediately, and why.
     *
     * @param config the job config
     * @return the reason to log when a catch-up run is due, empty when none is
     */
    public Optional<String> reasonToRun(SchedulerJobConfigEntity config) {
        if (config.getScheduleType() != ScheduleType.SOLAR_OFFSET || config.getSolarEvent() == null
                || config.getSolarOffsetMinutes() == null) {
            return Optional.empty();
        }
        CycleType cycle = SolarForecastJobs.cycleOf(config.getJobKey());
        if (cycle == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        LocalDate today = ForecastHorizon.civilDate(now);
        SolarOffsetTrigger.Fire fire = triggerFactory.create(config.getJobKey(),
                config.getSolarEvent(), config.getSolarOffsetMinutes(), config.getCronExpression())
                .fireFor(today);
        if (!fire.usable() || !fire.fire().isBefore(now)) {
            return Optional.empty();
        }
        if (pipelineRuns.existsByCycleTypeAndTriggerTimeGreaterThanEqual(cycle, fire.fire())) {
            return Optional.empty();
        }
        if (config.getSolarEvent() == SolarEvent.SUNRISE) {
            LocalTime local = now.atZone(SolarOffsetTrigger.LONDON).toLocalTime();
            if (!local.isBefore(MORNING_CLOSES)) {
                return Optional.empty();
            }
        } else if (now.isAfter(fire.eventInstant().minus(Duration.ofMinutes(SUNSET_LEAD_MINUTES)))) {
            return Optional.empty();
        }
        return Optional.of("today's " + config.getSolarEvent().name().toLowerCase() + " fire at "
                + fire.fire() + " passed with no " + cycle + " pipeline run since, and the catch-up "
                + "window is still open at " + now);
    }
}
