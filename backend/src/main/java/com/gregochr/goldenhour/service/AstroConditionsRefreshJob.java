package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.service.ask.AskReadyPrecompute;
import com.gregochr.goldenhour.service.ask.ReadyQuestion;
import com.gregochr.goldenhour.service.aurora.AuroraForecastRunService;
import com.gregochr.goldenhour.util.ForecastHorizon;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the {@code astro_conditions} table fresh: once a day it scores tonight and tomorrow night
 * for every enabled Bortle-enriched location.
 *
 * <p><b>Why a dedicated job.</b> {@link AstroConditionsService#evaluateAndPersist} was called only
 * from the hand-started synchronous engine ({@code ForecastCommandExecutor}, deleted in 2026-10), as a piggyback on a
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
 *
 * <p><b>It dispatches a night-only Ask PhotoCast Ready precompute after a successful run</b> (ask-night plan
 * §2.0 (3)). The Ready {@code NIGHT_SKY} answer is versioned on the night's astro generation
 * ({@code max(run_timestamp)}), which this job's run changes; the pipeline dispatches the precompute
 * only after its own cycles (14:00, 18:00 today; a sun-relative summer afternoon cycle runs after
 * this job's 15:30, making its own NIGHT_SKY precompute redundant but harmless), so without this
 * the 14:00 answer would be withheld from 15:30 until the 18:00 cycle rewrote it. "Successful" is
 * at least one location-date scored: a run
 * that scored nothing, or threw, wrote no new generation, so there is nothing to refresh. The
 * dispatch is the pipeline orchestrator's own shape: after the job run is complete, on a background
 * executor and never on the job's thread, the precompute carrying its own 5-minute deadline, and a
 * failure to queue it or an exception inside it logged and stopped there ({@link #dispatchReady}) so
 * it can never fail the run or hold the job's guard. The precompute refuses itself, whole and
 * cheaply, when Ask is off, a simulation is active or the briefing is missing or last-known-good.
 * It asks {@link #NIGHT_ONLY} alone, the one question this run makes stale, so it costs at most one
 * conversation per scope (the enabled regions and everywhere) and leaves every other stored answer as
 * it was.
 * ⚠️ <b>It counts against {@code photocast.ask.ready.max-cycles-per-day} exactly as a scheduled
 * run does, and a manual one does not</b>: a scheduled astro run takes {@code precompute(null, NIGHT_ONLY)}, the
 * counted route, while an administrator's "Run now" takes {@code precomputeOnDemand(NIGHT_ONLY)}, the route
 * the admin precompute endpoint takes, which is a person's decision and neither counted nor stopped
 * by the ceiling, so a lever pressed locally cannot lock the nightly cycles out. (The pipeline's own
 * "Run now" counts like a scheduled run because nothing records that trigger; this job's manual flag
 * is recorded, so the existing rule can be mirrored.)
 */
@Service
public class AstroConditionsRefreshJob {

    private static final Logger LOG = LoggerFactory.getLogger(AstroConditionsRefreshJob.class);

    /** Scheduler key; matches the {@code scheduler_job_config} row seeded by V170. */
    static final String JOB_KEY = "astro_conditions_refresh";

    /**
     * The one Ready question this job's run makes stale (it is versioned on this run's astro generation).
     * The precompute it dispatches asks this alone: every other question's stored answer is untouched, so
     * the cost is at most one conversation per scope, not a full precompute.
     */
    static final Set<ReadyQuestion> NIGHT_ONLY = Set.of(ReadyQuestion.NIGHT_SKY);

    /** Longest failure message kept in the job run's {@code notes} column (length 500). */
    private static final int MAX_NOTE_LENGTH = 400;

    private final AstroConditionsService astroConditionsService;
    private final AuroraForecastRunService nightSelector;
    private final JobRunService jobRunService;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final AskReadyPrecompute askReadyPrecompute;
    private final Executor backgroundExecutor;
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
     * @param askReadyPrecompute      Ask PhotoCast's Ready precompute, dispatched after a successful run
     * @param clock                   clock resolving "today" on the UK civil calendar
     */
    @Autowired
    public AstroConditionsRefreshJob(AstroConditionsService astroConditionsService,
            AuroraForecastRunService nightSelector, JobRunService jobRunService,
            DynamicSchedulerService dynamicSchedulerService, AskReadyPrecompute askReadyPrecompute,
            Clock clock) {
        this(astroConditionsService, nightSelector, jobRunService, dynamicSchedulerService,
                askReadyPrecompute, Executors.newVirtualThreadPerTaskExecutor(), clock);
    }

    /**
     * Creates the job with its own background executor, for tests.
     *
     * @param astroConditionsService  the template scorer and persister
     * @param nightSelector           the one owner of "which night is current"
     * @param jobRunService           job-run bookkeeping
     * @param dynamicSchedulerService the DB-backed scheduler this job registers with
     * @param askReadyPrecompute      Ask PhotoCast's Ready precompute
     * @param backgroundExecutor      where the precompute runs, off the job's thread
     * @param clock                   clock resolving "today" on the UK civil calendar
     */
    AstroConditionsRefreshJob(AstroConditionsService astroConditionsService,
            AuroraForecastRunService nightSelector, JobRunService jobRunService,
            DynamicSchedulerService dynamicSchedulerService, AskReadyPrecompute askReadyPrecompute,
            Executor backgroundExecutor, Clock clock) {
        this.astroConditionsService = astroConditionsService;
        this.nightSelector = nightSelector;
        this.jobRunService = jobRunService;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.askReadyPrecompute = askReadyPrecompute;
        this.backgroundExecutor = backgroundExecutor;
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

    /**
     * Hands Ask PhotoCast's Ready precompute to the background executor, after the job run is complete.
     * Must never throw: it runs inside {@link #doRun}'s try, whose catch would otherwise mark a
     * finished run failed. A failure to queue the work and an exception inside it are each logged and
     * stop there. The precompute returns a refusal rather than throwing (Ask off, a simulation, no
     * fresh briefing, another precompute running, the day's ceiling), which is logged at INFO.
     *
     * @param manual true for an administrator's run, which takes the route the admin endpoint takes
     *               and is not counted against the per-day ceiling; false for the schedule, which is
     */
    private void dispatchReady(boolean manual) {
        try {
            backgroundExecutor.execute(() -> {
                try {
                    AskReadyPrecompute.Result result = manual ? askReadyPrecompute.precomputeOnDemand(NIGHT_ONLY)
                            : askReadyPrecompute.precompute(null, NIGHT_ONLY);
                    LOG.info("Astro conditions refresh: Ask Ready precompute {}",
                            result.wasRefused() ? "refused: " + result.refusal()
                                    : "wrote " + result.written() + ", skipped " + result.skipped()
                                            + ", failed " + result.failed());
                } catch (RuntimeException e) {
                    LOG.warn("Astro conditions refresh: the Ask Ready precompute raised an exception —"
                            + " logged and ignored (the run is already complete): {}", e.toString());
                }
            });
        } catch (RuntimeException e) {
            LOG.warn("Astro conditions refresh: the Ask Ready precompute could not be dispatched —"
                    + " logged and ignored (the run is already complete): {}", e.toString());
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
            } else {
                dispatchReady(manual);
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
