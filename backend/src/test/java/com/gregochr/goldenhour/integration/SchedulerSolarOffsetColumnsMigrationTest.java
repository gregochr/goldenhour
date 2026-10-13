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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V171 against a real Postgres: {@code scheduler_job_config} gains the nullable
 * {@code solar_event} and {@code solar_offset_minutes} columns with their CHECKs, and the migration
 * touches no existing row (production's cron values were hand-edited, so nothing may assume one).
 *
 * <p>CI-only — it starts its own Testcontainer, like {@code DispositionSlotIndexMigrationTest}, and
 * there is no Docker on the development machine (see CLAUDE.md). It migrates to V170 first so it can
 * plant a row with a hand-edited cron before V171 runs, which {@code IntegrationTestBase} (already
 * fully migrated) cannot do.
 */
@Testcontainers
class SchedulerSolarOffsetColumnsMigrationTest {

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
    @DisplayName("V171 adds both columns, nullable, and leaves a hand-edited row exactly as it was")
    void addsNullableColumnsAndTouchesNoRow() throws SQLException {
        flyway("170").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE scheduler_job_config SET cron_expression = '0 0 1 * * *', "
                    + "status = 'ACTIVE' WHERE job_key = 'near_term_batch_evaluation'");
        }

        flyway("171").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT column_name, data_type, is_nullable "
                    + "FROM information_schema.columns WHERE table_name = 'scheduler_job_config' "
                    + "AND column_name IN ('solar_event', 'solar_offset_minutes') "
                    + "ORDER BY column_name")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("column_name")).isEqualTo("solar_event");
                assertThat(rs.getString("data_type")).isEqualTo("character varying");
                assertThat(rs.getString("is_nullable")).isEqualTo("YES");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("column_name")).isEqualTo("solar_offset_minutes");
                assertThat(rs.getString("data_type")).isEqualTo("integer");
                assertThat(rs.getString("is_nullable")).isEqualTo("YES");
            }
            try (ResultSet rs = st.executeQuery("SELECT schedule_type, cron_expression, status, "
                    + "solar_event, solar_offset_minutes FROM scheduler_job_config "
                    + "WHERE job_key = 'near_term_batch_evaluation'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("schedule_type")).isEqualTo("CRON");
                assertThat(rs.getString("cron_expression")).isEqualTo("0 0 1 * * *");
                assertThat(rs.getString("status")).isEqualTo("ACTIVE");
                assertThat(rs.getString("solar_event")).isNull();
                assertThat(rs.getObject("solar_offset_minutes")).isNull();
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM scheduler_job_config "
                    + "WHERE schedule_type = 'SOLAR_OFFSET' OR solar_event IS NOT NULL "
                    + "OR solar_offset_minutes IS NOT NULL")) {
                rs.next();
                assertThat(rs.getInt(1)).as("no row was switched by the migration").isZero();
            }
        }
    }

    @Test
    @DisplayName("the CHECKs accept a solar rule and NULLs, and refuse an unknown event or an "
            + "offset outside 0..720")
    void checkConstraintsHoldTheApplicationsRules() throws SQLException {
        flyway("171").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE scheduler_job_config SET solar_event = 'SUNRISE', "
                    + "solar_offset_minutes = 255 WHERE job_key = 'near_term_batch_evaluation'");
            st.executeUpdate("UPDATE scheduler_job_config SET solar_event = 'SUNSET', "
                    + "solar_offset_minutes = 0 WHERE job_key = 'intraday_forecast_refresh'");
            st.executeUpdate("UPDATE scheduler_job_config SET solar_event = 'SUNSET', "
                    + "solar_offset_minutes = 720 WHERE job_key = 'intraday_forecast_refresh'");
            st.executeUpdate("UPDATE scheduler_job_config SET solar_event = NULL, "
                    + "solar_offset_minutes = NULL WHERE job_key = 'intraday_forecast_refresh'");

            assertThatThrownBy(() -> st.executeUpdate("UPDATE scheduler_job_config "
                    + "SET solar_event = 'NOON' WHERE job_key = 'intraday_forecast_refresh'"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.executeUpdate("UPDATE scheduler_job_config "
                    + "SET solar_offset_minutes = 721 WHERE job_key = 'intraday_forecast_refresh'"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.executeUpdate("UPDATE scheduler_job_config "
                    + "SET solar_offset_minutes = -1 WHERE job_key = 'intraday_forecast_refresh'"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
