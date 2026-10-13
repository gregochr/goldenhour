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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V172 against a real Postgres: it seeds exactly one {@code scheduler_job_config} row, the
 * ACTIVE ten-minute {@code forecast_cycle_watchdog}, and changes no existing schedule (production's
 * cron values were hand-edited, so nothing may assume one).
 *
 * <p>CI-only — it starts its own Testcontainer, like {@code SchedulerSolarOffsetColumnsMigrationTest},
 * and there is no Docker on the development machine (see CLAUDE.md).
 */
@Testcontainers
class ForecastCycleWatchdogSeedMigrationTest {

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

    @Test
    @DisplayName("V172 adds exactly the watchdog row, ACTIVE and every ten minutes, and leaves a "
            + "hand-edited cycle schedule exactly as it was")
    void seedsTheWatchdogAndTouchesNothingElse() throws SQLException {
        flyway("171").migrate();
        int rowsBefore;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE scheduler_job_config SET cron_expression = '0 0 1 * * *', "
                    + "status = 'ACTIVE' WHERE job_key = 'near_term_batch_evaluation'");
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM scheduler_job_config")) {
                rs.next();
                rowsBefore = rs.getInt(1);
            }
        }

        flyway("172").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM scheduler_job_config")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(rowsBefore + 1);
            }
            try (ResultSet rs = st.executeQuery("SELECT schedule_type, fixed_delay_ms, "
                    + "initial_delay_ms, status, cron_expression FROM scheduler_job_config "
                    + "WHERE job_key = 'forecast_cycle_watchdog'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("schedule_type")).isEqualTo("FIXED_DELAY");
                assertThat(rs.getLong("fixed_delay_ms")).isEqualTo(600_000L);
                assertThat(rs.getLong("initial_delay_ms")).isEqualTo(600_000L);
                assertThat(rs.getString("status")).isEqualTo("ACTIVE");
                assertThat(rs.getString("cron_expression")).isNull();
                assertThat(rs.next()).as("exactly one row").isFalse();
            }
            try (ResultSet rs = st.executeQuery("SELECT cron_expression, status "
                    + "FROM scheduler_job_config WHERE job_key = 'near_term_batch_evaluation'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("cron_expression")).isEqualTo("0 0 1 * * *");
                assertThat(rs.getString("status")).isEqualTo("ACTIVE");
            }
        }
    }
}
