package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.service.VerificationTokenIssuer;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to the {@code photocast.registration} section of {@code application.yml}.
 *
 * <p>Controls the early-access registration cap, default role for new signups,
 * whether self-registration is enabled at all, and how long a never-verified sign-up holds its place
 * (the hold window and its two reminders, swept by {@code PendingRegistrationSweepJob}).
 *
 * <p>The hold-window values are validated at startup ({@link #validate()}): a job that deletes data never
 * falls back silently from a malformed setting.
 */
@Component
@ConfigurationProperties(prefix = "photocast.registration")
@Getter
@Setter
public class RegistrationProperties {

    /** Hours in a day: every hold-window value is a whole number of days, because the email copy says days. */
    public static final int HOURS_PER_DAY = 24;

    /** Maximum number of non-admin users allowed to register. Defaults to 10. */
    private int maxUsers = 10;

    /** Role assigned to new self-registered users. Defaults to {@code LITE_USER}. */
    private String defaultRole = "LITE_USER";

    /** Whether self-registration is open. Defaults to {@code true}. */
    private boolean enabled = true;

    /** How long a never-verified sign-up holds its place, in hours. Defaults to 168 (7 days). */
    private int pendingHoldHours = 168;

    /** The first reminder goes when this many hours of the hold are left. Defaults to 72 (3 days). */
    private int firstReminderHoursLeft = 72;

    /** The final reminder goes when this many hours of the hold are left. Defaults to 24 (1 day). */
    private int finalReminderHoursLeft = 24;

    /**
     * Refuses a hold window the sweep cannot honour, at startup.
     *
     * <p>Every value must be a positive multiple of 24; the final reminder must be at least one verification
     * link's life ({@link VerificationTokenIssuer#TOKEN_EXPIRY_HOURS}) before the hold ends; the first and the
     * final must be at least one link's life apart (so the sweep's own first-reminder link has expired before
     * the final is due and never defers it); and the first must fall inside the hold.
     *
     * @throws IllegalStateException naming the first rule broken
     */
    @PostConstruct
    public void validate() {
        requireWholeDays("pending-hold-hours", pendingHoldHours);
        requireWholeDays("first-reminder-hours-left", firstReminderHoursLeft);
        requireWholeDays("final-reminder-hours-left", finalReminderHoursLeft);
        int linkLife = VerificationTokenIssuer.TOKEN_EXPIRY_HOURS;
        if (finalReminderHoursLeft < linkLife) {
            throw new IllegalStateException("photocast.registration.final-reminder-hours-left="
                    + finalReminderHoursLeft + " is shorter than a verification link's life (" + linkLife
                    + " hours)");
        }
        if (firstReminderHoursLeft - finalReminderHoursLeft < linkLife) {
            throw new IllegalStateException("photocast.registration.first-reminder-hours-left="
                    + firstReminderHoursLeft + " must be at least " + linkLife
                    + " hours more than final-reminder-hours-left=" + finalReminderHoursLeft);
        }
        if (firstReminderHoursLeft >= pendingHoldHours) {
            throw new IllegalStateException("photocast.registration.first-reminder-hours-left="
                    + firstReminderHoursLeft + " must be less than pending-hold-hours=" + pendingHoldHours);
        }
    }

    private static void requireWholeDays(String key, int hours) {
        if (hours <= 0 || hours % HOURS_PER_DAY != 0) {
            throw new IllegalStateException("photocast.registration." + key + "=" + hours
                    + " must be a positive multiple of " + HOURS_PER_DAY + " hours");
        }
    }
}
