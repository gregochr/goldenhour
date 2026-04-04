package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.config.NotificationProperties;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link EmailNotificationService}.
 */
@ExtendWith(MockitoExtension.class)
class EmailNotificationServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Test
    @DisplayName("notify() does nothing when email notifications are disabled")
    void notify_whenDisabled_sendsNoEmail() {
        NotificationProperties properties = new NotificationProperties();
        properties.getEmail().setEnabled(false);
        EmailNotificationService emailService =
                new EmailNotificationService(properties, mailSender);

        emailService.notify(new SunsetEvaluation(null, 30, 40, "Moderate."),
                "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 2, 20));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("notify() sends Sonnet email with dual scores when notifications are enabled")
    void notify_sonnetEvaluation_sendsEmailWithDualScores() {
        NotificationProperties properties = new NotificationProperties();
        properties.getEmail().setEnabled(true);
        properties.getEmail().setRecipient("test@example.com");
        EmailNotificationService emailService =
                new EmailNotificationService(properties, mailSender);

        SunsetEvaluation evaluation = new SunsetEvaluation(null, 72, 80, "Good conditions.");
        emailService.notify(evaluation, "Durham UK", TargetType.SUNSET,
                LocalDate.of(2026, 2, 20));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();

        assertThat(sent.getTo()).contains("test@example.com");
        assertThat(sent.getSubject()).contains("Durham UK");
        assertThat(sent.getSubject()).contains("72/100");
        assertThat(sent.getText()).contains("Good conditions.");
    }

    @Test
    @DisplayName("notify() sends Haiku email with rating when evaluation has a rating")
    void notify_haikuEvaluation_sendsEmailWithRating() {
        NotificationProperties properties = new NotificationProperties();
        properties.getEmail().setEnabled(true);
        properties.getEmail().setRecipient("test@example.com");
        EmailNotificationService emailService =
                new EmailNotificationService(properties, mailSender);

        SunsetEvaluation evaluation = new SunsetEvaluation(4, null, null, "Good conditions.");
        emailService.notify(evaluation, "Durham UK", TargetType.SUNSET,
                LocalDate.of(2026, 2, 20));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();

        assertThat(sent.getSubject()).contains("rating 4/5");
        assertThat(sent.getText()).contains("4 / 5");
        assertThat(sent.getText()).contains("Good conditions.");
    }

    @Test
    @DisplayName("notify() skips when mailSender is null (no SMTP configured)")
    void notify_nullMailSender_sendsNoEmail() {
        NotificationProperties properties = new NotificationProperties();
        properties.getEmail().setEnabled(true);
        EmailNotificationService emailService =
                new EmailNotificationService(properties, null);

        emailService.notify(new SunsetEvaluation(null, 30, 40, "Moderate."),
                "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 2, 20));

        // No exception — silently skipped
    }

    @Test
    @DisplayName("notify() sets from address to noreply@photocast.online")
    void notify_setsFromAddress() {
        NotificationProperties properties = new NotificationProperties();
        properties.getEmail().setEnabled(true);
        properties.getEmail().setRecipient("test@example.com");
        EmailNotificationService emailService =
                new EmailNotificationService(properties, mailSender);

        emailService.notify(new SunsetEvaluation(3, null, null, "OK."),
                "Bamburgh", TargetType.SUNSET, LocalDate.of(2026, 3, 1));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getFrom()).isEqualTo("noreply@photocast.online");
    }

    @Test
    @DisplayName("notify() subject includes lowercase target type")
    void notify_subjectIncludesTargetType() {
        NotificationProperties properties = new NotificationProperties();
        properties.getEmail().setEnabled(true);
        properties.getEmail().setRecipient("test@example.com");
        EmailNotificationService emailService =
                new EmailNotificationService(properties, mailSender);

        emailService.notify(new SunsetEvaluation(null, 60, 55, "Decent."),
                "Bamburgh", TargetType.SUNRISE, LocalDate.of(2026, 3, 1));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).contains("sunrise");
        assertThat(captor.getValue().getSubject()).doesNotContain("SUNRISE");
    }

    @Test
    @DisplayName("notify() body includes date and location name")
    void notify_bodyIncludesDateAndLocation() {
        NotificationProperties properties = new NotificationProperties();
        properties.getEmail().setEnabled(true);
        properties.getEmail().setRecipient("test@example.com");
        EmailNotificationService emailService =
                new EmailNotificationService(properties, mailSender);

        emailService.notify(new SunsetEvaluation(null, 72, 80, "Great."),
                "Dunstanburgh", TargetType.SUNSET, LocalDate.of(2026, 4, 15));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getText()).contains("Dunstanburgh");
        assertThat(captor.getValue().getText()).contains("2026-04-15");
    }
}
