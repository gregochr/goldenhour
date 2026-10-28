package com.gregochr.goldenhour.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the actuator health-detail exposure as the COMMITTED profile files declare it: details are shown
 * only to an ADMIN, never to every signed-in user and never to anonymous callers.
 */
class HealthDetailsProdYamlTest {

    private static PropertySource<?> load(String yaml) throws IOException {
        return new YamlPropertySourceLoader().load(yaml, new ClassPathResource(yaml)).get(0);
    }

    @Test
    @DisplayName("production shows health details only to authorised ADMIN users")
    void prodDetailsAreAdminOnly() throws IOException {
        PropertySource<?> prod = load("application-prod.yml");

        assertThat(prod.getProperty("management.endpoint.health.show-details")).isEqualTo("when_authorized");
        assertThat(prod.getProperty("management.endpoint.health.roles")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("example profile also restricts health details to ADMIN")
    void exampleDetailsAreAdminOnly() throws IOException {
        PropertySource<?> example = load("application-example.yml");

        assertThat(example.getProperty("management.endpoint.health.show-details")).isNotEqualTo("always");
        assertThat(example.getProperty("management.endpoint.health.roles")).isEqualTo("ADMIN");
    }
}
