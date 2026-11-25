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
 * Proves V231 against a real Postgres: {@code snow_state} exists with its plain unique key on
 * (point_key, evaluation_date, event_type) and a date index, takes nullable readings, has no foreign key or CHECK,
 * and the {@code snow_state_refresh} job is seeded as an ACTIVE UTC CRON three times a day.
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine (see CLAUDE.md).
 */
@Testcontainers
class SnowStateMigrationTest {

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

    private void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("231")
                .load()
                .migrate();
    }

    @Test
    @DisplayName("V231 seeds snow_state_refresh as an ACTIVE CRON at 06:30, 14:30 and 21:30 UTC")
    void seedsTheJob() throws SQLException {
        migrate();

        try (Connection c = connect(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT schedule_type, cron_expression, time_zone, status "
                        + "FROM scheduler_job_config WHERE job_key = 'snow_state_refresh'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("schedule_type")).isEqualTo("CRON");
            assertThat(rs.getString("cron_expression")).isEqualTo("0 30 6,14,21 * * *");
            assertThat(rs.getString("time_zone")).isNull();
            assertThat(rs.getString("status")).isEqualTo("ACTIVE");
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    @DisplayName("snow_state takes null readings, rejects a repeated (point, date, event), and allows a new event")
    void uniqueKeyAndNullableReadings() throws SQLException {
        migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO snow_state (point_key, evaluation_date, event_type, computed_at) "
                    + "VALUES ('summit:helvellyn', DATE '2026-11-25', 'SUNRISE', NOW())");
            st.executeUpdate("INSERT INTO snow_state (point_key, evaluation_date, event_type, computed_at) "
                    + "VALUES ('summit:helvellyn', DATE '2026-11-25', 'SUNSET', NOW())");
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO snow_state "
                    + "(point_key, evaluation_date, event_type, snow_water_mm, computed_at) "
                    + "VALUES ('summit:helvellyn', DATE '2026-11-25', 'SUNRISE', 4.5, NOW())"))
                    .isInstanceOf(SQLException.class);
            try (ResultSet rs = st.executeQuery("SELECT snow_water_mm, snowfall_24h_cm, low_cloud_pct "
                    + "FROM snow_state WHERE event_type = 'SUNRISE'")) {
                assertThat(rs.next()).isTrue();
                rs.getDouble("snow_water_mm");
                assertThat(rs.wasNull()).isTrue();
                rs.getInt("low_cloud_pct");
                assertThat(rs.wasNull()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("snow_state carries the date index, one unique constraint, and no foreign key or CHECK")
    void constraintsAndIndex() throws SQLException {
        migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT indexdef FROM pg_indexes "
                    + "WHERE indexname = 'idx_snow_state_evaluation_date'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).contains("evaluation_date").doesNotContain("WHERE");
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_constraint "
                    + "WHERE conrelid = 'snow_state'::regclass AND contype IN ('f', 'c')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
            try (ResultSet rs = st.executeQuery("SELECT conname FROM pg_constraint "
                    + "WHERE conrelid = 'snow_state'::regclass AND contype = 'u'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("uq_snow_state_point_date_event");
                assertThat(rs.next()).isFalse();
            }
        }
    }
}
