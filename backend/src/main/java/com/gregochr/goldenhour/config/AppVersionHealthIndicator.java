package com.gregochr.goldenhour.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator that exposes the application version as a health detail.
 *
 * <p>Reads the {@code APP_VERSION} environment variable (default {@code "dev"}) and
 * publishes it under the {@code version} detail key. Always returns {@code UP} —
 * this indicator is purely informational and does not reflect a service liveness check.
 */
@Component
public class AppVersionHealthIndicator implements HealthIndicator {

    private final String appVersion;

    /**
     * Constructs the indicator.
     *
     * @param appVersion the application version string, injected from {@code APP_VERSION}
     *                   environment variable (defaults to {@code "dev"})
     */
    public AppVersionHealthIndicator(
            @Value("${APP_VERSION:dev}") String appVersion) {
        this.appVersion = appVersion;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("version", appVersion)
                .build();
    }
}
