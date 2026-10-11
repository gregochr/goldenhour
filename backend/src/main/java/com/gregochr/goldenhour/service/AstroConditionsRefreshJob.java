package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.service.aurora.AuroraForecastRunService;
import com.gregochr.goldenhour.util.ForecastHorizon;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the {@code astro_conditions} table fresh: once a day it scores tonight and tomorrow night
 * for every enabled Bortle-enriched location.
 *
 * <p><b>Why a dedicated job.</b> {@link AstroConditionsService#evaluateAndPersist} was called only
 * from the hand-started synchronous engine ({@code ForecastCommandExecutor}), as a piggyback on a
 * colour run, and had no scheduler row. The scheduled pipeline never reaches that engine, so
 * production held no astro row after 2026-07-31 and the night forecast the Map tab and Ask PhotoCast
 * draw on was usually absent. This job is the schedule the service never had. It makes one batched
 * Open-Meteo request through the service and no Claude call.
 *
 * <p><b>Which nights.</b> {@code max(AuroraForecastRunService.currentNightDate(), today)} and the day
 * after, where {@code today} is the UK civil date. After nautical dawn that is simply tonight and
 * tomorrow night. ⚠️ Between midnight and nautical dawn it deliberately <em>skips the night in
 * progress</em>, which {@code currentNightDate()} alone would pick (the night named yesterday; a
 * night named {@code D} runs from {@code D}'s dusk to {@code D+1}'s dawn, so no calendar date names
 * it — see {@code docs/engineering/aurora-night-selection.md}). The reason is the data, not the
 * rule: {@code OpenMeteoClient.fetchForecastBatch} sends no {@code past_days}, so its hourly array
 * starts at 00:00 UTC today, and {@link AstroConditionsService#coversWholeNight} checks only that
 * the array's last hour reaches the dawn <em>date</em>, never that it starts at dusk. Scoring the
 * running night before dawn would therefore use its post-midnight hours alone and overwrite the
 * complete row the afternoon run wrote with a partial one. So a pre-dawn run (a manual "Run now")
 * rescores today's and tomorrow's nights and leaves the running one as the afternoon run scored it.
 * The seeded 15:30 UTC run is always in daylight, where both terms agree.
 *
 * <p><b>Rows are upserted, never deleted.</b> {@link AstroConditionsService#evaluateAndPersist}
 * loads the existing row ids for the dates and saves only the (location, date) pairs it rescored, so
 * a pair it rescores is overwritten in place. It deletes nothing: a location disabled, or stripped
 * of its Bortle class, after a run keeps its old row. Readers
 * that must not show such a place (a later phase's snapshot query) exclude those rows themselves;
 * this job does not.
 *
 * <p><b>A failed fetch is invisible to the job.</b> If the Open-Meteo batch fails, the service
 * catches it, logs a WARN, scores nothing and returns 0 with nothing written, exactly as it does when
 * there are no dark-sky locations. The job cannot tell "no locations" from "fetch failed", so it
 * completes the run as 0 scored with a WARN and the previous rows stand. A location-date the service
 * cannot score (no forecast for that place, a truncated night) is skipped the same way.
 *
 * <p><b>Job-run accounting.</b> {@code RunType.WEATHER} with no evaluation model: a non-Claude
 * weather-derived refresh, the run type the {@code job_run} column documents as carrying a null
 * model. {@code succeeded} counts location-dates written. If the service throws (a database failure
 * on the write), {@code failed} counts the nights the run was asked to score, the exception message
 * goes in the run's notes, and the previous rows stand.
 */
@Service
public class AstroConditionsRefreshJob {

    private static final Logger LOG = LoggerFactory.getLogger(AstroConditionsRefreshJob.class);

    /** Scheduler key; matches the {@code scheduler_job_config} row seeded by V170. */
    static final String JOB_KEY = "astro_conditions_refresh";

    /** Longest failure message kept in the job run's {@code notes} column (length 500). */
    private static final int MAX_NOTE_LENGTH = 400;

    private final AstroConditionsService astroConditionsService;
    private final AuroraForecastRunService nightSelector;
    private final JobRunService jobRunService;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final Clock clock;

    /** Set while a run is in progress; a second fire is skipped rather than queued. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Creates the job.
     *
     * @param astroConditionsService  the template scorer and persister
     * @param nightSelector           the one owner of "which night is current"
     * @param jobRunService           job-run bookkeeping
     * @param dynamicSchedulerService the DB-backed scheduler this job registers with
     * @param clock                   clock resolving "today" on the UK civil calendar
     */
    public AstroConditionsRefreshJob(AstroConditionsService astroConditionsService,
            AuroraForecastRunService nightSelector, JobRunService jobRunService,
            DynamicSchedulerService dynamicSchedulerService, Clock clock) {
        this.astroConditionsService = astroConditionsService;
        this.nightSelector = nightSelector;
        this.jobRunService = jobRunService;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.clock = clock;
    }

    /**
     * Registers the job with the dynamic scheduler. Manual-aware, so the Scheduler screen's
     * "Run now" is recorded on the job run as a manual trigger.
     */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerManualAwareJobTarget(JOB_KEY, this::run);
    }

    /**
     * Runs one refresh unless one is already running.
     *
     * @param manual true when fired by an administrator rather than by the schedule
     */
    public void run(boolean manual) {
        if (!running.compareAndSet(false, true)) {
            LOG.info("Astro conditions refresh already running — skipping concurrent trigger");
            return;
        }
        try {
            doRun(manual);
        } finally {
            running.set(false);
        }
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private void doRun(boolean manual) {
        // Never the running night: the batch forecast starts at UTC today, so before dawn that night
        // would be scored on its post-midnight hours alone (see the class Javadoc).
        LocalDate night = max(nightSelector.currentNightDate(), ForecastHorizon.today(clock));
        List<LocalDate> dates = List.of(night, night.plusDays(1));
        JobRunEntity jobRun = jobRunService.startRun(RunType.WEATHER, manual, null);
        try {
            int scored = astroConditionsService.evaluateAndPersist(dates);
            jobRunService.completeRun(jobRun, scored, 0, dates);
            LOG.info("Astro conditions refresh complete — {} location-date(s) scored for {} and {}",
                    scored, dates.get(0), dates.get(1));
            if (scored == 0) {
                LOG.warn("Astro conditions refresh scored nothing for {} — no dark-sky locations, or the"
                        + " forecast fetch failed and the previous rows were kept", dates);
            }
        } catch (Exception e) {
            LOG.error("Astro conditions refresh failed — previous rows kept: {}", e.getMessage(), e);
            String message = String.valueOf(e.getMessage());
            jobRun.setNotes(message.length() > MAX_NOTE_LENGTH
                    ? message.substring(0, MAX_NOTE_LENGTH) : message);
            jobRunService.completeRun(jobRun, 0, dates.size(), dates);
        }
    }
}
