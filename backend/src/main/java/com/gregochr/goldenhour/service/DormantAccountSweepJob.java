package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.DormancyProperties;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserRepository.DormancyCandidate;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import com.gregochr.goldenhour.util.LogSanitizer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Daily sweep of dormant LITE_USER accounts — the {@code dormant_account_sweep} job
 * (docs/engineering/audit-followups-plan.md, D).
 *
 * <p>An enabled, addressable LITE_USER whose last activity ({@code COALESCE(last_active_at, created_at)}) is at
 * least {@code inactive-days} old is WARNED; if the warning (stamped after that last activity) is at least
 * {@code grace-days} old and nothing has happened since, the account is DELETED. Pro, Admin, disabled, pending
 * and address-less accounts are never candidates.
 *
 * <p>Safety, in order of importance:
 * <ul>
 *   <li>The seeded job row is PAUSED, and {@code dry-run} defaults to {@code true}: a dry run sends nothing,
 *       stamps nothing, deletes nothing, and only logs would-warn, would-delete and "deletable from"
 *       projections. "Run now" runs the job even while PAUSED, so the dry-run default is the real lock.</li>
 *   <li>Warn: send FIRST, stamp AFTER, and only if the sender accepted the mail. A crash between the two
 *       leaves the account unwarned (retried next run), never deleted unwarned.</li>
 *   <li>Delete: {@link UserService#purgeDormant} re-checks every "who" condition under the row lock and in the
 *       conditional delete, so a sign-in, promotion or disable after the warning saves the account.</li>
 *   <li>Without a configured mail sender nothing is warned or deleted.</li>
 * </ul>
 *
 * <p>Not transactional itself: every write goes through the repository's scoped updates or
 * {@link UserService#purgeDormant}, and no row lock is held across the SMTP send. Each account is handled in
 * isolation: a throw is logged at WARN (id only) and the loop carries on. Logs carry account ids only, never
 * an address or a username. Times are read from a system-default-zone clock, the frame {@code created_at} and
 * {@code last_active_at} are written in.
 */
@Service
public class DormantAccountSweepJob {

    private static final Logger LOG = LoggerFactory.getLogger(DormantAccountSweepJob.class);

    /** Scheduler job key, matching the seeded {@code scheduler_job_config} row. */
    public static final String JOB_KEY = "dormant_account_sweep";

    private final AppUserRepository userRepository;
    private final UserService userService;
    private final UserEmailService userEmailService;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final DormancyProperties properties;
    private final Clock clock;

    /**
     * Constructs the job on the system default zone.
     *
     * @param userRepository          accounts
     * @param userService             the purge
     * @param userEmailService        the warning and deleted mail
     * @param dynamicSchedulerService scheduler to register the job target against
     * @param properties              the dormancy windows and the dry-run flag
     */
    @Autowired
    public DormantAccountSweepJob(AppUserRepository userRepository, UserService userService,
            UserEmailService userEmailService, DynamicSchedulerService dynamicSchedulerService,
            DormancyProperties properties) {
        this(userRepository, userService, userEmailService, dynamicSchedulerService, properties,
                Clock.systemDefaultZone());
    }

    /**
     * Constructs the job on a given clock (tests pass a fixed one).
     *
     * @param userRepository          accounts
     * @param userService             the purge
     * @param userEmailService        the warning and deleted mail
     * @param dynamicSchedulerService scheduler to register the job target against
     * @param properties              the dormancy windows and the dry-run flag
     * @param clock                   the clock "now" is read from
     */
    DormantAccountSweepJob(AppUserRepository userRepository, UserService userService,
            UserEmailService userEmailService, DynamicSchedulerService dynamicSchedulerService,
            DormancyProperties properties, Clock clock) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.userEmailService = userEmailService;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.properties = properties;
        this.clock = clock;
    }

    /** Registers the sweep with the dynamic scheduler, resolving the seeded cron row. */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget(JOB_KEY, this::sweep);
    }

    /** What one run did, for its single INFO line. */
    private static final class Tally {
        private int candidates;
        private int warned;
        private int failedSends;
        private int unstamped;
        private int waiting;
        private int purged;
        private int skipped;
        private int errors;
        private int wouldWarn;
        private int wouldDelete;
    }

    /** Runs one sweep and logs one INFO line with the counts. */
    void sweep() {
        boolean dryRun = properties.isDryRun();
        if (!dryRun && !userEmailService.isConfigured()) {
            LOG.info("[DORMANT SWEEP] no mail sender is configured: nothing warned, nothing deleted");
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime inactiveCutoff = now.minusDays(properties.getInactiveDays());
        LocalDateTime warnCutoff = now.minusDays(properties.getGraceDays());
        List<DormancyCandidate> candidates = userRepository.findDormancyCandidates(UserRole.LITE_USER,
                inactiveCutoff);
        Tally tally = new Tally();
        tally.candidates = candidates.size();
        for (DormancyCandidate candidate : candidates) {
            try {
                handle(candidate, now, inactiveCutoff, warnCutoff, dryRun, tally);
            } catch (RuntimeException ex) {
                tally.errors++;
                LOG.warn("Dormant account sweep failed for user id={}: {}", candidate.getId(),
                        LogSanitizer.sanitize(ex.getClass().getSimpleName()));
            }
        }
        if (dryRun) {
            LOG.info("[DORMANT SWEEP dry-run] candidates={}, wouldWarn={}, wouldDelete={}, waiting={}, errors={}"
                            + " (nothing sent, stamped or deleted)", tally.candidates, tally.wouldWarn,
                    tally.wouldDelete, tally.waiting, tally.errors);
        } else {
            LOG.info("[DORMANT SWEEP] candidates={}, warned={}, failedSends={}, unstamped={}, waiting={}, "
                            + "purged={}, skipped={}, errors={}", tally.candidates, tally.warned,
                    tally.failedSends, tally.unstamped, tally.waiting, tally.purged, tally.skipped,
                    tally.errors);
        }
    }

    private void handle(DormancyCandidate c, LocalDateTime now, LocalDateTime inactiveCutoff,
            LocalDateTime warnCutoff, boolean dryRun, Tally tally) {
        LocalDateTime lastActivity = c.getLastActiveAt() != null ? c.getLastActiveAt() : c.getCreatedAt();
        LocalDateTime warnedAt = c.getDormancyWarningSentAt();
        boolean warned = warnedAt != null && warnedAt.isAfter(lastActivity);
        if (!warned) {
            warn(c, now, inactiveCutoff, dryRun, tally);
        } else if (!warnedAt.isAfter(warnCutoff)) {
            delete(c, inactiveCutoff, warnCutoff, dryRun, tally);
        } else {
            tally.waiting++;
            if (dryRun) {
                LOG.info("[DORMANT SWEEP dry-run] user id={} is warned; deletable from {}", c.getId(),
                        warnedAt.plusDays(properties.getGraceDays()).toLocalDate());
            }
        }
    }

    private void warn(DormancyCandidate c, LocalDateTime now, LocalDateTime inactiveCutoff, boolean dryRun,
            Tally tally) {
        LocalDateTime deletableFrom = now.plusDays(properties.getGraceDays());
        if (dryRun) {
            tally.wouldWarn++;
            LOG.info("[DORMANT SWEEP dry-run] would warn user id={}; deletable from {}", c.getId(),
                    deletableFrom.toLocalDate());
            return;
        }
        boolean sent = userEmailService.sendDormancyWarningEmail(c.getId(), c.getEmail(), c.getUsername(),
                properties.getInactiveDays(), deletableFrom);
        if (!sent) {
            tally.failedSends++;
            return;
        }
        if (userRepository.stampDormancyWarning(c.getId(), now, UserRole.LITE_USER, inactiveCutoff) == 1) {
            tally.warned++;
        } else {
            tally.unstamped++;
            LOG.info("Dormancy warning sent but not stamped, the account no longer qualifies: user id={}",
                    c.getId());
        }
    }

    private void delete(DormancyCandidate c, LocalDateTime inactiveCutoff, LocalDateTime warnCutoff,
            boolean dryRun, Tally tally) {
        if (dryRun) {
            tally.wouldDelete++;
            LOG.info("[DORMANT SWEEP dry-run] would delete user id={}", c.getId());
            return;
        }
        userService.purgeDormant(c.getId(), inactiveCutoff, warnCutoff).ifPresentOrElse(deleted -> {
            tally.purged++;
            userEmailService.sendAccountDeletedEmail(c.getId(), deleted.email(), deleted.username());
        }, () -> tally.skipped++);
    }
}
