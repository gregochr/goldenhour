package com.gregochr.goldenhour.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code container} health group the Docker healthcheck curls: built from the COMMITTED
 * production and example profile files, it follows the process and its database only. An external service
 * outage (Open-Meteo's daily quota, a Claude or WorldTides failure) must never turn the container
 * unhealthy, while a dead database must.
 */
class ContainerHealthGroupTest {

    private static final String GROUP = "container";

    private static WebApplicationContextRunner runnerFor(String profileFile, HealthIndicator db,
            HealthIndicator external) {
        return new WebApplicationContextRunner()
                .withInitializer(ctx -> {
                    try {
                        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                                .load(profileFile, new ClassPathResource(profileFile));
                        sources.forEach(s -> ctx.getEnvironment().getPropertySources().addFirst(s));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withConfiguration(AutoConfigurations.of(
                        EndpointAutoConfiguration.class, WebEndpointAutoConfiguration.class,
                        HealthContributorAutoConfiguration.class, HealthContributorRegistryAutoConfiguration.class,
                        HealthEndpointAutoConfiguration.class))
                .withBean("dbHealthIndicator", HealthIndicator.class, () -> db)
                .withBean("openMeteoHealthIndicator", HealthIndicator.class, () -> external)
                .withBean("claudeApiHealthIndicator", HealthIndicator.class, () -> external)
                .withBean("tideCheckHealthIndicator", HealthIndicator.class, () -> external)
                .withPropertyValues("management.endpoints.web.exposure.include=health");
    }

    /** What a probe sees: the HTTP status the group maps to, its status code and the serialised body. */
    private record Probe(int httpStatus, String code, String body) {
    }

    private static Probe probe(ApplicationContext context, String... path) {
        HealthDescriptor descriptor = path.length == 0 ? context.getBean(HealthEndpoint.class).health()
                : context.getBean(HealthEndpoint.class).healthForPath(path);
        HealthEndpointGroups groups = context.getBean(HealthEndpointGroups.class);
        HealthEndpointGroup group = path.length == 0 ? groups.getPrimary() : groups.get(path[0]);
        int http = group.getHttpCodeStatusMapper().getStatusCode(descriptor.getStatus());
        return new Probe(http, descriptor.getStatus().getCode(), descriptor.toString());
    }

    private static HealthIndicator up() {
        return () -> Health.up().build();
    }

    private static HealthIndicator down() {
        return () -> Health.down().withDetail("error", "HttpClientErrorException").build();
    }

    @Test
    @DisplayName("prod: the container group is 200 while Open-Meteo, Claude and tides are all DOWN")
    void prod_externalIndicatorsDown_groupStaysUp() {
        runnerFor("application-prod.yml", up(), down()).run(context -> {
            assertThat(context).hasNotFailed();

            // The aggregate the old healthcheck curled is what broke: 503 on an external outage.
            assertThat(probe(context).httpStatus()).isEqualTo(503);

            Probe response = probe(context, GROUP);
            assertThat(response.httpStatus()).isEqualTo(200);
            assertThat(response.code()).isEqualTo("UP");
        });
    }

    @Test
    @DisplayName("prod: the container group is 503 when the database indicator is DOWN")
    void prod_dbDown_groupIsDown() {
        runnerFor("application-prod.yml", down(), up()).run(context -> {
            Probe response = probe(context, GROUP);
            assertThat(response.httpStatus()).isEqualTo(503);
            assertThat(response.code()).isEqualTo("DOWN");
        });
    }

    @Test
    @DisplayName("prod: the group shows status only, naming no component to an anonymous caller")
    void prod_groupLeaksNoComponents() {
        runnerFor("application-prod.yml", up(), down()).run(context -> {
            String body = probe(context, GROUP).body();
            assertThat(body).doesNotContain("openMeteo", "claudeApi", "tideCheck", "error");
        });
    }

    @Test
    @DisplayName("prod, example and local profiles declare exactly db and ping as the group's members")
    void membersAreDbAndPingOnly() throws IOException {
        for (String file : List.of("application-prod.yml", "application-example.yml",
                "application-local.yml")) {
            PropertySource<?> source = new YamlPropertySourceLoader()
                    .load(file, new ClassPathResource(file)).get(0);
            assertThat(source.getProperty("management.endpoint.health.group.container.include"))
                    .as(file).isEqualTo("db,ping");
        }
    }

    @Test
    @DisplayName("example profile: the group is likewise unaffected by an external outage")
    void example_externalIndicatorsDown_groupStaysUp() {
        runnerFor("application-example.yml", up(), down()).run(context ->
                assertThat(probe(context, GROUP).httpStatus()).isEqualTo(200));
    }
}
