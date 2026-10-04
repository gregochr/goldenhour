package com.gregochr.goldenhour.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to {@code photocast.batch.cache-primer} of {@code application.yml}.
 *
 * <p>Out-of-range values fail startup (the setters throw). Controls the batch cache primer: a
 * one-request batch submitted per distinct sky-prompt cache prefix before a scheduled cycle
 * submits its real buckets, so the real requests READ the shared system-prompt cache instead of
 * each writing it (see {@code BatchCachePrimer}). When
 * {@link #enabled} is {@code false} the primer does not run and every request is byte-identical to
 * what was sent before the primer existed.
 */
@Component
@ConfigurationProperties(prefix = "photocast.batch.cache-primer")
@Getter
public class BatchCachePrimerProperties {

    /** Whether the cycle primes the cache (and warmed prefixes' requests carry the 1-hour lifetime). */
    @Setter
    private boolean enabled = true;

    /** The most the cycle waits for all primers together, in seconds. 0 means do not prime at all. */
    private int waitSeconds = 180;

    /** How often the primers' state is polled while waiting, in seconds. */
    private int pollSeconds = 10;

    /** Longest permitted {@code wait-seconds}. */
    public static final int MAX_WAIT_SECONDS = 600;

    /** Longest permitted {@code poll-seconds}. */
    public static final int MAX_POLL_SECONDS = 60;

    /**
     * Sets the wait cap.
     *
     * @param waitSeconds seconds, 0 to 600
     * @throws IllegalArgumentException if out of range, so a bad value fails startup
     */
    public void setWaitSeconds(int waitSeconds) {
        if (waitSeconds < 0 || waitSeconds > MAX_WAIT_SECONDS) {
            throw new IllegalArgumentException("photocast.batch.cache-primer.wait-seconds must be 0.."
                    + MAX_WAIT_SECONDS + " but was " + waitSeconds);
        }
        this.waitSeconds = waitSeconds;
    }

    /**
     * Sets the poll interval.
     *
     * @param pollSeconds seconds, 1 to 60
     * @throws IllegalArgumentException if out of range, so a bad value fails startup
     */
    public void setPollSeconds(int pollSeconds) {
        if (pollSeconds < 1 || pollSeconds > MAX_POLL_SECONDS) {
            throw new IllegalArgumentException("photocast.batch.cache-primer.poll-seconds must be 1.."
                    + MAX_POLL_SECONDS + " but was " + pollSeconds);
        }
        this.pollSeconds = pollSeconds;
    }
}
