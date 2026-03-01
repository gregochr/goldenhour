package com.gregochr.goldenhour.service.notification;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.IContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        UserEmailService service = new UserEmailService(null, templateEngine);

        service.sendWelcomeEmail("test@example.com", "alice", "TempPass1!");

        verify(templateEngine, never()).process(any(String.class), any(IContext.class));
    }

    @Test
    @DisplayName("sendWelcomeEmail skips silently when email is blank")
    void sendWelcomeEmail_blankEmail_skips() {
        UserEmailService service = new UserEmailService(mailSender, templateEngine);

        service.sendWelcomeEmail("", "alice", "TempPass1!");

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendWelcomeEmail skips silently when email is null")
    void sendWelcomeEmail_nullEmail_skips() {
        UserEmailService service = new UserEmailService(mailSender, templateEngine);

        service.sendWelcomeEmail(null, "alice", "TempPass1!");

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendWelcomeEmail renders template and sends HTML email")
    void sendWelcomeEmail_validInput_sendsEmail() {
        UserEmailService service = new UserEmailService(mailSender, templateEngine);
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(eq("welcome-email"), any(IContext.class)))
                .thenReturn("<html>Welcome</html>");

        service.sendWelcomeEmail("alice@example.com", "alice", "TempPass1!");

        verify(templateEngine).process(eq("welcome-email"), any(IContext.class));
        verify(mailSender).send(mimeMessage);
    }

    @Test
    @DisplayName("sendPasswordResetEmail renders template and sends HTML email")
    void sendPasswordResetEmail_validInput_sendsEmail() {
        UserEmailService service = new UserEmailService(mailSender, templateEngine);
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(eq("password-reset-email"), any(IContext.class)))
                .thenReturn("<html>Reset</html>");

        service.sendPasswordResetEmail("alice@example.com", "alice", "TempPass1!");

        verify(templateEngine).process(eq("password-reset-email"), any(IContext.class));
        verify(mailSender).send(mimeMessage);
    }

    @Test
    @DisplayName("sendWelcomeEmail catches and logs exceptions without propagating")
    void sendWelcomeEmail_sendFails_doesNotPropagate() {
        UserEmailService service = new UserEmailService(mailSender, templateEngine);
        when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("SMTP down"));

        // Should not throw
        service.sendWelcomeEmail("alice@example.com", "alice", "TempPass1!");
    }

    @Test
    @DisplayName("sendVerificationEmail renders template and sends HTML email")
    void sendVerificationEmail_validInput_sendsEmail() {
        UserEmailService service = new UserEmailService(mailSender, templateEngine);
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(eq("verification-email"), any(IContext.class)))
                .thenReturn("<html>Verify</html>");

        service.sendVerificationEmail("alice@example.com", "alice", "raw-token-uuid");

        verify(templateEngine).process(eq("verification-email"), any(IContext.class));
        verify(mailSender).send(mimeMessage);
    }

    @Test
    @DisplayName("sendVerificationEmail skips silently when mailSender is null")
    void sendVerificationEmail_nullMailSender_skips() {
        UserEmailService service = new UserEmailService(null, templateEngine);

        service.sendVerificationEmail("test@example.com", "alice", "raw-token-uuid");

        verify(templateEngine, never()).process(any(String.class), any(IContext.class));
    }
}
