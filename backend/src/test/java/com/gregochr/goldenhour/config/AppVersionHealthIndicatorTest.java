package com.gregochr.goldenhour.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AppVersionHealthIndicator}.
 */
class AppVersionHealthIndicatorTest {

    @Test
    @DisplayName("health() returns UP status")
    void healthReturnsUp() {
        AppVersionHealthIndicator indicator = new AppVersionHealthIndicator("v2.3.1");
        Health health = indicator.health();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
    }

    @Test
    @DisplayName("health() includes the injected version string as 'version' detail")
    void healthIncludesVersionDetail() {
        AppVersionHealthIndicator indicator = new AppVersionHealthIndicator("v2.3.1");
        Health health = indicator.health();
        assertThat(health.getDetails().get("version")).isEqualTo("v2.3.1");
    }

    @Test
    @DisplayName("health() returns 'dev' as the version detail when passed 'dev'")
    void healthReturnsDev() {
        AppVersionHealthIndicator indicator = new AppVersionHealthIndicator("dev");
        Health health = indicator.health();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails().get("version")).isEqualTo("dev");
    }
}
