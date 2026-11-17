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
 * Proves V224 against a real Postgres: {@code weather_warning_version} enforces its unique (warning_id, version) key
 * and its status CHECK, and the {@code weather_warning_poll} job is seeded DISABLED_BY_CONFIG on a five-minute fixed
 * delay gated by {@code photocast.warnings.enabled}.
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine (see CLAUDE.md).
 */
@Testcontainers
class WeatherWarningVersionMigrationTest {

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
                .target("224")
                .load()
                .migrate();
    }

    @Test
    @DisplayName("V224 seeds weather_warning_poll DISABLED_BY_CONFIG, gated by photocast.warnings.enabled")
    void seedsTheJob() throws SQLException {
        migrate();

        try (Connection c = connect(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT schedule_type, fixed_delay_ms, time_zone, status, "
                        + "status_before_config_disable, config_source "
                        + "FROM scheduler_job_config WHERE job_key = 'weather_warning_poll'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("schedule_type")).isEqualTo("FIXED_DELAY");
            assertThat(rs.getLong("fixed_delay_ms")).isEqualTo(300000L);
            assertThat(rs.getString("time_zone")).isEqualTo("Europe/London");
            assertThat(rs.getString("status")).isEqualTo("DISABLED_BY_CONFIG");
            assertThat(rs.getString("status_before_config_disable")).isEqualTo("ACTIVE");
            assertThat(rs.getString("config_source")).isEqualTo("photocast.warnings.enabled");
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    @DisplayName("a duplicate (warning_id, warning_version) and an unknown status are both rejected")
    void uniqueKeyAndStatusCheck() throws SQLException {
        migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO weather_warning_version (warning_id, warning_version, status, received_at) "
                    + "VALUES ('W1', 1, 'ACTIVE', NOW())");
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO weather_warning_version "
                    + "(warning_id, warning_version, status, received_at) VALUES ('W1', 1, 'CANCELLED', NOW())"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO weather_warning_version "
                    + "(warning_id, warning_version, status, received_at) VALUES ('W1', 2, 'MAYBE', NOW())"))
                    .isInstanceOf(SQLException.class);
            try (ResultSet rs = st.executeQuery("SELECT simulated FROM weather_warning_version")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isFalse();
            }
        }
    }

    @Test
    @DisplayName("the (warning_id, warning_version DESC) and valid_to indexes exist")
    void indexes() throws SQLException {
        migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT indexdef FROM pg_indexes "
                    + "WHERE indexname = 'idx_weather_warning_version_id_version'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).contains("warning_id", "warning_version DESC");
            }
            try (ResultSet rs = st.executeQuery("SELECT indexdef FROM pg_indexes "
                    + "WHERE indexname = 'idx_weather_warning_version_valid_to'")) {
                assertThat(rs.next()).isTrue();
            }
        }
    }
}
