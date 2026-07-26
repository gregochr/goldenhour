package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.config.NotificationProperties;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * Shows a macOS toast notification for a forecast evaluation via {@code osascript}.
 *
 * <p>Silently skips when {@code notifications.macosToast.enabled} is {@code false}.
 * Also skips silently if {@code osascript} is unavailable (e.g. when running on Linux/Windows).
 * Formats the title based on which model produced the evaluation: Haiku shows a 1–5 rating;
 * Sonnet shows dual 0–100 scores.
 */
@Service
public class MacOsToastNotificationService implements NotificationChannel {

    private static final Logger LOG = LoggerFactory.getLogger(MacOsToastNotificationService.class);

    /**
     * Absolute path to the interpreter. Naming the bare command would resolve it through
     * {@code PATH}, so a directory ahead of {@code /usr/bin} could supply a different binary.
     */
    private static final String OSASCRIPT = "/usr/bin/osascript";

    /** Control characters (CR/LF included) that would terminate the AppleScript string literal. */
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

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
    @Override
    public void notify(SunsetEvaluation evaluation, String locationName,
            TargetType targetType, LocalDate date) {
        if (!properties.getMacosToast().isEnabled()) {
            return;
        }
        String title = buildTitle(evaluation, locationName, targetType, date);
        String script = String.format(
                "display notification \"%s\" with title \"%s\"",
                escapeForAppleScript(evaluation.summary()), escapeForAppleScript(title));
        try {
            Runtime.getRuntime().exec(new String[]{OSASCRIPT, "-e", script});
        } catch (IOException e) {
            LOG.warn("macOS toast notification failed: {}", e.getMessage());
        }
    }

    /**
     * Renders untrusted text safe to embed in a double-quoted AppleScript string literal.
     *
     * <p>The summary is model-generated, so it can contain anything. A stray quote, backslash or
     * newline would close the literal early and turn the rest of the text into AppleScript the
     * interpreter runs.
     *
     * @param value the raw text, may be null
     * @return the escaped text, empty string when {@code value} is null
     */
    static String escapeForAppleScript(String value) {
        if (value == null) {
            return "";
        }
        // Backslashes first: escaping quotes adds backslashes, so the reverse order would
        // re-escape those and leave the literal unbalanced.
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return CONTROL_CHARS.matcher(escaped).replaceAll(" ");
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
