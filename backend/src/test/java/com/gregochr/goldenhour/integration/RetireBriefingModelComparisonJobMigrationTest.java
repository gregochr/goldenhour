package com.gregochr.goldenhour.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V199 against a real Postgres: the {@code briefing_model_comparison} row exists at V198 and is
 * gone after migrating to V199, while every other {@code scheduler_job_config} row is still
 * present with its cron unchanged.
 *
 * <p>CI-only — it starts its own Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md).
 */
@Testcontainers
class RetireBriefingModelComparisonJobMigrationTest {

    private static final String JOB_KEY = "briefing_model_comparison";

    @Container
    @SuppressWarnings("resource")
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("goldenhour_migration_test")
                    .withUsername("test")
                    .withPassword("test");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private Map<String, String> crons(Statement st) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ResultSet rs = st.executeQuery(
                "SELECT job_key, cron_expression FROM scheduler_job_config ORDER BY job_key")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2));
            }
        }
        return out;
    }

    @Test
    @DisplayName("The job row is removed and every other job keeps its cron")
    void removesOnlyTheBriefingModelComparisonRow() throws SQLException {
        flyway("198").migrate();
        Map<String, String> before;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            before = crons(st);
        }
        assertThat(before).containsKey(JOB_KEY);

        flyway("199").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            Map<String, String> after = crons(st);
            Map<String, String> expected = new LinkedHashMap<>(before);
            expected.remove(JOB_KEY);
            assertThat(after).doesNotContainKey(JOB_KEY).isEqualTo(expected);
        }
    }
}
