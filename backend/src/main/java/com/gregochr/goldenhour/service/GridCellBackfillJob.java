package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fills in the Open-Meteo grid cell for any location that has none.
 *
 * <p><strong>Why this exists.</strong> {@code grid_lat}/{@code grid_lng} were written in exactly
 * one place — {@code LocationService.addLocation}, from the enrichment the admin Add-location form
 * runs client-side. Every location inserted by raw SQL therefore arrived without one: V84's sixteen
 * bluebell sites, V138's ten Heritage Coast entries, V143's Penshaw Monument. Such a location is
 * skipped by {@code GridCellStabilityService} and falls back to its own key in
 * {@code BriefingService} rather than being deduplicated against neighbours in the same ~2 km cell.
 *
 * <p>{@code LocationService.update} now fills the cell whenever a location is saved or moved, which
 * fixes the problem going forward but only for rows somebody edits. This job closes the existing
 * backlog without anyone clicking through twenty-seven admin rows, and keeps it closed: the next
 * migration that inserts locations is repaired on the following night rather than needing a
 * matching manual pass.
 *
 * <p><strong>It is also the other half of the update path's clearing rule.</strong> When a
 * location's coordinates change and the refetch fails, {@code update} deliberately clears the now
 * stale cell rather than leaving it pointing at the old position. That leaves a null — and this is
 * what heals it.
 *
 * <p><strong>Scope is every location, enabled or not.</strong> A disabled location is not being
 * forecast, so enriching it buys nothing today; but the work is one cheap call and happens exactly
 * once per location ever, where filtering on {@code enabled} would leave a re-enabled location
 * silently cell-less until the next nightly tick. "Any location without a grid cell" is also the
 * rule that needs no exception when someone asks why one row is still empty.
 *
 * <p>No manual endpoint ships with this. The dynamic scheduler already exposes
 * {@code POST /api/admin/scheduler/jobs/grid_cell_backfill/trigger}, so an immediate run after a
 * deploy is available without a second code path that could drift from this one.
 */
@Service
public class GridCellBackfillJob {

    private static final Logger LOG = LoggerFactory.getLogger(GridCellBackfillJob.class);

    /** Scheduler key; matches the {@code scheduler_job_config} row seeded by V146. */
    static final String JOB_KEY = "grid_cell_backfill";

    /**
     * Most locations to enrich in a single run.
     *
     * <p>The real backlog is around twenty-seven, so this never binds today. It exists so that a
     * migration inserting thousands of rows cannot turn one nightly tick into thousands of
     * sequential Open-Meteo calls; the remainder is picked up on subsequent nights. A run that hits
     * the cap says so in the log rather than reporting a tidy total for a job it did not finish.
     */
    static final int MAX_PER_RUN = 200;

    /**
     * Consecutive failures after which the run gives up.
     *
     * <p>Distinguishes "this one location has coordinates Open-Meteo dislikes" from "Open-Meteo is
     * unreachable". Without it, an outage means one failed call per location in the backlog, every
     * night, achieving nothing. Five in a row is not a coordinate problem.
     */
    static final int CONSECUTIVE_FAILURE_LIMIT = 5;

    private final LocationRepository locationRepository;
    private final LocationEnrichmentService locationEnrichmentService;
    private final DynamicSchedulerService dynamicSchedulerService;

    /**
     * Creates the job.
     *
     * @param locationRepository        source of locations missing a grid cell, and the sink
     * @param locationEnrichmentService performs the Open-Meteo grid cell lookup
     * @param dynamicSchedulerService   the DB-backed scheduler this job registers with
     */
    public GridCellBackfillJob(LocationRepository locationRepository,
            LocationEnrichmentService locationEnrichmentService,
            DynamicSchedulerService dynamicSchedulerService) {
        this.locationRepository = locationRepository;
        this.locationEnrichmentService = locationEnrichmentService;
        this.dynamicSchedulerService = dynamicSchedulerService;
    }

    /** Registers the nightly backfill with the dynamic scheduler. */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget(JOB_KEY, this::runScheduled);
    }

    /**
     * Enriches every location currently missing a grid cell.
     *
     * <p>Each location is saved as it succeeds rather than at the end, so a run cut short by the
     * consecutive-failure guard keeps the work it already did. A failure on one location is stepped
     * over: one bad coordinate must not deny the rest of the backlog its repair.
     */
    void runScheduled() {
        List<LocationEntity> pending = locationRepository.findByGridLatIsNullOrGridLngIsNullOrderByNameAsc();

        if (pending.isEmpty()) {
            LOG.info("Grid cell backfill: every location already has a cell — nothing to do");
            return;
        }

        List<LocationEntity> batch = pending.size() > MAX_PER_RUN
                ? pending.subList(0, MAX_PER_RUN)
                : pending;
        if (batch.size() < pending.size()) {
            LOG.warn("Grid cell backfill: {} locations lack a cell, capped at {} this run — the "
                    + "remaining {} are left for the next tick",
                    pending.size(), MAX_PER_RUN, pending.size() - batch.size());
        }

        int filled = 0;
        int failed = 0;
        int consecutiveFailures = 0;

        for (LocationEntity location : batch) {
            double[] cell;
            try {
                cell = locationEnrichmentService.fetchGridCell(
                        location.getLat(), location.getLon());
            } catch (Exception e) {
                cell = null;
                LOG.warn("Grid cell backfill threw for '{}': {}",
                        location.getName(), e.getMessage());
            }

            if (cell == null) {
                failed++;
                consecutiveFailures++;
                if (consecutiveFailures >= CONSECUTIVE_FAILURE_LIMIT) {
                    LOG.warn("Grid cell backfill: {} consecutive lookup failures — abandoning the "
                            + "run after filling {}. This reads as an Open-Meteo outage rather "
                            + "than bad coordinates; the backlog is retried on the next tick.",
                            consecutiveFailures, filled);
                    return;
                }
                continue;
            }

            consecutiveFailures = 0;
            location.setGridLat(cell[0]);
            location.setGridLng(cell[1]);
            locationRepository.save(location);
            filled++;
            LOG.info("Grid cell backfill: '{}' → {},{}", location.getName(), cell[0], cell[1]);
        }

        LOG.info("Grid cell backfill complete — {} filled, {} failed, {} considered",
                filled, failed, batch.size());
    }
}
