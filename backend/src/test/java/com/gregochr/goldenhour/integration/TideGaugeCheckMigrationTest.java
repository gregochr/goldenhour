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
 * Proves V208 against a real Postgres: the {@code tide_gauge_check} table's columns and defaults, its
 * foreign-key cascade from {@code locations}, and the {@code (location_id, run_at DESC)} index.
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md).
 */
@Testcontainers
class TideGaugeCheckMigrationTest {

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

    private void migrateTo208() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("208")
                .load()
                .migrate();
    }

    @Test
    @DisplayName("V208 creates tide_gauge_check with its columns and the location/run index")
    void createsTableAndIndex() throws SQLException {
        migrateTo208();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT column_name FROM information_schema.columns "
                    + "WHERE table_name = 'tide_gauge_check' ORDER BY ordinal_position")) {
                java.util.List<String> columns = new java.util.ArrayList<>();
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
                assertThat(columns).containsExactly("id", "gauge_measure_id", "location_id", "run_at",
                        "window_from", "window_to", "paired_highs", "median_timing_error_min",
                        "median_height_error_m", "flagged");
            }
            try (ResultSet rs = st.executeQuery("SELECT indexdef FROM pg_indexes "
                    + "WHERE indexname = 'idx_tide_gauge_check_location_run'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).contains("location_id", "run_at DESC");
            }
        }
    }

    @Test
    @DisplayName("flagged defaults to false and rows cascade-delete with the location")
    void defaultsAndCascade() throws SQLException {
        migrateTo208();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO locations (name, lat, lon) VALUES ('Gauge Migration Test', 55.0, -1.5)");
            long id;
            try (ResultSet rs = st.executeQuery("SELECT id FROM locations WHERE name = 'Gauge Migration Test'")) {
                rs.next();
                id = rs.getLong(1);
            }
            st.executeUpdate("INSERT INTO tide_gauge_check (gauge_measure_id, location_id, run_at, window_from, "
                    + "window_to, paired_highs, median_timing_error_min, median_height_error_m) VALUES "
                    + "('m', " + id + ", now(), now(), now(), 12, 3.5, 0.1)");
            try (ResultSet rs = st.executeQuery("SELECT flagged FROM tide_gauge_check WHERE location_id = " + id)) {
                rs.next();
                assertThat(rs.getBoolean(1)).isFalse();
            }

            st.executeUpdate("DELETE FROM locations WHERE id = " + id);

            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM tide_gauge_check WHERE location_id = " + id)) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }
}
