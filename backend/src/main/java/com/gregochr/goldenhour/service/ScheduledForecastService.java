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

/**
 * Scheduled job targets for the weekly tide refresh and the daily briefing (registered
 * with the {@link DynamicSchedulerService}), plus the admin-triggered tide backfill.
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
     * Refreshes 14 days of tide extremes from WorldTides for all coastal locations.
     *
     * <p>Runs once a week (default: Monday at 02:00 UTC).
     */
    public void refreshTideExtremes() {
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
     * Refreshes the daily briefing at 04:00, 14:00 and 22:00 UTC — a pre-flight check
     * of weather and tide conditions across all enabled colour locations.
     */
    public void refreshDailyBriefing() {
        try {
            briefingService.refreshBriefing();
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
