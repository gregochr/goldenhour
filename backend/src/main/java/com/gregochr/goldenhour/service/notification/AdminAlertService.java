package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.util.LogSanitizer;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Emails every enabled ADMIN account when the pipeline orchestrator marks a cycle
 * {@code DEGRADED} — the one admin-alert channel this app has, born from the
 * 2026-09-29 incident where a fully-failed forecast batch submission left the
 * cycle {@code COMPLETED}, silently serving ratings a day stale, with nobody told.
 *
 * <p><b>Why a new, dedicated service rather than the existing notification
 * machinery.</b> {@code NotificationChannel}/{@code NotificationDispatcher} carry
 * forecast RESULTS to end users (email/Pushover/macOS toast) and are all disabled
 * in production — reusing or enabling them for an operational alert would be
 * repurposing a user-facing feature flag for an ops concern, and would spend real
 * notification budget (Pushover) on something that was never a forecast result.
 * {@link UserEmailService} is the right sibling to imitate instead: it already
 * sends transactional mail successfully in production (registration, password
 * reset) via the same {@link JavaMailSender} bean and the same {@code
 * noreply@photocast.online} from-address — this class reuses exactly that
 * infrastructure, for a different audience and a different trigger.
 *
 * <p>Recipients are every {@link AppUserEntity} with {@link UserRole#ADMIN} whose
 * {@code enabled} flag is true AND whose {@code email} is non-blank — an admin
 * account created without an email (the seeded default admin, for instance) is
 * silently skipped rather than causing a send failure, since it was never
 * reachable by mail in the first place.
 *
 * <p><b>Best-effort by design.</b> {@link #sendPipelineDegradedAlert} is
 * {@link Async} and every failure inside it — no mail sender configured, no
 * enabled admin with an email, or the send itself throwing — is caught and logged
 * at WARN. It must never throw into the orchestrator's thread and must never
 * change a pipeline run's status; the run is already DEGRADED by the time this is
 * called, and a failed alert does not make it any more or less degraded.
 */
@Service
public class AdminAlertService {

    private static final Logger LOG = LoggerFactory.getLogger(AdminAlertService.class);

    /** Matches {@link UserEmailService}'s own from-address exactly — one sender identity. */
    private static final String FROM_ADDRESS = "noreply@photocast.online";

    private static final DateTimeFormatter TRIGGER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final JavaMailSender mailSender;
    private final AppUserRepository appUserRepository;

    /**
     * Constructs the service.
     *
     * @param mailSender         Spring mail sender (optional — null when SMTP is not configured,
     *                           matching {@link UserEmailService}'s own constructor)
     * @param appUserRepository  resolves the enabled-ADMIN recipient list
     */
    public AdminAlertService(
            @Autowired(required = false) JavaMailSender mailSender,
            AppUserRepository appUserRepository) {
        this.mailSender = mailSender;
        this.appUserRepository = appUserRepository;
    }

    /**
     * Sends the "pipeline run degraded" alert to every enabled ADMIN with an email address.
     *
     * <p>Called exactly once by {@code PipelineOrchestrator}, immediately after a cycle is marked
     * {@link com.gregochr.goldenhour.entity.PipelineRunStatus#DEGRADED} — never for a run that
     * completes cleanly or that fails outright (a FAILED run has its own, separate visibility via
     * the Pipeline Runs admin UI; this channel exists for DEGRADED specifically because that status
     * is the one case where the app keeps serving traffic while quietly stale).
     *
     * @param runId          the degraded pipeline run's id
     * @param cycleType      NIGHTLY or INTRADAY
     * @param triggerTime    when the cycle started (UTC)
     * @param failureSummary the same detail string recorded on the run's {@code failureReason} —
     *                       e.g. {@code "3 of 3 forecast batch submissions failed (510 requests
     *                       not submitted — near-term inland, near-term coastal, far-term
     *                       inland)"}
     */
    @Async
    public void sendPipelineDegradedAlert(Long runId, CycleType cycleType, Instant triggerTime,
            String failureSummary) {
        if (mailSender == null) {
            LOG.debug("Mail sender not configured — skipping pipeline-degraded admin alert "
                    + "(runId={})", runId);
            return;
        }
        List<String> recipients = appUserRepository.findByRoleAndEnabledTrue(UserRole.ADMIN)
                .stream()
                .map(AppUserEntity::getEmail)
                .filter(email -> email != null && !email.isBlank())
                .toList();
        if (recipients.isEmpty()) {
            LOG.warn("Pipeline run {} degraded, but no enabled ADMIN account has an email "
                    + "address — no alert sent", runId);
            return;
        }

        String subject = "PhotoCast: pipeline run " + runId + " degraded — "
                + subjectClause(failureSummary);
        String body = buildBody(runId, cycleType, triggerTime, failureSummary);

        for (String recipient : recipients) {
            try {
                sendPlainTextEmail(recipient, subject, body);
                LOG.info("Pipeline-degraded alert sent to {} for run {}",
                        LogSanitizer.sanitize(recipient), runId);
            } catch (Exception ex) {
                // Best-effort: one admin's mailbox rejecting the message must not stop the
                // others from being told, and must never propagate into the orchestrator.
                LOG.warn("Failed to send pipeline-degraded alert to {} for run {}: {}",
                        LogSanitizer.sanitize(recipient), runId,
                        LogSanitizer.sanitize(ex.getMessage()));
            }
        }
    }

    /**
     * The subject line's own clause is the short leading sentence of the failure summary — e.g.
     * {@code "3 of 3 forecast batch submissions failed"} out of {@code
     * BatchSubmissionSummary#detail()}'s full {@code "3 of 3 forecast batch submissions failed
     * (510 requests not submitted — near-term inland, ...)"}. The parenthetical (request count,
     * bucket names) is detail for the body, not the subject line; splitting on the first
     * {@code " ("} is safe because {@code detail()} never puts one inside its own leading clause.
     * Falls back to the whole string if the shape ever changes, rather than silently truncating
     * something else.
     */
    private static String subjectClause(String failureSummary) {
        int parenIndex = failureSummary.indexOf(" (");
        return parenIndex > 0 ? failureSummary.substring(0, parenIndex) : failureSummary;
    }

    private static String buildBody(Long runId, CycleType cycleType, Instant triggerTime,
            String failureSummary) {
        String triggerText = triggerTime != null
                ? TRIGGER_TIME_FORMAT.format(triggerTime)
                : "unknown";
        return "Pipeline run " + runId + " (" + cycleType + ") degraded.\n\n"
                + "Trigger time: " + triggerText + "\n\n"
                + failureSummary + "\n\n"
                + "The app is still serving ratings from the previous successful run.\n";
    }

    private void sendPlainTextEmail(String to, String subject, String body)
            throws MessagingException {
        // Jakarta Mail's ServiceLoader.load() fails on async/ForkJoinPool threads inside
        // Spring Boot fat JARs — set the app classloader so it can find StreamProvider. Same
        // workaround UserEmailService applies for the identical reason.
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(FROM_ADDRESS);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(message);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }
}
