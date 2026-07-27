package com.gregochr.goldenhour.integration;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
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
 *
 * <p><strong>Why {@link DirtiesContext} is here, and why removing it breaks CI.</strong>
 * {@code @Testcontainers} plus a {@code static} {@code @Container} field means JUnit's
 * {@code TestcontainersExtension} owns the container's lifecycle: it stops the container
 * in {@code afterAll} of <em>every</em> test class and restarts it in the next class's
 * {@code beforeAll} — on a <b>new random host port</b>. Spring's context cache does not
 * restart with it. {@code @DynamicPropertySource} is evaluated only when a context is
 * <em>created</em>, so without this annotation a later class sharing the same context
 * cache key is handed the cached context, still holding the previous port in
 * {@code spring.datasource.url}, and every query fails with
 * {@code Connection to localhost:NNNNN refused} after a 30 s Hikari timeout.
 *
 * <p>That was a real CI failure (2026-07-27, tag {@code v2.16.9}). It presented as
 * flakiness because it only fires when two classes sharing a context cache key land in
 * the same surefire fork, and {@code <forkCount>1C</forkCount>} makes that a matter of
 * luck. Evicting the context after each class guarantees the next one re-reads the
 * restarted container's port.
 *
 * <p>The obvious-looking alternative — the Testcontainers singleton pattern, dropping
 * {@code @Testcontainers}/{@code @Container} and starting the container once from a
 * static initialiser — was tried and <b>rejected on evidence</b>. The per-class restart
 * is load-bearing: it is what hands each class a virgin, freshly-migrated database.
 * Several subclasses call {@code regionRepository.deleteAll()} in {@code @AfterEach},
 * which deletes the rows <em>Flyway seeded</em> (V31's five regions) and not merely the
 * rows they created. With one long-lived container those deletions leak across classes
 * and {@code HttpIntegrationTestBaseProbeTest} fails on an empty {@code regions} table.
 * Making the singleton work would mean first teaching every subclass not to delete seed
 * data — a larger change than the bug warrants, and not one to make while CI is red.
 */
@SpringBootTest
@ActiveProfiles({"test", "integration-test"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(WireMockAnthropicClientTestConfiguration.class)
public abstract class IntegrationTestBase {

    /**
     * Postgres 17-alpine container — same image as production
     * ({@code docker-compose.yml}). Static field, but note that this does
     * <em>not</em> mean one container per fork: the extension stops and restarts it
     * around every test class, which is what gives each class a clean database and
     * why {@link DirtiesContext} above is mandatory. See the class Javadoc.
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
