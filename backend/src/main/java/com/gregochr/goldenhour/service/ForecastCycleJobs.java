package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.SolarEvent;

import java.util.List;

/**
 * The scheduled forecast cycles, and the one place their solar rules live
 * (solar-relative-refresh-plan §2 and A3; evening-sunrise-refresh-plan §3.4).
 *
 * <p>Three jobs run a pipeline cycle ({@link #allCycleJobs()}, what the watchdog walks), of which
 * only the two main cycles may be timed against the sun ({@link #solarCapableJobs()}). The evening
 * refresh is a plain CRON job with no solar rule.
 *
 * <p>The scheduler is generic, but only the two forecast cycles are allowed to become
 * {@code SOLAR_OFFSET}: every other job's timing is a fixed-hour affair that nothing has asked to
 * move. The allow-list, each job's pipeline cycle type (for catch-up and the preview's duration
 * figure) and the plan's proposed rules are kept together here so they cannot drift apart.
 */
public final class ForecastCycleJobs {

    /** The nightly forecast cycle's job key; becomes the morning cycle. */
    public static final String NIGHTLY_JOB = "near_term_batch_evaluation";

    /** The intraday forecast cycle's job key; becomes the afternoon cycle. */
    public static final String INTRADAY_JOB = "intraday_forecast_refresh";

    /** The evening sunrise refresh's job key; a plain CRON job that may never become solar. */
    public static final String EVENING_JOB = "evening_sunrise_refresh";

    /**
     * The plan's proposed rule for the morning cycle: 255 minutes before sunrise, sized so the
     * latest sunrise of the year still finishes by 05:00 UK (owner decision, 2026-10-05).
     */
    public static final Rule NIGHTLY_RULE = new Rule(SolarEvent.SUNRISE, 255);

    /** The plan's proposed rule for the afternoon cycle: 240 minutes before sunset. */
    public static final Rule INTRADAY_RULE = new Rule(SolarEvent.SUNSET, 240);

    /**
     * A solar rule: an event and the minutes before it to fire.
     *
     * @param event         the solar event
     * @param offsetMinutes minutes before the event
     */
    public record Rule(SolarEvent event, int offsetMinutes) {
    }

    /**
     * One scheduled forecast cycle.
     *
     * @param key       the scheduler job key
     * @param cycleType the pipeline cycle it runs
     * @param rule      its proposed solar rule, or null for a job that may not become solar
     */
    public record Job(String key, CycleType cycleType, Rule rule) {
    }

    private static final Job NIGHTLY = new Job(NIGHTLY_JOB, CycleType.NIGHTLY, NIGHTLY_RULE);
    private static final Job INTRADAY = new Job(INTRADAY_JOB, CycleType.INTRADAY, INTRADAY_RULE);
    private static final Job EVENING = new Job(EVENING_JOB, CycleType.EVENING, null);

    private ForecastCycleJobs() {
    }

    /**
     * Every scheduled forecast cycle, solar-capable or not.
     *
     * @return nightly, intraday, then evening
     */
    public static List<Job> allCycleJobs() {
        return List.of(NIGHTLY, INTRADAY, EVENING);
    }

    /**
     * The cycles that may become {@code SOLAR_OFFSET}.
     *
     * @return nightly, then intraday
     */
    public static List<Job> solarCapableJobs() {
        return List.of(NIGHTLY, INTRADAY);
    }

    /**
     * Whether a job may be switched to {@code SOLAR_OFFSET}.
     *
     * @param jobKey the job key
     * @return true for the two forecast cycle jobs
     */
    public static boolean allowed(String jobKey) {
        return NIGHTLY_JOB.equals(jobKey) || INTRADAY_JOB.equals(jobKey);
    }

    /**
     * The pipeline cycle a job runs.
     *
     * @param jobKey a solar-capable job key
     * @return the cycle type, or null for any other key, the evening included (it is not solar-capable;
     *         read its type from {@link #allCycleJobs()})
     */
    public static CycleType cycleOf(String jobKey) {
        if (NIGHTLY_JOB.equals(jobKey)) {
            return CycleType.NIGHTLY;
        }
        return INTRADAY_JOB.equals(jobKey) ? CycleType.INTRADAY : null;
    }

    /**
     * The plan's proposed rule for a job.
     *
     * @param jobKey one of {@link #keys()}
     * @return the rule, or null for any other key
     */
    public static Rule proposedRule(String jobKey) {
        if (NIGHTLY_JOB.equals(jobKey)) {
            return NIGHTLY_RULE;
        }
        return INTRADAY_JOB.equals(jobKey) ? INTRADAY_RULE : null;
    }
}
