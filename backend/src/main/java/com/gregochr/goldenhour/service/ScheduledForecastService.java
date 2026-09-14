package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RunType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled job targets for the weekly tide refresh and the daily briefing (registered
 * with the {@link DynamicSchedulerService}), plus the two admin-triggered tide runs: the
 * refresh ({@link #startTideRefresh}) and the backfill.
 *
 * <p>The synchronous forecast wrappers that used to live here (near-term, distant and
 * weather runs, plus the exchange-rate warm-up) were removed after the v2.12
 * consolidation onto the Anthropic Batch API pipeline
 * ({@link com.gregochr.goldenhour.service.batch.ScheduledBatchEvaluationService}) left
 * them dormant with no callers — their {@code @Scheduled} triggers had already been
 * commented out. Admin manual runs go through {@code ForecastController} →
 * {@link ForecastCommandExecutor} directly, and the {@code stability_snapshot} write
 * contract is owned by the shared
 * {@link com.gregochr.goldenhour.service.batch.GridCellStabilityService}.
 */
@Service
public class ScheduledForecastService {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledForecastService.class);

    private final TideService tideService;
    private final LocationService locationService;
    private final JobRunService jobRunService;
    private final BriefingService briefingService;
    private final DynamicSchedulerService dynamicSchedulerService;

    /**
     * True from the moment a tide refresh is accepted until it finishes, whichever route started
     * it: the {@code tide_refresh} schedule, the Scheduler screen's Run Now, or the admin
     * {@code POST /api/forecast/run/tide}.
     *
     * <p>Two refreshes that reach a location before either has committed it both find the
     * window uncovered, so both spend a WorldTides request on it, and each deletes and re-inserts
     * that window in its own transaction under {@code uq_tide_extreme (location_id, event_time)}.
     * The slower one's inserts can then collide with the rows the faster one wrote, and its run
     * logs a failure for a location that was in fact refreshed.
     *
     * <p>An {@link AtomicBoolean} rather than a lock because the admin route accepts the run on
     * the request thread and releases it on the executor's thread, and a lock is owned by the
     * thread that took it. It covers the roster-wide refresh only: the 12-month backfill, and the
     * single-location fetch {@code LocationService} makes when a coastal location is added or
     * edited, are outside it.
     */
    private final AtomicBoolean tideRefreshRunning = new AtomicBoolean(false);

    /**
     * Constructs a {@code ScheduledForecastService}.
     *
     * @param tideService              the service that fetches and stores tide extremes
     * @param locationService          the service providing persisted locations
     * @param jobRunService            the service for tracking job run metrics
     * @param briefingService          the service that generates daily briefings
     * @param dynamicSchedulerService  the dynamic scheduler for job registration
     */
    public ScheduledForecastService(TideService tideService, LocationService locationService,
            JobRunService jobRunService,
            BriefingService briefingService,
            DynamicSchedulerService dynamicSchedulerService) {
        this.tideService = tideService;
        this.locationService = locationService;
        this.jobRunService = jobRunService;
        this.briefingService = briefingService;
        this.dynamicSchedulerService = dynamicSchedulerService;
    }

    /**
     * Registers tide refresh and daily briefing jobs with the dynamic scheduler.
     */
    @PostConstruct
    void registerJobs() {
        dynamicSchedulerService.registerJobTarget("tide_refresh", this::refreshTideExtremes);
        dynamicSchedulerService.registerJobTarget("daily_briefing", this::refreshDailyBriefing);
    }

    /**
     * Refreshes the forward tide window of extremes from WorldTides for every enabled
     * SEASCAPE location that has a tide preference set.
     *
     * <p>Runs once a week (default: Monday at 02:00 UTC).
     *
     * <p>Not "all coastal locations", despite what this said for a long time and what the
     * {@code tide_refresh} scheduler row still said until V139: the filter below requires the
     * SEASCAPE tag as well as a non-empty tide type, so a coastal LANDSCAPE or WATERFALL
     * location is skipped.
     *
     * <p>Does nothing if a tide refresh is already running (see {@link #tideRefreshRunning}).
     * This is the scheduler's target, so the refusal can only be logged: Run Now has already
     * answered "triggered" by the time it runs.
     */
    public void refreshTideExtremes() {
        if (!tideRefreshRunning.compareAndSet(false, true)) {
            LOG.warn("Tide refresh already running — skipping concurrent trigger");
            return;
        }
        try {
            doRefreshTideExtremes();
        } finally {
            tideRefreshRunning.set(false);
        }
    }

    /**
     * Starts a tide refresh on {@code executor} unless one is already running — the admin
     * {@code POST /api/forecast/run/tide} route, which answers before the refresh finishes.
     *
     * <p>The guard is taken here, on the caller's thread, and released by the task when it ends,
     * so the answer this returns is the truth at the moment it is given: there is no window
     * between "accepted" and "started" in which a second trigger could also be accepted.
     *
     * @param executor the executor to run the refresh on
     * @return {@code true} if the refresh was accepted, {@code false} if one was already running
     */
    public boolean startTideRefresh(Executor executor) {
        if (!tideRefreshRunning.compareAndSet(false, true)) {
            LOG.warn("Tide refresh already running — refusing admin trigger");
            return false;
        }
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    doRefreshTideExtremes();
                } finally {
                    tideRefreshRunning.set(false);
                }
            }, executor);
        } catch (RuntimeException | Error e) {
            // Rejected before it ran (or the thread could not be made), so the task's own
            // finally never will.
            tideRefreshRunning.set(false);
            throw e;
        }
        return true;
    }

    private void doRefreshTideExtremes() {
        JobRunEntity jobRun = jobRunService.startRun(RunType.TIDE, false, null);
        List<LocationEntity> coastal = locationService.findAllEnabled().stream()
                .filter(loc -> loc.getLocationType().contains(LocationType.SEASCAPE))
                .filter(locationService::isCoastal)
                .toList();
        LOG.info("Weekly tide refresh started — {} SEASCAPE coastal location(s)", coastal.size());
        int succeeded = 0;
        int failed = 0;

        for (LocationEntity location : coastal) {
            try {
                tideService.fetchAndStoreTideExtremes(location, jobRun);
                succeeded++;
            } catch (Exception e) {
                LOG.error("Tide refresh failed for {}: {}", location.getName(), e.getMessage(), e);
                failed++;
            }
        }

        jobRunService.completeRun(jobRun, succeeded, failed);
        LOG.info("Weekly tide refresh complete — {} succeeded, {} failed",
                succeeded, failed);
    }

    /**
     * The {@code daily_briefing} scheduler target — a pre-flight check of weather and tide
     * conditions across all enabled colour locations.
     *
     * <p>Dormant: V103 deleted the job's row, so nothing schedules it and the Scheduler screen
     * has no Run Now for it (the briefing is built at the tail of each pipeline cycle). It stays
     * registered as the one-line revert path V103 describes. If that revert is ever taken, a fire
     * that meets a build already in progress skips it rather than queuing a second one behind it.
     */
    public void refreshDailyBriefing() {
        try {
            briefingService.refreshBriefingIfIdle();
        } catch (Exception e) {
            LOG.error("Daily briefing refresh failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Backfills 12 months of historical tide data for all enabled SEASCAPE locations.
     *
     * <p>Fetches in 7-day chunks, skipping any chunk where data already exists to avoid
     * duplicate WorldTides API charges. Runs asynchronously from the admin UI.
     */
    public void backfillTideExtremes() {
        JobRunEntity jobRun = jobRunService.startRun(RunType.TIDE, true, null);
        List<LocationEntity> seascapeCoastal = locationService.findAllEnabled().stream()
                .filter(loc -> loc.getLocationType().contains(LocationType.SEASCAPE))
                .filter(locationService::isCoastal)
                .toList();
        LOG.info("Tide backfill started — {} SEASCAPE location(s)", seascapeCoastal.size());
        int succeeded = 0;
        int failed = 0;

        for (LocationEntity location : seascapeCoastal) {
            try {
                int chunks = tideService.backfillTideExtremes(location, jobRun);
                LOG.info("Backfilled {} chunks for {}", chunks, location.getName());
                succeeded++;
            } catch (Exception e) {
                LOG.error("Tide backfill failed for {}: {}",
                        location.getName(), e.getMessage(), e);
                failed++;
            }
        }

        jobRunService.completeRun(jobRun, succeeded, failed);
        LOG.info("Tide backfill complete — {} succeeded, {} failed", succeeded, failed);
    }
}
