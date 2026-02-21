package com.gregochr.goldenhour.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to the {@code notifications} section of {@code application.yml}.
 *
 * <p>Each notification channel (email, Pushover, macOS toast) can be independently
 * enabled or disabled. Disabled channels are skipped silently.
 */
@Component
@ConfigurationProperties(prefix = "notifications")
@Getter
@Setter
public class NotificationProperties {

    /** Daily digest send schedule. */
    private Schedule schedule = new Schedule();

    /** Email notification channel configuration. */
    private Email email = new Email();

    /** Pushover push notification channel configuration. */
    private Pushover pushover = new Pushover();

    /** macOS toast notification channel configuration. */
    private MacosToast macosToast = new MacosToast();

    /**
     * Cron schedule for the daily notification digest.
     */
    @Getter
    @Setter
    public static class Schedule {

        /** Cron expression for notification send time (default: 07:30 daily). */
        private String cron = "0 30 7 * * *";
    }

    /**
     * Email notification channel.
     */
    @Getter
    @Setter
    public static class Email {

        /** Whether email notifications are enabled. */
        private boolean enabled = false;

        /** Recipient email address. */
        private String recipient;
    }

    /**
     * Pushover iOS push notification channel.
     */
    @Getter
    @Setter
    public static class Pushover {

        /** Whether Pushover notifications are enabled. */
        private boolean enabled = false;

        /** Pushover application token. */
        private String appToken;

        /** Pushover user key. */
        private String userKey;
    }

    /**
     * macOS toast notification channel (via {@code osascript}).
     */
    @Getter
    @Setter
    public static class MacosToast {

        /** Whether macOS toast notifications are enabled. */
        private boolean enabled = false;
    }
}
