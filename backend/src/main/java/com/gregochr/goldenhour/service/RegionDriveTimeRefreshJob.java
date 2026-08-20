package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.RegionEntity;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Nightly refresh of the shared region-base drive-time matrix.
 *
 * <p><strong>Why it exists.</strong> The same reason {@link DriveTimeRefreshJob} does: the matrix
 * is replaced wholesale per region, so a location added after the last sweep has no row from any
 * base, indefinitely — and on the Plan tab that is a spot rendering with no drive line and nothing
 * saying why. It runs twenty minutes after the per-user sweep rather than beside it: both hit the
 * same ORS account through the same two permits, and staggering keeps a slow user sweep from
 * queueing every region behind it.
 *
 * <p>Regions with no base town are skipped — they cannot be an origin, so they have no journeys to
 * measure, and their having no rows is correct rather than stale. Enabled and disabled regions are
 * treated alike: a disabled region is hidden from the location dropdowns, which says nothing about
 * whether a reader may plan from it, and re-enabling one should not leave it a day behind.
 */
@Service
public class RegionDriveTimeRefreshJob {

    private static final Logger LOG = LoggerFactory.getLogger(RegionDriveTimeRefreshJob.class);

    /** Scheduler key; matches the {@code scheduler_job_config} row seeded by V145. */
    static final String JOB_KEY = "region_drive_time_refresh";

    private final RegionService regionService;
    private final RegionDriveDurationService regionDriveDurationService;
    private final DynamicSchedulerService dynamicSchedulerService;

    /**
     * Creates the job.
     *
     * @param regionService              source of regions and their bases
     * @param regionDriveDurationService performs the per-region ORS refresh
     * @param dynamicSchedulerService    the DB-backed scheduler this job registers with
     */
    public RegionDriveTimeRefreshJob(RegionService regionService,
            RegionDriveDurationService regionDriveDurationService,
            DynamicSchedulerService dynamicSchedulerService) {
        this.regionService = regionService;
        this.regionDriveDurationService = regionDriveDurationService;
        this.dynamicSchedulerService = dynamicSchedulerService;
    }

    /** Registers the nightly refresh with the dynamic scheduler. */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget(JOB_KEY, this::runScheduled);
    }

    /**
     * Refreshes the matrix for every region carrying a base town.
     *
     * <p>Each region is refreshed independently and a failure is logged and stepped over rather
     * than abandoning the run: one region's ORS failure must not deny every other region its
     * refresh. A region that writes no rows is counted as a failure so the log distinguishes it
     * from success, and its previous rows survive — {@link RegionDriveTimeWriter#replaceForRegion}
     * is only reached on a response that produced some.
     */
    void runScheduled() {
        List<RegionEntity> based = regionService.findAll().stream()
                .filter(RegionDriveDurationService::hasBase)
                .toList();

        if (based.isEmpty()) {
            LOG.info("Region drive time refresh: no regions have a base town — nothing to do");
            return;
        }

        int refreshed = 0;
        int failed = 0;
        int locationsWritten = 0;

        for (RegionEntity region : based) {
            try {
                int updated = regionDriveDurationService.refreshForRegion(region);
                if (updated > 0) {
                    refreshed++;
                    locationsWritten += updated;
                } else {
                    failed++;
                    LOG.warn("Region drive time refresh wrote no rows for '{}' — leaving the "
                            + "previous matrix in place", region.getName());
                }
            } catch (Exception e) {
                failed++;
                LOG.warn("Region drive time refresh failed for '{}': {}",
                        region.getName(), e.getMessage());
            }
        }

        LOG.info("Region drive time refresh complete — {} region(s) refreshed ({} location rows), "
                + "{} failed, {} considered", refreshed, locationsWritten, failed, based.size());
    }
}
