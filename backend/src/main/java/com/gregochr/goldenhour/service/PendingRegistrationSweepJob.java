package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.RegistrationProperties;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserRepository.PendingStage;
import com.gregochr.goldenhour.service.PendingRegistrationWriter.Claim;
import com.gregochr.goldenhour.service.PendingRegistrationWriter.Outcome;
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
 * Hourly sweep of never-verified sign-ups — the {@code pending_registration_sweep} job
 * (docs/engineering/pending-registration-lifecycle-plan.md).
 *
 * <p>A pending sign-up (disabled, empty password) holds one of the early-access places. It is reminded with a
 * FRESH link when {@code first-reminder-hours-left} and {@code final-reminder-hours-left} of the hold remain,
 * and deleted once the final reminder was sent at least {@code final-reminder-hours-left} ago. A purge always
 * follows a final reminder that was actually sent; the one exception is a sender that is not configured at all
 * (local/dev), where the sweep purges at {@code age >= hold} and sends nothing.
 *
 * <p>Not transactional itself: every write goes through {@link PendingRegistrationWriter} or
 * {@link UserService#purgeAbandonedPending}, both reached through their Spring proxies, and no row lock is
 * ever held across the SMTP send. Each account is handled in isolation: a throw is logged at WARN and the
 * loop carries on. All times are read from a system-default-zone clock — the frame {@code created_at} and
 * the token {@code expires_at} are written in — never the app's UTC {@code Clock} bean.
 */
@Service
public class PendingRegistrationSweepJob {

    private static final Logger LOG = LoggerFactory.getLogger(PendingRegistrationSweepJob.class);

    /** Scheduler job key, matching the V190-seeded {@code scheduler_job_config} row. */
    public static final String JOB_KEY = "pending_registration_sweep";

    private static final int FIRST_REMINDER = 1;
    private static final int FINAL_REMINDER = 2;

    private final AppUserRepository userRepository;
    private final PendingRegistrationWriter writer;
    private final UserService userService;
    private final UserEmailService userEmailService;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final RegistrationProperties properties;
    private final Clock clock;

    /**
     * Constructs the job on the system default zone.
     *
     * @param userRepository          accounts
     * @param writer                  the claim and revert transactions
     * @param userService             the purge
     * @param userEmailService        the reminder mail
     * @param dynamicSchedulerService scheduler to register the job target against
     * @param properties              the hold window
     */
    @Autowired
    public PendingRegistrationSweepJob(AppUserRepository userRepository, PendingRegistrationWriter writer,
            UserService userService, UserEmailService userEmailService,
            DynamicSchedulerService dynamicSchedulerService, RegistrationProperties properties) {
        this(userRepository, writer, userService, userEmailService, dynamicSchedulerService, properties,
                Clock.systemDefaultZone());
    }

    /**
     * Constructs the job on a given clock (tests pass a fixed one).
     *
     * @param userRepository          accounts
     * @param writer                  the claim and revert transactions
     * @param userService             the purge
     * @param userEmailService        the reminder mail
     * @param dynamicSchedulerService scheduler to register the job target against
     * @param properties              the hold window
     * @param clock                   the clock "now" is read from
     */
    PendingRegistrationSweepJob(AppUserRepository userRepository, PendingRegistrationWriter writer,
            UserService userService, UserEmailService userEmailService,
            DynamicSchedulerService dynamicSchedulerService, RegistrationProperties properties, Clock clock) {
        this.userRepository = userRepository;
        this.writer = writer;
        this.userService = userService;
        this.userEmailService = userEmailService;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.properties = properties;
        this.clock = clock;
    }

    /** Registers the sweep with the dynamic scheduler, resolving the V190-seeded cron row. */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget(JOB_KEY, this::sweep);
    }

    /** What one run did, for its single INFO line. */
    private static final class Tally {
        private int candidates;
        private int firstSent;
        private int finalSent;
        private int deferred;
        private int failedSends;
        private int purged;
        private int errors;
    }

    /** Runs one sweep and logs one INFO line with the counts. */
    void sweep() {
        LocalDateTime now = LocalDateTime.now(clock);
        int hold = properties.getPendingHoldHours();
        LocalDateTime cutoff = now.minusHours(hold - properties.getFirstReminderHoursLeft());
        boolean configured = userEmailService.isConfigured();
        List<PendingStage> candidates = userRepository.findPendingSweepCandidates(cutoff, UserRole.ADMIN);
        Tally tally = new Tally();
        tally.candidates = candidates.size();
        for (PendingStage candidate : candidates) {
            try {
                handle(candidate, now, configured, tally);
            } catch (RuntimeException ex) {
                tally.errors++;
                LOG.warn("Pending registration sweep failed for user id={}: {}", candidate.getId(),
                        LogSanitizer.sanitize(ex.getMessage()));
            }
        }
        LOG.info("[PENDING SWEEP] candidates={}, firstReminders={}, finalReminders={}, deferred={}, "
                        + "failedSends={}, purged={}, errors={}", tally.candidates, tally.firstSent,
                tally.finalSent, tally.deferred, tally.failedSends, tally.purged, tally.errors);
    }

    private void handle(PendingStage c, LocalDateTime now, boolean configured, Tally tally) {
        int hold = properties.getPendingHoldHours();
        int finalLeft = properties.getFinalReminderHoursLeft();
        int firstLeft = properties.getFirstReminderHoursLeft();
        boolean purgeAfterFinal = c.getRemindersSent() >= FINAL_REMINDER && c.getFinalReminderSentAt() != null
                && !c.getFinalReminderSentAt().isAfter(now.minusHours(finalLeft));
        boolean purgeNoSender = !configured && !c.getCreatedAt().isAfter(now.minusHours(hold));
        if (purgeAfterFinal || purgeNoSender) {
            if (userService.purgeAbandonedPending(c.getId(), now)) {
                tally.purged++;
            }
            return;
        }
        if (!configured) {
            return;
        }
        if (c.getRemindersSent() < FINAL_REMINDER && !c.getCreatedAt().isAfter(now.minusHours(hold - finalLeft))) {
            remind(c, FINAL_REMINDER, now.plusHours(finalLeft), now, tally);
        } else if (c.getRemindersSent() < FIRST_REMINDER
                && !c.getCreatedAt().isAfter(now.minusHours(hold - firstLeft))) {
            remind(c, FIRST_REMINDER, c.getCreatedAt().plusHours(hold), now, tally);
        }
    }

    private void remind(PendingStage c, int next, LocalDateTime placeLapsesAt, LocalDateTime now, Tally tally) {
        int expected = c.getRemindersSent();
        Claim claim = writer.claim(c.getId(), expected, next, now);
        if (claim.outcome() == Outcome.DEFERRED) {
            tally.deferred++;
            return;
        }
        if (claim.outcome() != Outcome.CLAIMED) {
            return;
        }
        boolean finalReminder = next == FINAL_REMINDER;
        boolean sent = userEmailService.sendVerificationReminderEmail(claim.issued().email(),
                claim.issued().username(), claim.issued().rawToken(), finalReminder, placeLapsesAt);
        if (!sent) {
            writer.revert(c.getId(), expected, next, claim.previousFinalAt(), claim.tokenHash());
            tally.failedSends++;
        } else if (finalReminder) {
            tally.finalSent++;
        } else {
            tally.firstSent++;
        }
    }
}
