package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.repository.AppUserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Nightly refresh of every user's per-location drive times.
 *
 * <p><strong>Why this exists.</strong> Drive times were only ever recalculated when a user pressed
 * the button in Settings, and {@code DriveDurationService} replaces a user's whole set on each run.
 * So a location added after someone's last refresh had no row for them, indefinitely — it simply
 * rendered without a {@code 🚗 NN min} chip and nothing indicated why. Observed in production:
 * Hardwick Hall Country Park and Houghall Woods had zero {@code user_drive_time} rows while their
 * neighbours had one each, because they were added after the last manual refresh.
 *
 * <p><strong>The manual button stays.</strong> This job is the routine path that stops the data
 * going stale on its own; the Settings button remains as the immediate, user-triggered override for
 * someone who has just moved house and does not want to wait for tonight. The two differ
 * deliberately in one respect: the manual path enforces a per-user cooldown to stop the button
 * being hammered, and this one does not — a nightly tick is not abuse, and applying the cooldown
 * here would make the job silently skip any user who happened to press the button that evening,
 * which is precisely the user whose data is most likely to matter.
 *
 * <p>Users with no saved home location are skipped: there is no origin to route from, and their
 * having no drive times is correct rather than stale.
 */
@Service
public class DriveTimeRefreshJob {

    private static final Logger LOG = LoggerFactory.getLogger(DriveTimeRefreshJob.class);

    /** Scheduler key; matches the {@code scheduler_job_config} row seeded by V133. */
    static final String JOB_KEY = "drive_time_refresh";

    private final AppUserRepository userRepository;
    private final DriveDurationService driveDurationService;
    private final DynamicSchedulerService dynamicSchedulerService;

    /**
     * Creates the job.
     *
     * @param userRepository          source of users with a saved home location
     * @param driveDurationService    performs the per-user ORS refresh
     * @param dynamicSchedulerService the DB-backed scheduler this job registers with
     */
    public DriveTimeRefreshJob(AppUserRepository userRepository,
            DriveDurationService driveDurationService,
            DynamicSchedulerService dynamicSchedulerService) {
        this.userRepository = userRepository;
        this.driveDurationService = driveDurationService;
        this.dynamicSchedulerService = dynamicSchedulerService;
    }

    /** Registers the nightly refresh with the dynamic scheduler. */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget(JOB_KEY, this::runScheduled);
    }

    /**
     * Refreshes drive times for every enabled user with a saved home location.
     *
     * <p>Each user is refreshed independently and a failure is logged and stepped over rather than
     * abandoning the run: one user's ORS failure must not deny everyone else their refresh. The
     * per-user {@code driveTimesCalculatedAt} stamp is only advanced on a run that actually wrote
     * rows, so a silently-empty ORS response leaves the timestamp showing the last real refresh
     * rather than claiming a fresh one — the same distinction the cloud-verification backfill
     * draws between "ran" and "achieved something".
     */
    void runScheduled() {
        List<AppUserEntity> users = userRepository.findAll().stream()
                .filter(AppUserEntity::isEnabled)
                .filter(u -> u.getHomeLatitude() != null && u.getHomeLongitude() != null)
                .toList();

        if (users.isEmpty()) {
            LOG.info("Drive time refresh: no enabled users with a home location — nothing to do");
            return;
        }

        int refreshed = 0;
        int failed = 0;
        int locationsWritten = 0;

        for (AppUserEntity user : users) {
            try {
                int updated = driveDurationService.refreshForUser(
                        user.getId(), user.getHomeLatitude(), user.getHomeLongitude());
                if (updated > 0) {
                    user.setDriveTimesCalculatedAt(Instant.now());
                    userRepository.save(user);
                    refreshed++;
                    locationsWritten += updated;
                } else {
                    // Zero is not an exception — ORS unconfigured, rate-limited, or an empty
                    // response. Count it as a failure so the log distinguishes it from success,
                    // and leave the timestamp alone so the UI keeps showing the last real refresh.
                    failed++;
                    LOG.warn("Drive time refresh wrote no rows for user {} — leaving the previous "
                            + "calculated-at stamp in place", user.getId());
                }
            } catch (Exception e) {
                failed++;
                LOG.warn("Drive time refresh failed for user {}: {}", user.getId(), e.getMessage());
            }
        }

        LOG.info("Drive time refresh complete — {} user(s) refreshed ({} location rows), "
                + "{} failed, {} considered", refreshed, locationsWritten, failed, users.size());
    }
}
