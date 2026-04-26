package com.gregochr.goldenhour.integration;

import com.anthropic.client.AnthropicClient;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerBootstrap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test that proves {@link IntegrationTestBase}'s infrastructure works
 * before any pipeline test relies on it.
 *
 * <p>Asserts four invariants:
 * <ol>
 *   <li>The Spring context boots against the Postgres testcontainer.</li>
 *   <li>Flyway ran V1–V99 — verified by the seed rows in
 *       {@code scheduler_job_config} (added in V68).</li>
 *   <li>{@link DynamicSchedulerBootstrap} is absent from the context — the
 *       {@code integration-test} profile suppressed it, so no production
 *       cron jobs will fire during tests.</li>
 *   <li>The Anthropic client bean was replaced with the WireMock-routed one
 *       from {@link WireMockAnthropicClientTestConfiguration}.</li>
 * </ol>
 *
 * <p>If any of these fail, every downstream integration test would fail in
 * confusing ways. Catching it here gives a single, clear failure point.
 */
class IntegrationTestBaseSmokeTest extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SchedulerJobConfigRepository schedulerJobConfigRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AnthropicClient anthropicClient;

    @Test
    @DisplayName("Postgres testcontainer is reachable and reports its product name")
    void postgresContainer_isReachable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String productName = jdbc.queryForObject(
                "SELECT current_setting('server_version_num')", String.class);
        assertThat(productName)
                .as("Postgres should be reachable and on a v17.x server")
                .startsWith("17");
    }

    @Test
    @DisplayName("Flyway ran V1-V99 — scheduler_job_config has the V68 seed rows")
    void flywayMigrations_seededSchedulerJobConfig() {
        long count = schedulerJobConfigRepository.count();
        assertThat(count)
                .as("V68 seeds 5 scheduler job configs (tide, briefing, aurora, "
                        + "met office, cleanup) — if this is 0, Flyway did not run")
                .isEqualTo(5L);
    }

    @Test
    @DisplayName("flyway_schema_history records all migrations through V99")
    void flywayHistory_recordsLatestMigration() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer maxVersion = jdbc.queryForObject(
                "SELECT MAX(CAST(SUBSTRING(version, '^[0-9]+') AS INTEGER)) "
                        + "FROM flyway_schema_history WHERE success = true",
                Integer.class);
        assertThat(maxVersion)
                .as("Highest applied migration version should be at least 99 "
                        + "(latest at the time of writing this test)")
                .isGreaterThanOrEqualTo(99);
    }

    @Test
    @DisplayName("DynamicSchedulerBootstrap is absent under integration-test profile")
    void schedulerBootstrap_isSuppressed() {
        assertThat(applicationContext.getBeansOfType(DynamicSchedulerBootstrap.class))
                .as("Integration-test profile must suppress the bootstrap so "
                        + "production cron triggers do not fire")
                .isEmpty();
    }

    @Test
    @DisplayName("AnthropicClient bean is the WireMock-routed override")
    void anthropicClient_isRoutedToWireMock() {
        WIRE_MOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .get(com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching(
                        "/v1/messages/batches/.*"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock
                        .aResponse().withStatus(404)));

        try {
            anthropicClient.messages().batches().retrieve("smoke-test-id");
        } catch (RuntimeException expected) {
            // Stubbed 404 will surface as some Anthropic SDK exception — fine,
            // we only care that the call hit WireMock, not the real API.
        }

        WIRE_MOCK.verify(com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(com.github.tomakehurst.wiremock.client.WireMock
                        .urlPathEqualTo("/v1/messages/batches/smoke-test-id")));
    }
}
