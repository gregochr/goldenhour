package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.repository.AppUserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * Nightly refresh of every user's per-location drive times.
 *
 * <p><strong>Why this exists.</strong> Drive times were only ever recalculated when a user pressed
 * the button in Settings, and a refresh replaces a user's whole set each time. So a location added
 * after someone's last refresh had no row for them, indefinitely — it simply rendered without a
 * {@code 🚗 NN min} chip and nothing indicated why. Observed in production: Hardwick Hall Country
 * Park and Houghall Woods had zero {@code user_drive_time} rows while their neighbours had one
 * each, because they were added after the last manual refresh.
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
 *
 * <p><strong>A home can move under the run.</strong> The roster is read once, and each user is
 * then routed for seconds from the home as it was read, so a user who saves a new postcode tonight
 * can be measured from the old one. What was measured is stored through
 * {@link UserDriveTimeWriter#storeIfHomeUnchanged}, which writes nothing when the home has moved:
 * the save that moved it already discarded the old drive times and released the manual cooldown,
 * so that user is counted as superseded, not failed, and is measured afresh by the next run or by
 * their own press of the button. Until this was guarded, the job stored the old home's drive times
 * over that discard and then saved the whole user row it had read — putting the old home back.
 */
@Service
public class DriveTimeRefreshJob {

    private static final Logger LOG = LoggerFactory.getLogger(DriveTimeRefreshJob.class);

    /** Scheduler key; matches the {@code scheduler_job_config} row seeded by V133. */
    static final String JOB_KEY = "drive_time_refresh";

    private final AppUserRepository userRepository;
    private final DriveDurationService driveDurationService;
    private final UserDriveTimeWriter driveTimeWriter;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final Clock clock;

    /**
     * Creates the job.
     *
     * @param userRepository          source of users with a saved home location
     * @param driveDurationService    measures a user's drive times via ORS
     * @param driveTimeWriter         stores them, only while the home is still the one measured from
     * @param dynamicSchedulerService the DB-backed scheduler this job registers with
     * @param clock                   supplies the calculated-at stamp
     */
    public DriveTimeRefreshJob(AppUserRepository userRepository,
            DriveDurationService driveDurationService,
            UserDriveTimeWriter driveTimeWriter,
            DynamicSchedulerService dynamicSchedulerService,
            Clock clock) {
        this.userRepository = userRepository;
        this.driveDurationService = driveDurationService;
        this.driveTimeWriter = driveTimeWriter;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.clock = clock;
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
     * abandoning the run: one user's ORS failure must not deny everyone else their refresh. Only a
     * measurement with at least one drive time is stored, and the per-user
     * {@code driveTimesCalculatedAt} stamp moves only with it — so a silently-empty ORS answer, or
     * one with no valid duration, leaves the stored drive times and the stamp showing the last real
     * refresh rather than claiming a fresh one. The same distinction the cloud-verification backfill
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
        int superseded = 0;
        int failed = 0;
        int locationsWritten = 0;

        for (AppUserEntity user : users) {
            try {
                double originLat = user.getHomeLatitude();
                double originLon = user.getHomeLongitude();
                List<UserDriveTimeEntity> driveTimes = driveDurationService
                        .measureForUser(user.getId(), originLat, originLon)
                        .orElse(List.of());
                if (driveTimes.isEmpty()) {
                    // Zero is not an exception — ORS unconfigured, rate-limited, an empty response,
                    // or no valid duration. Count it as a failure so the log distinguishes it from
                    // success, and store nothing, so the UI keeps showing the last real refresh.
                    failed++;
                    LOG.warn("Drive time refresh measured no drive times for user {} — leaving the "
                            + "stored ones and their calculated-at stamp in place", user.getId());
                } else if (driveTimeWriter.storeIfHomeUnchanged(
                        user.getId(), originLat, originLon, driveTimes, clock.instant())) {
                    refreshed++;
                    locationsWritten += driveTimes.size();
                } else {
                    superseded++;
                    LOG.info("Drive time refresh for user {} discarded — the home moved while it "
                            + "was measured, and the save that moved it discarded the old drive "
                            + "times", user.getId());
                }
            } catch (Exception e) {
                failed++;
                LOG.warn("Drive time refresh failed for user {}: {}", user.getId(), e.getMessage());
            }
        }

        LOG.info("Drive time refresh complete — {} user(s) refreshed ({} location rows), "
                + "{} superseded by a home move, {} failed, {} considered",
                refreshed, locationsWritten, superseded, failed, users.size());
    }
}
