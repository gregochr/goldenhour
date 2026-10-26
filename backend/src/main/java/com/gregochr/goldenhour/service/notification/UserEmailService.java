package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.config.EmailTextTemplateConfig;
import com.gregochr.goldenhour.config.RegistrationProperties;
import com.gregochr.goldenhour.util.LogSanitizer;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Sends transactional emails to users for account creation, verification and password resets.
 *
 * <p>Every message is {@code multipart/alternative}: a hand-written plain-text part (the {@code text/<name>}
 * template) followed by the HTML part, rendered from the same Thymeleaf context. HTML-only mail is marked down
 * by Outlook/Hotmail and Gmail spam filters.
 *
 * <p>Methods are {@link Async} so they never block the admin's HTTP response.
 * Failures are caught and logged — a mail delivery problem must never prevent
 * the controller from returning the temporary password to the admin.
 *
 * <p>Gracefully skips sending when {@code JavaMailSender} is not configured
 * (e.g. local dev without SMTP), matching the pattern used by
 * {@link EmailNotificationService}.
 */
@Service
public class UserEmailService {

    private static final Logger LOG = LoggerFactory.getLogger(UserEmailService.class);
    private static final String TEXT_TEMPLATE_PREFIX = EmailTextTemplateConfig.TEXT_TEMPLATE_PREFIX;
    private static final String FROM_ADDRESS = "noreply@photocast.online";
    private static final DateTimeFormatter LAPSES_ON_FORMAT =
            DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK);

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final RegistrationProperties registrationProperties;

    /** Base URL of the frontend app, used to build verification links. */
    @Value("${app.frontend-base-url:http://localhost:5173}")
    private String frontendBaseUrl;

    /**
     * Constructs a {@code UserEmailService}.
     *
     * @param mailSender     Spring mail sender (optional — null when SMTP is not configured)
     * @param templateEngine Thymeleaf template engine for rendering the HTML and plain-text emails
     * @param registrationProperties the hold window, for the day counts the sign-up emails speak in
     */
    public UserEmailService(
            @Autowired(required = false) JavaMailSender mailSender,
            TemplateEngine templateEngine,
            RegistrationProperties registrationProperties) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.registrationProperties = registrationProperties;
    }

    /**
     * Whether a mail sender is configured at all (false in local/dev without SMTP).
     *
     * @return true when emails can be sent
     */
    public boolean isConfigured() {
        return mailSender != null;
    }

    /**
     * Sends a welcome email to a newly created user with their temporary password.
     *
     * @param email             the recipient's email address
     * @param username          the user's login name
     * @param temporaryPassword the plain-text temporary password
     */
    @Async
    public void sendWelcomeEmail(String email, String username, String temporaryPassword) {
        if (!canSend(email)) {
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("username", username);
            context.setVariable("temporaryPassword", temporaryPassword);
            String html = templateEngine.process("welcome-email", context);
            String text = templateEngine.process(TEXT_TEMPLATE_PREFIX + "welcome-email", context);

            sendEmail(email, "Welcome to PhotoCast — Your Account is Ready", text, html);
            LOG.info("Welcome email sent to {} for user {}",
                    LogSanitizer.sanitize(email), LogSanitizer.sanitize(username));
        } catch (Exception ex) {
            LOG.error("Failed to send welcome email to {} for user {}: {}",
                    LogSanitizer.sanitize(email), LogSanitizer.sanitize(username),
                    LogSanitizer.sanitize(ex.getMessage()));
        }
    }

    /**
     * Sends a password reset email to a user with their new temporary password.
     *
     * @param email             the recipient's email address
     * @param username          the user's login name
     * @param temporaryPassword the plain-text temporary password
     */
    @Async
    public void sendPasswordResetEmail(String email, String username, String temporaryPassword) {
        if (!canSend(email)) {
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("username", username);
            context.setVariable("temporaryPassword", temporaryPassword);
            String html = templateEngine.process("password-reset-email", context);
            String text = templateEngine.process(TEXT_TEMPLATE_PREFIX + "password-reset-email", context);

            sendEmail(email, "PhotoCast — Your Password Has Been Reset", text, html);
            LOG.info("Password reset email sent to {} for user {}",
                    LogSanitizer.sanitize(email), LogSanitizer.sanitize(username));
        } catch (Exception ex) {
            LOG.error("Failed to send password reset email to {} for user {}: {}",
                    LogSanitizer.sanitize(email), LogSanitizer.sanitize(username),
                    LogSanitizer.sanitize(ex.getMessage()));
        }
    }

    /**
     * Sends a verification email to a newly registered user with a verification link.
     *
     * @param email    the recipient's email address
     * @param username the user's chosen login name
     * @param rawToken the raw verification token (included in the link URL)
     */
    @Async
    public void sendVerificationEmail(String email, String username, String rawToken) {
        if (!canSend(email)) {
            return;
        }
        try {
            String verificationUrl = appLink("/verify", rawToken);
            Context context = new Context();
            context.setVariable("username", username);
            context.setVariable("verificationUrl", verificationUrl);
            context.setVariable("holdDays", holdDays());
            String html = templateEngine.process("verification-email", context);
            String text = templateEngine.process(TEXT_TEMPLATE_PREFIX + "verification-email", context);

            sendEmail(email, "PhotoCast — Verify Your Email Address", text, html);
            LOG.info("Verification email sent to {} for user {}",
                    LogSanitizer.sanitize(email), LogSanitizer.sanitize(username));
        } catch (Exception ex) {
            LOG.error("Failed to send verification email to {} for user {}: {}",
                    LogSanitizer.sanitize(email), LogSanitizer.sanitize(username),
                    LogSanitizer.sanitize(ex.getMessage()));
        }
    }

    /**
     * Sends a reminder to a never-verified sign-up, with a FRESH verification link. SYNCHRONOUS (not
     * {@link Async}): the pending-registration sweep needs to know whether the mail left, so it can revert
     * its claim when it did not.
     *
     * @param email          the recipient's stored address
     * @param username       the account's login name
     * @param rawToken       the raw verification token (included in the link URL)
     * @param finalReminder  true for the last-day copy, false for the first reminder
     * @param placeLapsesAt  when the place is released, shown as a date
     * @return true only when the mail sender accepted the message; false when no sender is configured, the
     *         address is blank, or anything went wrong (logged at WARN)
     */
    public boolean sendVerificationReminderEmail(String email, String username, String rawToken,
            boolean finalReminder, LocalDateTime placeLapsesAt) {
        if (!canSend(email)) {
            return false;
        }
        try {
            Context context = new Context();
            context.setVariable("username", username);
            context.setVariable("verificationUrl", appLink("/verify", rawToken));
            context.setVariable("finalReminder", finalReminder);
            context.setVariable("daysLeft", registrationProperties.getFirstReminderHoursLeft()
                    / RegistrationProperties.HOURS_PER_DAY);
            context.setVariable("lapsesOn", placeLapsesAt.format(LAPSES_ON_FORMAT));
            String html = templateEngine.process("verification-reminder-email", context);
            String text = templateEngine.process(TEXT_TEMPLATE_PREFIX + "verification-reminder-email", context);
            String subject = finalReminder
                    ? "PhotoCast — last day to claim your place"
                    : "PhotoCast — your place is held for " + registrationProperties.getFirstReminderHoursLeft()
                            / RegistrationProperties.HOURS_PER_DAY + " more days";
            sendEmail(email, subject, text, html);
            LOG.info("Verification reminder sent for user {}", LogSanitizer.sanitize(username));
            LOG.debug("Verification reminder address {}", LogSanitizer.sanitize(email));
            return true;
        } catch (Exception ex) {
            LOG.warn("Failed to send verification reminder for user {}: {}",
                    LogSanitizer.sanitize(username), LogSanitizer.sanitize(ex.getMessage()));
            return false;
        }
    }

    private int holdDays() {
        return registrationProperties.getPendingHoldHours() / RegistrationProperties.HOURS_PER_DAY;
    }

    /**
     * Sends the "reset your password" email: a one-hour link to the app's {@code /reset} page.
     *
     * <p>Distinct from {@link #sendPasswordResetEmail}, which mails an admin-issued TEMPORARY PASSWORD:
     * this one carries a link and no secret but the link's own token. The caller passes the account's
     * STORED address, never the text of the request that asked for the reset.
     *
     * @param email    the recipient's stored email address
     * @param username the account's login name
     * @param rawToken the raw reset token (included in the link URL)
     */
    @Async
    public void sendPasswordResetLinkEmail(String email, String username, String rawToken) {
        if (!canSend(email)) {
            return;
        }
        try {
            String resetUrl = appLink("/reset", rawToken);
            Context context = new Context();
            context.setVariable("username", username);
            context.setVariable("resetUrl", resetUrl);
            String html = templateEngine.process("password-reset-link-email", context);
            String text = templateEngine.process(TEXT_TEMPLATE_PREFIX + "password-reset-link-email", context);

            sendEmail(email, "Reset your PhotoCast password", text, html);
            LOG.info("Password reset link email sent to {} for user {}",
                    LogSanitizer.sanitize(email), LogSanitizer.sanitize(username));
        } catch (Exception ex) {
            LOG.error("Failed to send password reset link email to {} for user {}: {}",
                    LogSanitizer.sanitize(email), LogSanitizer.sanitize(username),
                    LogSanitizer.sanitize(ex.getMessage()));
        }
    }

    /**
     * Sends a notification email to a user whose account has been deleted.
     *
     * @param email    the recipient's email address
     * @param username the user's login name
     */
    @Async
    public void sendAccountDeletedEmail(String email, String username) {
        if (!canSend(email)) {
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("username", username);
            String html = templateEngine.process("account-deleted-email", context);
            String text = templateEngine.process(TEXT_TEMPLATE_PREFIX + "account-deleted-email", context);

            sendEmail(email, "PhotoCast — Your Account Has Been Removed", text, html);
            LOG.info("Account deleted email sent to {} for user {}",
                    LogSanitizer.sanitize(email), LogSanitizer.sanitize(username));
        } catch (Exception ex) {
            LOG.error("Failed to send account deleted email to {} for user {}: {}",
                    LogSanitizer.sanitize(email), LogSanitizer.sanitize(username),
                    LogSanitizer.sanitize(ex.getMessage()));
        }
    }

    /**
     * Sends the dormant-account warning: the account has been unused for a long while and will be deleted
     * unless the reader signs in first. SYNCHRONOUS (not {@link Async}) and returning whether the mail left,
     * because the dormant sweep stamps its warning only after the sender accepted it.
     *
     * <p>Logs the account id only: no address and no username is written to the log by this path (the address
     * at DEBUG at most).
     *
     * @param userId        the account's primary key, the only identifier logged
     * @param email         the recipient's stored address
     * @param username      the account's login name, shown in the greeting
     * @param inactiveDays  the number of days of inactivity the mail speaks of
     * @param deletableFrom the earliest moment the account may be deleted, shown as a date
     * @return true only when the mail sender accepted the message; false when no sender is configured, the
     *         address is blank, or anything went wrong (logged at WARN, without the message text)
     */
    public boolean sendDormancyWarningEmail(Long userId, String email, String username, int inactiveDays,
            LocalDateTime deletableFrom) {
        if (!canSend(email)) {
            return false;
        }
        try {
            Context context = new Context();
            context.setVariable("username", username);
            context.setVariable("signInUrl", appBaseUrl());
            context.setVariable("inactiveDays", inactiveDays);
            context.setVariable("deletesOn", deletableFrom.format(LAPSES_ON_FORMAT));
            String html = templateEngine.process("dormancy-warning-email", context);
            String text = templateEngine.process(TEXT_TEMPLATE_PREFIX + "dormancy-warning-email", context);
            sendEmail(email, "PhotoCast — sign in to keep your account", text, html);
            LOG.info("Dormancy warning sent for user id={}", userId);
            LOG.debug("Dormancy warning address {}", LogSanitizer.sanitize(email));
            return true;
        } catch (Exception ex) {
            LOG.warn("Failed to send dormancy warning for user id={}: {}", userId, ex.getClass().getSimpleName());
            LOG.debug("Dormancy warning failure detail: {}", LogSanitizer.sanitize(ex.getMessage()));
            return false;
        }
    }

    /**
     * Sends the account-deleted notice to a reader the dormant sweep has deleted. Same message as
     * {@link #sendAccountDeletedEmail(String, String)}, but this path logs the account id only: no address and
     * no username is written to the log (the address at DEBUG at most).
     *
     * @param userId   the deleted account's former primary key, the only identifier logged
     * @param email    the recipient's stored address
     * @param username the account's login name, shown in the greeting
     */
    @Async
    public void sendAccountDeletedEmail(Long userId, String email, String username) {
        if (!canSend(email)) {
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("username", username);
            String html = templateEngine.process("account-deleted-email", context);
            String text = templateEngine.process(TEXT_TEMPLATE_PREFIX + "account-deleted-email", context);

            sendEmail(email, "PhotoCast — Your Account Has Been Removed", text, html);
            LOG.info("Account deleted email sent for former user id={}", userId);
            LOG.debug("Account deleted address {}", LogSanitizer.sanitize(email));
        } catch (Exception ex) {
            LOG.error("Failed to send account deleted email for former user id={}: {}", userId,
                    ex.getClass().getSimpleName());
            LOG.debug("Account deleted failure detail: {}", LogSanitizer.sanitize(ex.getMessage()));
        }
    }

    /**
     * Sends a waitlist invitation: a link to the app's {@code /register} page carrying the one-time invite
     * token in the URL fragment ({@code #invite=}). Asynchronous; logs the waitlist row id only, never the
     * address (the address at DEBUG at most).
     *
     * @param waitlistId the waitlist row's primary key, the only identifier logged
     * @param email      the invited address
     * @param rawToken   the raw invite token (included in the link URL)
     * @param expiresAt  when the invitation stops working, shown as a date
     */
    @Async
    public void sendWaitlistInviteEmail(Long waitlistId, String email, String rawToken, LocalDateTime expiresAt) {
        if (!canSend(email)) {
            return;
        }
        try {
            Context context = new Context();
            // The slot every email template greets from: a waitlisted reader has no username, so it is the address.
            context.setVariable("username", email);
            context.setVariable("inviteUrl", inviteLink(rawToken));
            context.setVariable("expiresOn", expiresAt.format(LAPSES_ON_FORMAT));
            String html = templateEngine.process("waitlist-invite-email", context);
            String text = templateEngine.process(TEXT_TEMPLATE_PREFIX + "waitlist-invite-email", context);

            sendEmail(email, "PhotoCast — a place has opened for you", text, html);
            LOG.info("Waitlist invitation sent for waitlist id={}", waitlistId);
            LOG.debug("Waitlist invitation address {}", LogSanitizer.sanitize(email));
        } catch (Exception ex) {
            LOG.error("Failed to send waitlist invitation for waitlist id={}: {}", waitlistId,
                    ex.getClass().getSimpleName());
            LOG.debug("Waitlist invitation failure detail: {}", LogSanitizer.sanitize(ex.getMessage()));
        }
    }

    /**
     * Builds the registration link a waitlist invitation carries: {@code <app>/register#invite=<token>}. It
     * is NOT {@code #token=}, which the app treats as an email-verification link. The fragment never reaches
     * the server or a {@code Referer}.
     *
     * @param rawToken the raw invite token (a UUID string, so it needs no encoding)
     * @return the absolute URL
     */
    private String inviteLink(String rawToken) {
        return appBaseUrl() + "/register#invite=" + rawToken;
    }

    /** The app origin without a trailing slash: where a reader signs in. */
    private String appBaseUrl() {
        return frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
    }

    /**
     * Builds a link to a path on the app origin carrying a one-time token in the URL fragment
     * ({@code #token=}), tolerating a configured base URL with or without a trailing slash. A fragment
     * is never sent to the server, so the token stays out of the nginx access log, Cloudflare's request
     * line and any {@code Referer}. The token is a {@link java.util.UUID} string (hex and dashes), so
     * it needs no encoding.
     *
     * @param path     the route, with a leading slash ({@code /verify}, {@code /reset})
     * @param rawToken the raw one-time token
     * @return the absolute URL
     */
    private String appLink(String path, String rawToken) {
        String base = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
        return base + path + "#token=" + rawToken;
    }

    private boolean canSend(String email) {
        if (mailSender == null) {
            LOG.debug("Mail sender not configured — skipping user email");
            return false;
        }
        if (email == null || email.isBlank()) {
            LOG.debug("No email address provided — skipping user email");
            return false;
        }
        return true;
    }

    private void sendEmail(String to, String subject, String plainText, String htmlBody) throws MessagingException {
        // Jakarta Mail's ServiceLoader.load() fails on async/ForkJoinPool threads inside
        // Spring Boot fat JARs — set the app classloader so it can find StreamProvider.
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(FROM_ADDRESS);
            helper.setTo(to);
            helper.setSubject(subject);
            // Built by hand so the top-level type is multipart/alternative, plain text first (the helper's
            // multipart modes all wrap the alternative part in multipart/mixed or /related).
            MimeMultipart alternative = new MimeMultipart("alternative");
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(plainText, "UTF-8", "plain");
            alternative.addBodyPart(textPart);
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setText(htmlBody, "UTF-8", "html");
            alternative.addBodyPart(htmlPart);
            message.setContent(alternative);
            mailSender.send(message);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }
}
