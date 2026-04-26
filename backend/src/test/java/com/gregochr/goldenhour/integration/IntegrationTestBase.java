package com.gregochr.goldenhour.integration;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Base class for Spring-Boot integration tests that need a real Postgres
 * schema (built by Flyway, matching production) and stubbed Anthropic API
 * endpoints (served by WireMock).
 *
 * <p>Why this exists:
 * <ul>
 *   <li>Default test profile uses H2 in-memory with {@code spring.flyway.enabled=false}
 *       and {@code ddl-auto=create-drop}, so the schema is generated from JPA
 *       entity annotations and the V1–V99 migrations are silently skipped. That
 *       hides any drift between JPA expectations and what Flyway actually
 *       produces in production.</li>
 *   <li>Integration tests using this base run V1–V99 against a Postgres 17
 *       container, which is exactly what production does. Schema bugs that H2
 *       was hiding will surface here.</li>
 *   <li>The WireMock extension stubs Anthropic's endpoints so tests exercise
 *       the full pipeline (request build → submit → poll → result process →
 *       cache write) without burning real API cost.</li>
 * </ul>
 *
 * <p>Activates two profiles via {@link ActiveProfiles}:
 * <ul>
 *   <li>{@code test} — inherits the existing {@code src/test/resources/application.yml}
 *       defaults (notifications disabled, JWT secret, etc.) so this base does
 *       not have to redeclare them.</li>
 *   <li>{@code integration-test} — suppresses {@code DynamicSchedulerBootstrap}
 *       so production cron triggers do not fire during the test, and lets
 *       {@code @DynamicPropertySource} below override H2-specific defaults
 *       (datasource, Flyway, ddl-auto) with Postgres-aware values.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles({"test", "integration-test"})
@Testcontainers
@Import(WireMockAnthropicClientTestConfiguration.class)
public abstract class IntegrationTestBase {

    /**
     * Postgres 17-alpine container — same image as production
     * ({@code docker-compose.yml}). Static field so the container is shared
     * across every test class extending this base, avoiding the ~2 s startup
     * cost per class.
     */
    @Container
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("goldenhour_test")
                    .withUsername("test")
                    .withPassword("test");

    /**
     * WireMock server on a dynamic port. Per-class lifecycle (mappings cleared
     * between tests by the extension's default {@code resetOnEachTest} behaviour
     * — see WireMock JUnit 5 docs).
     */
    @RegisterExtension
    protected static final WireMockExtension WIRE_MOCK = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    /**
     * Wires testcontainer URLs and WireMock port into Spring properties before
     * the application context starts. Also enables Flyway and switches Hibernate
     * to {@code validate} mode so the JPA layer must agree with the migrations
     * — any drift fails the test rather than being silently auto-corrected.
     *
     * @param registry the dynamic property registry supplied by Spring at boot
     */
    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");

        registry.add("photocast.test.anthropic-base-url",
                () -> "http://localhost:" + WIRE_MOCK.getPort());
    }
}
