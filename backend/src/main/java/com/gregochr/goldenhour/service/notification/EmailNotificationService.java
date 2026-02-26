package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.config.NotificationProperties;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Sends a forecast evaluation by email.
 *
 * <p>Silently skips sending when {@code notifications.email.enabled} is {@code false}.
 * Formats the score section based on which model produced the evaluation: Haiku evaluations
 * show a 1–5 rating; Sonnet evaluations show dual 0–100 scores.
 */
@Service
public class EmailNotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(EmailNotificationService.class);

    private final NotificationProperties properties;
    private final JavaMailSender mailSender;

    /**
     * Constructs an {@code EmailNotificationService}.
     *
     * @param properties notification configuration
     * @param mailSender Spring mail sender (optional, required=false)
     */
    public EmailNotificationService(
            NotificationProperties properties,
            @Autowired(required = false) JavaMailSender mailSender) {
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
        boolean emailDisabled = !properties.getEmail().isEnabled();
        if (emailDisabled || mailSender == null) {
            LOG.debug(
                    "Email notifications disabled or mail sender not configured "
                            + "— skipping {} {} {}",
                    locationName, targetType, date);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(properties.getEmail().getRecipient());
        message.setSubject(buildSubject(locationName, targetType, date, evaluation));
        message.setText(buildBody(evaluation, locationName, targetType, date));
        mailSender.send(message);
        if (evaluation.rating() != null) {
            LOG.info("Email sent to {} — {} {} {} rating={}/5",
                    properties.getEmail().getRecipient(), locationName, targetType, date,
                    evaluation.rating());
        } else {
            LOG.info("Email sent to {} — {} {} {} fiery={}/100 golden={}/100",
                    properties.getEmail().getRecipient(), locationName, targetType, date,
                    evaluation.fierySkyPotential(), evaluation.goldenHourPotential());
        }
    }

    private String buildSubject(String locationName, TargetType targetType, LocalDate date,
            SunsetEvaluation evaluation) {
        if (evaluation.rating() != null) {
            return String.format("Golden Hour \u2014 %s %s on %s (rating %d/5)",
                    locationName, targetType.name().toLowerCase(), date, evaluation.rating());
        }
        return String.format("Golden Hour \u2014 %s %s on %s (fiery %d/100, golden %d/100)",
                locationName, targetType.name().toLowerCase(), date,
                evaluation.fierySkyPotential(), evaluation.goldenHourPotential());
    }

    private String buildBody(SunsetEvaluation evaluation, String locationName,
            TargetType targetType, LocalDate date) {
        if (evaluation.rating() != null) {
            return String.format(
                    "Colour potential forecast for %s %s on %s%n%n"
                    + "Rating: %d / 5%n%n%s",
                    locationName, targetType.name().toLowerCase(), date,
                    evaluation.rating(), evaluation.summary());
        }
        return String.format(
                "Colour potential forecast for %s %s on %s%n%n"
                + "Fiery Sky: %d / 100%nGolden Hour: %d / 100%n%n%s",
                locationName, targetType.name().toLowerCase(), date,
                evaluation.fierySkyPotential(), evaluation.goldenHourPotential(),
                evaluation.summary());
    }
}
