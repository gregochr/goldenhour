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
 * Proves V173 against a real Postgres: {@code briefing_model_comparison} moves from the seeded
 * {@code 0 0 5 * * *} to {@code 0 30 6 * * *}, a hand-edited cron is left alone, and no other
 * {@code scheduler_job_config} row changes.
 *
 * <p>CI-only — it starts its own Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md).
 */
@Testcontainers
class BriefingModelComparisonRescheduleMigrationTest {

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
    @DisplayName("The seeded 05:00 cron moves to 06:30 and no other schedule row changes")
    void movesTheSeededValueOnly() throws SQLException {
        flyway("172").migrate();
        Map<String, String> before;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            before = crons(st);
        }
        assertThat(before).containsEntry("briefing_model_comparison", "0 0 5 * * *");

        flyway("173").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            Map<String, String> after = crons(st);
            Map<String, String> expected = new LinkedHashMap<>(before);
            expected.put("briefing_model_comparison", "0 30 6 * * *");
            assertThat(after).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("A hand-edited cron is left exactly as the operator set it")
    void leavesAHandEditedValueAlone() throws SQLException {
        flyway("172").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE scheduler_job_config SET cron_expression = '0 15 7 * * *' "
                    + "WHERE job_key = 'briefing_model_comparison'");
        }

        flyway("173").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(crons(st)).containsEntry("briefing_model_comparison", "0 15 7 * * *");
        }
    }
}
