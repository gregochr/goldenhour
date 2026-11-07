package com.gregochr.goldenhour.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Readiness for the E2E smoke suite's seeded fixture: {@code DOWN} until {@link E2eLocalFixtureSeeder}
 * has written the fixture and its self-check has passed.
 *
 * <p>Tomcat accepts requests before {@code ApplicationReadyEvent} listeners have finished, so
 * {@code /actuator/health/container} can report {@code UP} while the fixture is still being written. The
 * smoke script adds this indicator (contributor name {@code e2eFixture}) to the {@code container} health
 * group, and only there, so Playwright's {@code webServer} URL probe waits for the seed. The production
 * group is untouched: this bean exists only under {@code local & !prod} with
 * {@code photocast.e2e.seed-fixture=true}.
 *
 * <p>The bean name is exactly {@code e2eFixtureHealthIndicator}: Spring Boot derives the contributor name
 * {@code e2eFixture} by stripping the {@code HealthIndicator} suffix.
 */
@Component("e2eFixtureHealthIndicator")
@Profile("local & !prod")
@ConditionalOnProperty(name = "photocast.e2e.seed-fixture", havingValue = "true")
public class E2eFixtureHealthIndicator implements HealthIndicator {

    private volatile boolean ready;
    private volatile String failure;

    /** Marks the fixture seeded and self-checked: the indicator goes {@code UP}. */
    void markReady() {
        this.failure = null;
        this.ready = true;
    }

    /**
     * Records that seeding failed: the indicator stays {@code DOWN}, with the reason as a detail.
     *
     * @param reason why the seed did not complete
     */
    void markFailed(String reason) {
        this.ready = false;
        this.failure = reason;
    }

    @Override
    public Health health() {
        if (ready) {
            return Health.up().build();
        }
        if (failure != null) {
            return Health.down().withDetail("reason", failure).build();
        }
        return Health.down().withDetail("reason", "fixture not seeded yet").build();
    }
}
