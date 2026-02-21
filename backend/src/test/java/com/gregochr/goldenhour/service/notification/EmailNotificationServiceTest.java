package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.config.NotificationProperties;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link EmailNotificationService}.
 */
@ExtendWith(MockitoExtension.class)
class EmailNotificationServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private NotificationProperties properties;

    @InjectMocks
    private EmailNotificationService emailService;

    @Test
    @DisplayName("notify() does nothing when email notifications are disabled")
    void notify_whenDisabled_sendsNoEmail() {
        NotificationProperties.Email email = new NotificationProperties.Email();
        email.setEnabled(false);
        org.mockito.Mockito.when(properties.getEmail()).thenReturn(email);

        emailService.notify(new SunsetEvaluation(3, "Moderate."),
                "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 2, 20));

        verify(mailSender, never()).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("notify() sends email when notifications are enabled")
    void notify_whenEnabled_sendsEmail() {
        NotificationProperties.Email email = new NotificationProperties.Email();
        email.setEnabled(true);
        email.setRecipient("test@example.com");
        org.mockito.Mockito.when(properties.getEmail()).thenReturn(email);

        SunsetEvaluation evaluation = new SunsetEvaluation(4, "Good conditions.");
        emailService.notify(evaluation, "Durham UK", TargetType.SUNSET,
                LocalDate.of(2026, 2, 20));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();

        assertThat(sent.getTo()).contains("test@example.com");
        assertThat(sent.getSubject()).contains("Durham UK");
        assertThat(sent.getSubject()).contains("4/5");
        assertThat(sent.getText()).contains("Good conditions.");
    }
}
