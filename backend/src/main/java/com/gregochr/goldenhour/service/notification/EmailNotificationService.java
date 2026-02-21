package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.config.NotificationProperties;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Sends a forecast evaluation by email.
 *
 * <p>Silently skips sending when {@code notifications.email.enabled} is {@code false}.
 */
@Service
public class EmailNotificationService {

    private final NotificationProperties properties;
    private final JavaMailSender mailSender;

    /**
     * Constructs an {@code EmailNotificationService}.
     *
     * @param properties notification configuration
     * @param mailSender Spring mail sender
     */
    public EmailNotificationService(NotificationProperties properties, JavaMailSender mailSender) {
        this.properties = properties;
        this.mailSender = mailSender;
    }

    /**
     * Sends an email notification for a forecast evaluation, if email notifications are enabled.
     *
     * @param evaluation   the colour potential evaluation to report
     * @param locationName name of the location
     * @param targetType   SUNRISE or SUNSET
     * @param date         date of the solar event
     */
    public void notify(SunsetEvaluation evaluation, String locationName,
            TargetType targetType, LocalDate date) {
        if (!properties.getEmail().isEnabled()) {
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(properties.getEmail().getRecipient());
        message.setSubject(buildSubject(locationName, targetType, date, evaluation.rating()));
        message.setText(buildBody(evaluation, locationName, targetType, date));
        mailSender.send(message);
    }

    private String buildSubject(String locationName, TargetType targetType, LocalDate date,
            int rating) {
        return String.format("Golden Hour \u2014 %s %s on %s (rating %d/5)",
                locationName, targetType.name().toLowerCase(), date, rating);
    }

    private String buildBody(SunsetEvaluation evaluation, String locationName,
            TargetType targetType, LocalDate date) {
        return String.format(
                "Colour potential forecast for %s %s on %s%n%nRating: %d / 5%n%n%s",
                locationName, targetType.name().toLowerCase(), date,
                evaluation.rating(), evaluation.summary());
    }
}
