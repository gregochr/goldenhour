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
import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V220 against a real Postgres: {@code locations.drive_target_changed_at} is NOT NULL, is backfilled from
 * {@code created_at}, and defaults to now for a later raw-SQL insert; {@code regions.drives_measured_at} is a
 * nullable timestamptz that existing rows read as NULL.
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md). It migrates to V219 first so it can plant rows before V220 runs.
 */
@Testcontainers
class DriveTargetChangedAtMigrationTest {

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
    @DisplayName("V220 backfills drive_target_changed_at from created_at, makes it NOT NULL, and defaults it")
    void backfillsAndDefaultsDriveTargetChangedAt() throws SQLException {
        flyway("219").migrate();
        Timestamp createdAt = Timestamp.valueOf("2026-03-04 05:06:07");
        long locationId;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO locations (name, lat, lon, created_at) "
                    + "VALUES ('Drive Target Migration Test Point', 54.6, -3.1, '2026-03-04 05:06:07')");
            try (ResultSet rs = st.executeQuery(
                    "SELECT id FROM locations WHERE name = 'Drive Target Migration Test Point'")) {
                rs.next();
                locationId = rs.getLong(1);
            }
        }

        flyway("220").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT data_type, is_nullable FROM information_schema.columns "
                    + "WHERE table_name = 'locations' AND column_name = 'drive_target_changed_at'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("data_type")).isEqualTo("timestamp without time zone");
                assertThat(rs.getString("is_nullable")).isEqualTo("NO");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT drive_target_changed_at FROM locations WHERE id = " + locationId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getTimestamp(1)).isEqualTo(createdAt);
            }
            st.executeUpdate("INSERT INTO locations (name, lat, lon) "
                    + "VALUES ('Drive Target Default Test Point', 54.7, -3.2)");
            try (ResultSet rs = st.executeQuery("SELECT drive_target_changed_at FROM locations "
                    + "WHERE name = 'Drive Target Default Test Point'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getTimestamp(1)).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("V220 adds a nullable timestamptz regions.drives_measured_at that existing rows read as NULL")
    void addsNullableDrivesMeasuredAt() throws SQLException {
        flyway("219").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO regions (name) VALUES ('Drives Measured Migration Region')");
        }

        flyway("220").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT data_type, is_nullable FROM information_schema.columns "
                    + "WHERE table_name = 'regions' AND column_name = 'drives_measured_at'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("data_type")).isEqualTo("timestamp with time zone");
                assertThat(rs.getString("is_nullable")).isEqualTo("YES");
            }
            try (ResultSet rs = st.executeQuery("SELECT drives_measured_at FROM regions "
                    + "WHERE name = 'Drives Measured Migration Region'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject(1)).isNull();
            }
        }
    }
}
