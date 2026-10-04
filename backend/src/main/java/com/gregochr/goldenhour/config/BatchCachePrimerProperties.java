package com.gregochr.goldenhour.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to {@code photocast.batch.cache-primer} of {@code application.yml}.
 *
 * <p>Controls the batch cache primer: a one-request batch submitted per distinct sky-prompt cache
 * prefix before a scheduled cycle submits its real buckets, so the real requests READ the shared
 * system-prompt cache instead of each writing it (see {@code BatchCachePrimer}). When
 * {@link #enabled} is {@code false} the primer does not run AND batch sky requests keep the default
 * five-minute cache lifetime, byte for byte as before the primer existed.
 */
@Component
@ConfigurationProperties(prefix = "photocast.batch.cache-primer")
@Getter
@Setter
public class BatchCachePrimerProperties {

    /** Whether the primer runs and batch sky requests carry the one-hour cache lifetime. */
    private boolean enabled = true;

    /** The most the cycle waits for all primers together, in seconds. */
    private int waitSeconds = 300;

    /** How often the primers' state is polled while waiting, in seconds. */
    private int pollSeconds = 10;

    /**
     * Builds an instance with the primer switched off.
     *
     * @return properties with {@link #enabled} {@code false}
     */
    public static BatchCachePrimerProperties disabled() {
        BatchCachePrimerProperties p = new BatchCachePrimerProperties();
        p.setEnabled(false);
        return p;
    }
}
