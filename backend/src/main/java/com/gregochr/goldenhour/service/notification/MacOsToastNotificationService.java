package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.config.NotificationProperties;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;

/**
 * Shows a macOS toast notification for a forecast evaluation via {@code osascript}.
 *
 * <p>Silently skips when {@code notifications.macosToast.enabled} is {@code false}.
 * Also skips silently if {@code osascript} is unavailable (e.g. when running on Linux/Windows).
 * Formats the title based on which model produced the evaluation: Haiku shows a 1–5 rating;
 * Sonnet shows dual 0–100 scores.
 */
@Service
public class MacOsToastNotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(MacOsToastNotificationService.class);

    private final NotificationProperties properties;

    /**
     * Constructs a {@code MacOsToastNotificationService}.
     *
     * @param properties notification configuration
     */
    public MacOsToastNotificationService(NotificationProperties properties) {
        this.properties = properties;
    }

    /**
     * Shows a macOS toast notification for a forecast evaluation, if toast notifications
     * are enabled.
     *
     * @param evaluation   the colour potential evaluation to report
     * @param locationName name of the location
     * @param targetType   SUNRISE or SUNSET
     * @param date         date of the solar event
     */
    public void notify(SunsetEvaluation evaluation, String locationName,
            TargetType targetType, LocalDate date) {
        if (!properties.getMacosToast().isEnabled()) {
            return;
        }
        String title = buildTitle(evaluation, locationName, targetType, date);
        String script = String.format(
                "display notification \"%s\" with title \"%s\"",
                evaluation.summary().replace("\"", "\\\""), title.replace("\"", "\\\""));
        try {
            Runtime.getRuntime().exec(new String[]{"osascript", "-e", script});
        } catch (IOException e) {
            LOG.warn("macOS toast notification failed: {}", e.getMessage());
        }
    }

    private String buildTitle(SunsetEvaluation evaluation, String locationName,
            TargetType targetType, LocalDate date) {
        if (evaluation.rating() != null) {
            return String.format("Golden Hour \u2014 %s %s %s (rating %d/5)",
                    locationName, targetType.name().toLowerCase(), date, evaluation.rating());
        }
        return String.format("Golden Hour \u2014 %s %s %s (fiery %d/100, golden %d/100)",
                locationName, targetType.name().toLowerCase(), date,
                evaluation.fierySkyPotential(), evaluation.goldenHourPotential());
    }
}
