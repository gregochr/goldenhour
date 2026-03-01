package com.gregochr.goldenhour.service.notification;

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
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Sends transactional emails to users for account creation and password resets.
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
    private static final String FROM_ADDRESS = "noreply@photocast.online";

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    /** Base URL of the frontend app, used to build verification links. */
    @Value("${app.frontend-base-url:http://localhost:5173}")
    private String frontendBaseUrl;

    /**
     * Constructs a {@code UserEmailService}.
     *
     * @param mailSender     Spring mail sender (optional — null when SMTP is not configured)
     * @param templateEngine Thymeleaf template engine for rendering HTML emails
     */
    public UserEmailService(
            @Autowired(required = false) JavaMailSender mailSender,
            TemplateEngine templateEngine) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
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

            sendHtmlEmail(email, "Welcome to PhotoCast — Your Account is Ready", html);
            LOG.info("Welcome email sent to {} for user {}", email, username);
        } catch (Exception ex) {
            LOG.error("Failed to send welcome email to {} for user {}: {}", email, username, ex.getMessage());
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

            sendHtmlEmail(email, "PhotoCast — Your Password Has Been Reset", html);
            LOG.info("Password reset email sent to {} for user {}", email, username);
        } catch (Exception ex) {
            LOG.error("Failed to send password reset email to {} for user {}: {}", email, username, ex.getMessage());
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
            String verificationUrl = frontendBaseUrl + "?token=" + rawToken;
            Context context = new Context();
            context.setVariable("username", username);
            context.setVariable("verificationUrl", verificationUrl);
            String html = templateEngine.process("verification-email", context);

            sendHtmlEmail(email, "PhotoCast — Verify Your Email Address", html);
            LOG.info("Verification email sent to {} for user {}", email, username);
        } catch (Exception ex) {
            LOG.error("Failed to send verification email to {} for user {}: {}",
                    email, username, ex.getMessage());
        }
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

    private void sendHtmlEmail(String to, String subject, String htmlBody) throws MessagingException {
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
            helper.setText(htmlBody, true);
            mailSender.send(message);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }
}
