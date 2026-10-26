package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.config.RegistrationProperties;
import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IContext;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.LocalDateTime;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserEmailService}.
 */
@ExtendWith(MockitoExtension.class)
class UserEmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private TemplateEngine templateEngine;

    @Test
    @DisplayName("sendWelcomeEmail skips silently when mailSender is null")
    void sendWelcomeEmail_nullMailSender_skips() {
        UserEmailService service = new UserEmailService(null, templateEngine, new RegistrationProperties());

        service.sendWelcomeEmail("test@example.com", "alice", "TempPass1!");

        verify(templateEngine, never()).process(any(String.class), any(IContext.class));
    }

    @Test
    @DisplayName("sendWelcomeEmail skips silently when email is blank")
    void sendWelcomeEmail_blankEmail_skips() {
        UserEmailService service = new UserEmailService(mailSender, templateEngine, new RegistrationProperties());

        service.sendWelcomeEmail("", "alice", "TempPass1!");

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendWelcomeEmail skips silently when email is null")
    void sendWelcomeEmail_nullEmail_skips() {
        UserEmailService service = new UserEmailService(mailSender, templateEngine, new RegistrationProperties());

        service.sendWelcomeEmail(null, "alice", "TempPass1!");

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendWelcomeEmail renders template and sends HTML email")
    void sendWelcomeEmail_validInput_sendsEmail() {
        UserEmailService service = new UserEmailService(mailSender, templateEngine, new RegistrationProperties());
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(eq("welcome-email"), any(IContext.class)))
                .thenReturn("<html>Welcome</html>");
        when(templateEngine.process(eq("text/welcome-email"), any(IContext.class)))
                .thenReturn("plain text");

        service.sendWelcomeEmail("alice@example.com", "alice", "TempPass1!");

        verify(templateEngine).process(eq("welcome-email"), any(IContext.class));
        verify(mailSender).send(mimeMessage);
    }

    @Test
    @DisplayName("sendPasswordResetEmail renders template and sends HTML email")
    void sendPasswordResetEmail_validInput_sendsEmail() {
        UserEmailService service = new UserEmailService(mailSender, templateEngine, new RegistrationProperties());
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(eq("password-reset-email"), any(IContext.class)))
                .thenReturn("<html>Reset</html>");
        when(templateEngine.process(eq("text/password-reset-email"), any(IContext.class)))
                .thenReturn("plain text");

        service.sendPasswordResetEmail("alice@example.com", "alice", "TempPass1!");

        verify(templateEngine).process(eq("password-reset-email"), any(IContext.class));
        verify(mailSender).send(mimeMessage);
    }

    @Test
    @DisplayName("sendWelcomeEmail catches and logs exceptions without propagating")
    void sendWelcomeEmail_sendFails_doesNotPropagate() {
        UserEmailService service = new UserEmailService(mailSender, templateEngine, new RegistrationProperties());
        when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("SMTP down"));

        // Should not throw
        service.sendWelcomeEmail("alice@example.com", "alice", "TempPass1!");
    }

    private UserEmailService serviceWithBaseUrl(String baseUrl) {
        UserEmailService service = new UserEmailService(mailSender, templateEngine, new RegistrationProperties());
        ReflectionTestUtils.setField(service, "frontendBaseUrl", baseUrl);
        return service;
    }

    /** Sends one mail and returns the Thymeleaf context the template was rendered with. */
    private Context renderedContext(String template, Runnable send) throws Exception {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(eq(template), any(IContext.class))).thenReturn("<html></html>");
        when(templateEngine.process(eq("text/" + template), any(IContext.class))).thenReturn("plain text");

        send.run();

        ArgumentCaptor<IContext> captor = ArgumentCaptor.forClass(IContext.class);
        verify(templateEngine).process(eq(template), captor.capture());
        return (Context) captor.getValue();
    }

    @Test
    @DisplayName("sendVerificationEmail links to /verify#token= on the app origin")
    void sendVerificationEmail_linksToTheVerifyPath() throws Exception {
        UserEmailService service = serviceWithBaseUrl("https://app.photocast.online");

        Context context = renderedContext("verification-email",
                () -> service.sendVerificationEmail("alice@example.com", "alice", "raw-token-uuid"));

        assertThat(context.getVariable("verificationUrl"))
                .isEqualTo("https://app.photocast.online/verify#token=raw-token-uuid");
        assertThat(context.getVariable("username")).isEqualTo("alice");
    }

    @Test
    @DisplayName("a base URL configured with a trailing slash does not produce a double slash")
    void links_toleratesATrailingSlashOnTheBaseUrl() throws Exception {
        UserEmailService service = serviceWithBaseUrl("https://app.photocast.online/");

        Context context = renderedContext("verification-email",
                () -> service.sendVerificationEmail("alice@example.com", "alice", "tok"));

        assertThat(context.getVariable("verificationUrl"))
                .isEqualTo("https://app.photocast.online/verify#token=tok");
    }

    @Test
    @DisplayName("sendPasswordResetLinkEmail links to /reset#token=, names the user and mails the given address "
            + "with the agreed subject")
    void sendPasswordResetLinkEmail_linksToTheResetPath() throws Exception {
        UserEmailService service = serviceWithBaseUrl("https://app.photocast.online");
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(eq("password-reset-link-email"), any(IContext.class)))
                .thenReturn("<html>Reset link</html>");
        when(templateEngine.process(eq("text/password-reset-link-email"), any(IContext.class)))
                .thenReturn("plain text");

        service.sendPasswordResetLinkEmail("Alice@Example.com", "alice", "reset-raw-token");

        ArgumentCaptor<IContext> captor = ArgumentCaptor.forClass(IContext.class);
        verify(templateEngine).process(eq("password-reset-link-email"), captor.capture());
        Context context = (Context) captor.getValue();
        assertThat(context.getVariable("resetUrl"))
                .isEqualTo("https://app.photocast.online/reset#token=reset-raw-token");
        assertThat(context.getVariable("username")).isEqualTo("alice");
        verify(mimeMessage).setSubject("Reset your PhotoCast password", "UTF-8");
        verify(mailSender).send(mimeMessage);
    }

    @Test
    @DisplayName("sendPasswordResetLinkEmail skips silently when mailSender is null or the address blank")
    void sendPasswordResetLinkEmail_skipsWhenItCannotSend() {
        new UserEmailService(null, templateEngine, new RegistrationProperties())
                .sendPasswordResetLinkEmail("alice@example.com", "alice", "tok");
        new UserEmailService(mailSender, templateEngine, new RegistrationProperties())
                .sendPasswordResetLinkEmail(" ", "alice", "tok");

        verify(templateEngine, never()).process(any(String.class), any(IContext.class));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendPasswordResetLinkEmail catches and logs a send failure without propagating")
    void sendPasswordResetLinkEmail_sendFails_doesNotPropagate() {
        UserEmailService service = serviceWithBaseUrl("https://app.photocast.online");
        when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("SMTP down"));
        when(templateEngine.process(eq("password-reset-link-email"), any(IContext.class)))
                .thenReturn("<html></html>");
        when(templateEngine.process(eq("text/password-reset-link-email"), any(IContext.class)))
                .thenReturn("plain text");

        service.sendPasswordResetLinkEmail("alice@example.com", "alice", "tok");
    }

    @Test
    @DisplayName("the reset email template carries the agreed copy, the username and the link, "
            + "and escapes a hostile username")
    void resetTemplate_rendersTheAgreedCopy() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine real = new SpringTemplateEngine();
        real.setTemplateResolver(resolver);
        Context context = new Context();
        context.setVariable("username", "<b>alice</b>");
        context.setVariable("resetUrl", "https://app.photocast.online/reset#token=abc");

        String html = real.process("password-reset-link-email", context);

        assertThat(html)
                .contains("Someone asked to reset the password for")
                .contains("If that was you, use the link below within the next hour.")
                .contains("If it wasn't, you can ignore this email")
                .contains("your password hasn't changed.")
                .contains("href=\"https://app.photocast.online/reset#token=abc\"")
                .contains("&lt;b&gt;alice&lt;/b&gt;")
                .doesNotContain("<b>alice</b>");
    }

    /** Sends through a real MimeMessage and returns the saved multipart body. */
    private MimeMultipart sendAndCaptureMultipart(Runnable send) throws Exception {
        MimeMessage real = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(real);
        send.run();
        verify(mailSender).send(real);
        real.saveChanges();
        assertThat(real.getContentType()).startsWith("multipart/alternative");
        return (MimeMultipart) real.getContent();
    }

    @Test
    @DisplayName("the reset-link email is multipart/alternative: plain text first, then the HTML")
    void resetLinkEmail_isMultipartAlternativeWithTextFirst() throws Exception {
        UserEmailService service = serviceWithBaseUrl("https://app.photocast.online");
        when(templateEngine.process(eq("password-reset-link-email"), any(IContext.class)))
                .thenReturn("<html>Reset link</html>");
        when(templateEngine.process(eq("text/password-reset-link-email"), any(IContext.class)))
                .thenReturn("Reset link as text");

        MimeMultipart multipart = sendAndCaptureMultipart(
                () -> service.sendPasswordResetLinkEmail("alice@example.com", "alice", "tok"));

        assertThat(multipart.getCount()).isEqualTo(2);
        BodyPart plain = multipart.getBodyPart(0);
        BodyPart html = multipart.getBodyPart(1);
        assertThat(plain.getContentType()).startsWith("text/plain");
        assertThat(plain.getContent()).isEqualTo("Reset link as text");
        assertThat(html.getContentType()).startsWith("text/html");
        assertThat(html.getContent()).isEqualTo("<html>Reset link</html>");
    }

    @Test
    @DisplayName("the verification email is multipart/alternative and both templates see the same context values")
    void verificationEmail_isMultipartAndRendersBothFromOneContext() throws Exception {
        UserEmailService service = serviceWithBaseUrl("https://app.photocast.online");
        when(templateEngine.process(eq("verification-email"), any(IContext.class)))
                .thenReturn("<html>Verify</html>");
        when(templateEngine.process(eq("text/verification-email"), any(IContext.class)))
                .thenReturn("Verify as text");

        MimeMultipart multipart = sendAndCaptureMultipart(
                () -> service.sendVerificationEmail("alice@example.com", "alice", "tok"));

        assertThat(multipart.getBodyPart(0).getContentType()).startsWith("text/plain");
        assertThat(multipart.getBodyPart(0).getContent()).isEqualTo("Verify as text");
        assertThat(multipart.getBodyPart(1).getContentType()).startsWith("text/html");
        assertThat(multipart.getBodyPart(1).getContent()).isEqualTo("<html>Verify</html>");

        ArgumentCaptor<IContext> htmlCtx = ArgumentCaptor.forClass(IContext.class);
        ArgumentCaptor<IContext> textCtx = ArgumentCaptor.forClass(IContext.class);
        verify(templateEngine).process(eq("verification-email"), htmlCtx.capture());
        verify(templateEngine).process(eq("text/verification-email"), textCtx.capture());
        assertThat(textCtx.getValue()).isSameAs(htmlCtx.getValue());
        Context context = (Context) textCtx.getValue();
        assertThat(context.getVariable("verificationUrl"))
                .isEqualTo("https://app.photocast.online/verify#token=tok");
        assertThat(context.getVariable("username")).isEqualTo("alice");
        assertThat(context.getVariable("holdDays")).isEqualTo(7);
    }

    @Test
    @DisplayName("sendVerificationEmail renders template and sends HTML email")
    void sendVerificationEmail_validInput_sendsEmail() {
        UserEmailService service = serviceWithBaseUrl("https://app.photocast.online");
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(eq("verification-email"), any(IContext.class)))
                .thenReturn("<html>Verify</html>");
        when(templateEngine.process(eq("text/verification-email"), any(IContext.class)))
                .thenReturn("plain text");

        service.sendVerificationEmail("alice@example.com", "alice", "raw-token-uuid");

        verify(templateEngine).process(eq("verification-email"), any(IContext.class));
        verify(mailSender).send(mimeMessage);
    }

    @Test
    @DisplayName("sendVerificationEmail skips silently when mailSender is null")
    void sendVerificationEmail_nullMailSender_skips() {
        UserEmailService service = new UserEmailService(null, templateEngine, new RegistrationProperties());

        service.sendVerificationEmail("test@example.com", "alice", "raw-token-uuid");

        verify(templateEngine, never()).process(any(String.class), any(IContext.class));
    }

    @Test
    @DisplayName("sendAccountDeletedEmail renders template and sends HTML email")
    void sendAccountDeletedEmail_validInput_sendsEmail() {
        UserEmailService service = new UserEmailService(mailSender, templateEngine, new RegistrationProperties());
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(eq("account-deleted-email"), any(IContext.class)))
                .thenReturn("<html>Deleted</html>");
        when(templateEngine.process(eq("text/account-deleted-email"), any(IContext.class)))
                .thenReturn("plain text");

        service.sendAccountDeletedEmail("alice@example.com", "alice");

        verify(templateEngine).process(eq("account-deleted-email"), any(IContext.class));
        verify(mailSender).send(mimeMessage);
    }

    @Test
    @DisplayName("sendAccountDeletedEmail skips silently when mailSender is null")
    void sendAccountDeletedEmail_nullMailSender_skips() {
        UserEmailService service = new UserEmailService(null, templateEngine, new RegistrationProperties());

        service.sendAccountDeletedEmail("test@example.com", "alice");

        verify(templateEngine, never()).process(any(String.class), any(IContext.class));
    }

    /** Matches the reminder template's context exactly: link, flag, days left and the lapse date. */
    private static IContext reminderContext(String token, boolean finalReminder) {
        return argThat(ctx -> ctx instanceof Context c
                && ("https://app.photocast.online/verify#token=" + token).equals(c.getVariable("verificationUrl"))
                && Boolean.valueOf(finalReminder).equals(c.getVariable("finalReminder"))
                && Integer.valueOf(3).equals(c.getVariable("daysLeft"))
                && "Friday 14 March".equals(c.getVariable("lapsesOn")));
    }

    @Test
    @DisplayName("isConfigured reports whether a mail sender exists")
    void isConfigured_reflectsTheSender() {
        assertThat(new UserEmailService(null, templateEngine, new RegistrationProperties()).isConfigured())
                .isFalse();
        assertThat(new UserEmailService(mailSender, templateEngine, new RegistrationProperties()).isConfigured())
                .isTrue();
    }

    @Test
    @DisplayName("sendVerificationEmail passes the hold in days (168 h -> 7)")
    void sendVerificationEmail_carriesHoldDays() throws Exception {
        UserEmailService service = serviceWithBaseUrl("https://app.photocast.online");

        Context context = renderedContext("verification-email",
                () -> service.sendVerificationEmail("alice@example.com", "alice", "tok"));

        assertThat(context.getVariable("holdDays")).isEqualTo(7);
    }

    @Test
    @DisplayName("the reminder returns false with no sender and for a blank address, rendering nothing")
    void reminder_cannotSend_returnsFalse() {
        LocalDateTime lapses = LocalDateTime.of(2031, 3, 14, 10, 0);

        assertThat(new UserEmailService(null, templateEngine, new RegistrationProperties())
                .sendVerificationReminderEmail("a@example.com", "alice", "tok", false, lapses)).isFalse();
        assertThat(new UserEmailService(mailSender, templateEngine, new RegistrationProperties())
                .sendVerificationReminderEmail(" ", "alice", "tok", false, lapses)).isFalse();

        verify(templateEngine, never()).process(any(String.class), any(IContext.class));
    }

    @Test
    @DisplayName("the reminder returns false when the mailer throws a MailException")
    void reminder_mailException_returnsFalse() {
        UserEmailService service = serviceWithBaseUrl("https://app.photocast.online");
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(eq("verification-reminder-email"), reminderContext("tok", true)))
                .thenReturn("<html></html>");
        when(templateEngine.process(eq("text/verification-reminder-email"), reminderContext("tok", true)))
                .thenReturn("plain text");
        doThrow(new MailSendException("SMTP down")).when(mailSender).send(mimeMessage);

        assertThat(service.sendVerificationReminderEmail("a@example.com", "alice", "tok", true,
                LocalDateTime.of(2031, 3, 14, 10, 0))).isFalse();
    }

    @Test
    @DisplayName("the first reminder carries the fresh URL, the days left and its subject; true on send")
    void firstReminder_carriesTheFreshUrlAndSubject() throws Exception {
        UserEmailService service = serviceWithBaseUrl("https://app.photocast.online");
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(eq("verification-reminder-email"), reminderContext("fresh-tok", false)))
                .thenReturn("<html></html>");
        when(templateEngine.process(eq("text/verification-reminder-email"), reminderContext("fresh-tok", false)))
                .thenReturn("plain text");

        boolean sent = service.sendVerificationReminderEmail("a@example.com", "alice", "fresh-tok", false,
                LocalDateTime.of(2031, 3, 14, 10, 0));

        assertThat(sent).isTrue();
        ArgumentCaptor<IContext> captor = ArgumentCaptor.forClass(IContext.class);
        verify(templateEngine).process(eq("verification-reminder-email"), captor.capture());
        Context context = (Context) captor.getValue();
        assertThat(context.getVariable("verificationUrl"))
                .isEqualTo("https://app.photocast.online/verify#token=fresh-tok");
        assertThat(context.getVariable("finalReminder")).isEqualTo(false);
        assertThat(context.getVariable("daysLeft")).isEqualTo(3);
        assertThat(context.getVariable("lapsesOn")).isEqualTo("Friday 14 March");
        verify(mimeMessage).setSubject("PhotoCast — your place is held for 3 more days", "UTF-8");
        verify(mailSender).send(mimeMessage);
    }

    @Test
    @DisplayName("the final reminder carries the fresh URL and the last-day subject")
    void finalReminder_carriesTheFreshUrlAndSubject() throws Exception {
        UserEmailService service = serviceWithBaseUrl("https://app.photocast.online");
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(eq("verification-reminder-email"), reminderContext("fresh-tok", true)))
                .thenReturn("<html></html>");
        when(templateEngine.process(eq("text/verification-reminder-email"), reminderContext("fresh-tok", true)))
                .thenReturn("plain text");

        boolean sent = service.sendVerificationReminderEmail("a@example.com", "alice", "fresh-tok", true,
                LocalDateTime.of(2031, 3, 14, 10, 0));

        assertThat(sent).isTrue();
        ArgumentCaptor<IContext> captor = ArgumentCaptor.forClass(IContext.class);
        verify(templateEngine).process(eq("verification-reminder-email"), captor.capture());
        Context context = (Context) captor.getValue();
        assertThat(context.getVariable("verificationUrl"))
                .isEqualTo("https://app.photocast.online/verify#token=fresh-tok");
        assertThat(context.getVariable("finalReminder")).isEqualTo(true);
        verify(mimeMessage).setSubject("PhotoCast — last day to claim your place", "UTF-8");
    }
}
