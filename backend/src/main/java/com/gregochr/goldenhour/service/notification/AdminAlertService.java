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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Supplier;

/**
 * Emails every enabled ADMIN account when the pipeline orchestrator marks a cycle
 * {@code DEGRADED} — the one admin-alert channel this app has, born from the
 * 2026-09-29 incident where a fully-failed forecast batch submission left the
 * cycle {@code COMPLETED}, silently serving ratings a day stale, with nobody told.
 *
 * <p>The same channel also carries the two alerts of the location auto-disable rule
 * ({@code LocationFailureService}): one when a cycle disables places, one when more places qualify
 * than the per-cycle cap allows and none is disabled.
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
    private final boolean enabled;

    /**
     * Constructs the service.
     *
     * @param mailSender         Spring mail sender (optional — null when SMTP is not configured,
     *                           matching {@link UserEmailService}'s own constructor)
     * @param appUserRepository  resolves the enabled-ADMIN recipient list
     * @param enabled            {@code notifications.admin-alerts.enabled}, default {@code false}
     *                           when absent — {@code application-local.yml} points {@code
     *                           spring.mail} at a real SMTP server, so this must default OFF or a
     *                           local run with {@code MAIL_PASSWORD} set would email every ADMIN
     *                           in the local database on a degraded run
     */
    public AdminAlertService(
            @Autowired(required = false) JavaMailSender mailSender,
            AppUserRepository appUserRepository,
            @Value("${notifications.admin-alerts.enabled:false}") boolean enabled) {
        this.mailSender = mailSender;
        this.appUserRepository = appUserRepository;
        this.enabled = enabled;
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
        if (!enabled) {
            LOG.debug("notifications.admin-alerts.enabled=false — skipping pipeline-degraded "
                    + "admin alert (runId={})", runId);
            return;
        }
        Supplier<String> subject = () -> "PhotoCast: pipeline run " + runId + " degraded — "
                + subjectClause(failureSummary);
        deliver(subject, () -> buildBody(runId, cycleType, triggerTime, failureSummary),
                "pipeline-degraded", runId);
    }

    /**
     * Sends the "locations auto-disabled" alert: one message per cycle naming every place the
     * cycle's settle disabled after {@code LocationFailureService#AUTO_DISABLE_THRESHOLD}
     * consecutive counted failures, with the stored reason for each.
     *
     * <p>Same channel, same recipients and same best-effort rules as
     * {@link #sendPipelineDegradedAlert}: asynchronous, never throws, silent when alerts are
     * disabled or no mail sender or admin address exists. A place that vanishes from every user's
     * Plan and map is exactly what an admin must hear about.
     *
     * @param runId       the pipeline run whose settle disabled the places
     * @param cycleType   NIGHTLY or INTRADAY
     * @param triggerTime when the cycle started (UTC)
     * @param disabled    the places disabled this cycle, each with its stored reason
     */
    @Async
    public void sendLocationsAutoDisabledAlert(Long runId, CycleType cycleType, Instant triggerTime,
            List<DisabledLocation> disabled) {
        if (!enabled) {
            LOG.debug("notifications.admin-alerts.enabled=false — skipping locations-auto-disabled "
                    + "admin alert (runId={})", runId);
            return;
        }
        String noun = disabled.size() == 1 ? "location" : "locations";
        Supplier<String> subject = () -> "PhotoCast: " + disabled.size() + " " + noun
                + " auto-disabled after pipeline run " + runId;
        deliver(subject, () -> buildAutoDisabledBody(runId, cycleType, triggerTime, disabled),
                "locations-auto-disabled", runId);
    }

    /**
     * Sends the "auto-disable cap" alert: more places than {@code
     * LocationFailureService#MAX_DISABLED_PER_CYCLE} reached the failure threshold in one cycle, so
     * none was disabled. Many places failing at once points at something systemic (an outage, a bad
     * deploy, a rejected key) rather than many broken places, and an admin must hear about that
     * instead of finding the roster silently emptied or silently untouched.
     *
     * @param runId       the pipeline run whose settle hit the cap
     * @param cycleType   NIGHTLY or INTRADAY
     * @param triggerTime when the cycle started (UTC)
     * @param names       every place that qualified for disabling this cycle
     * @param cap         the per-cycle disable cap that was exceeded
     */
    @Async
    public void sendLocationDisableCapAlert(Long runId, CycleType cycleType, Instant triggerTime,
            List<String> names, int cap) {
        if (!enabled) {
            LOG.debug("notifications.admin-alerts.enabled=false — skipping location-disable-cap "
                    + "admin alert (runId={})", runId);
            return;
        }
        Supplier<String> subject = () -> "PhotoCast: " + names.size()
                + " locations failed repeatedly on pipeline run " + runId + " — none disabled";
        deliver(subject, () -> buildCapBody(runId, cycleType, triggerTime, names, cap),
                "location-disable-cap", runId);
    }

    /**
     * One place disabled by the auto-disable rule, as named in the alert.
     *
     * @param name   the location name
     * @param reason the stored, fixed-shape disabled reason
     */
    public record DisabledLocation(String name, String reason) {
    }

    /**
     * Mails {@code subject} and the lazily built body to every enabled ADMIN with an address.
     *
     * <p>The WHOLE body is guarded, not just the per-recipient send loop: a failure in the
     * recipient query or in building the body must be exactly as best-effort as a failure sending
     * to one recipient — every public method here promises it never throws into the caller.
     */
    private void deliver(Supplier<String> subjectSupplier, Supplier<String> bodySupplier,
            String what, Long runId) {
        try {
            if (mailSender == null) {
                LOG.debug("Mail sender not configured — skipping {} admin alert (runId={})",
                        what, runId);
                return;
            }
            List<String> recipients = appUserRepository.findByRoleAndEnabledTrue(UserRole.ADMIN)
                    .stream()
                    .map(AppUserEntity::getEmail)
                    .filter(email -> email != null && !email.isBlank())
                    .toList();
            if (recipients.isEmpty()) {
                LOG.warn("Pipeline run {} raised a {} alert, but no enabled ADMIN account has an "
                        + "email address — no alert sent", runId, what);
                return;
            }

            String subject = subjectSupplier.get();
            String body = bodySupplier.get();
            for (String recipient : recipients) {
                try {
                    sendPlainTextEmail(recipient, subject, body);
                    LOG.info("{} alert sent to {} for run {}", what,
                            LogSanitizer.sanitize(recipient), runId);
                } catch (Exception ex) {
                    // Best-effort: one admin's mailbox rejecting the message must not stop the
                    // others from being told, and must never propagate into the caller.
                    LOG.warn("Failed to send {} alert to {} for run {}: {}", what,
                            LogSanitizer.sanitize(recipient), runId,
                            LogSanitizer.sanitize(ex.getMessage()));
                }
            }
        } catch (Exception ex) {
            LOG.warn("{} admin alert failed for run {}: {}", what, runId,
                    LogSanitizer.sanitize(ex.getMessage()), ex);
        }
    }

    private static String triggerText(Instant triggerTime) {
        return triggerTime != null ? TRIGGER_TIME_FORMAT.format(triggerTime) : "unknown";
    }

    private static String buildAutoDisabledBody(Long runId, CycleType cycleType,
            Instant triggerTime, List<DisabledLocation> disabled) {
        StringBuilder body = new StringBuilder();
        body.append("Pipeline run ").append(runId).append(" (").append(cycleType)
                .append(") auto-disabled ").append(disabled.size())
                .append(disabled.size() == 1 ? " location" : " locations").append(".\n\n")
                .append("Trigger time: ").append(triggerText(triggerTime)).append("\n\n");
        for (DisabledLocation location : disabled) {
            body.append("- ").append(location.name()).append(": ").append(location.reason())
                    .append('\n');
        }
        body.append("\nThese places no longer appear in any forecast. Re-enable each one from "
                + "Manage > Locations (Location Issues) once the cause is fixed.\n");
        return body.toString();
    }

    private static String buildCapBody(Long runId, CycleType cycleType, Instant triggerTime,
            List<String> names, int cap) {
        return "Pipeline run " + runId + " (" + cycleType + "): " + names.size()
                + " locations reached the consecutive-failure threshold in this one cycle, more "
                + "than the cap of " + cap + " disabled per cycle.\n\n"
                + "That points at something systemic rather than that many broken places, so NONE "
                + "was disabled.\n\n"
                + "Trigger time: " + triggerText(triggerTime) + "\n\n"
                + "Locations: " + String.join(", ", names) + "\n";
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
        return "Pipeline run " + runId + " (" + cycleType + ") degraded.\n\n"
                + "Trigger time: " + triggerText(triggerTime) + "\n\n"
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
