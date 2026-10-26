package com.gregochr.goldenhour.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to the {@code photocast.sub-regions} section of {@code application.yml} (sub-regions-plan D5, D9).
 *
 * <p>The Lite pick <em>limit</em> is deliberately not here: it is an admin setting in the database
 * ({@code sub_region_policy}). These are the deploy-time knobs: whether enforcement is on, and the two
 * lengths of the change rule. Validated at startup ({@link #validate()}) so a malformed value never falls
 * back silently.
 */
@Component
@ConfigurationProperties(prefix = "photocast.sub-regions")
@Getter
@Setter
public class SubRegionProperties {

    /** The longest grace or lock period accepted, in days. */
    public static final int MAX_DAYS = 365;

    /** Whether a Lite reader's ratings are limited to their picks. Defaults to {@code false} (D9). */
    private boolean enforce = false;

    /** Days of free changes from a reader's first choice, the first day included. Defaults to 7. */
    private int graceDays = 7;

    /** Days a reader must wait between changes that swap a pick out, once grace is over. Defaults to 30. */
    private int lockDays = 30;

    /**
     * Refuses a grace or lock length outside 1..{@value #MAX_DAYS}, at startup.
     *
     * @throws IllegalStateException naming the first value out of range
     */
    @PostConstruct
    public void validate() {
        requireInRange("grace-days", graceDays);
        requireInRange("lock-days", lockDays);
    }

    private static void requireInRange(String key, int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalStateException("photocast.sub-regions." + key + "=" + days
                    + " must be between 1 and " + MAX_DAYS);
        }
    }
}
