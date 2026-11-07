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
 * Proves V221 against a real Postgres: the three {@code locations.drive_point_*} columns exist, are nullable, and
 * read NULL on every existing row; a later row can carry all three.
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine (see CLAUDE.md).
 * It migrates to V220 first so it can plant a row before V221 runs.
 */
@Testcontainers
class LocationDrivePointMigrationTest {

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
    @DisplayName("V221 adds three nullable drive_point columns that existing rows read as NULL")
    void addsNullableDrivePointColumns() throws SQLException {
        flyway("220").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO locations (name, lat, lon) "
                    + "VALUES ('Drive Point Migration Test Summit', 54.5, -3.0)");
        }

        flyway("221").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT column_name, data_type, is_nullable "
                    + "FROM information_schema.columns WHERE table_name = 'locations' "
                    + "AND column_name LIKE 'drive_point_%' ORDER BY column_name")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("column_name")).isEqualTo("drive_point_lat");
                assertThat(rs.getString("data_type")).isEqualTo("double precision");
                assertThat(rs.getString("is_nullable")).isEqualTo("YES");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("column_name")).isEqualTo("drive_point_lon");
                assertThat(rs.getString("is_nullable")).isEqualTo("YES");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("column_name")).isEqualTo("drive_point_name");
                assertThat(rs.getString("data_type")).isEqualTo("character varying");
                assertThat(rs.getString("is_nullable")).isEqualTo("YES");
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = st.executeQuery("SELECT drive_point_name, drive_point_lat, drive_point_lon "
                    + "FROM locations WHERE name = 'Drive Point Migration Test Summit'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject(1)).isNull();
                assertThat(rs.getObject(2)).isNull();
                assertThat(rs.getObject(3)).isNull();
            }
            st.executeUpdate("INSERT INTO locations (name, lat, lon, drive_point_name, drive_point_lat, "
                    + "drive_point_lon) VALUES ('Drive Point Migration Test With Point', 54.5, -3.0, "
                    + "'Glenridding', 54.5442, -2.9505)");
            try (ResultSet rs = st.executeQuery("SELECT drive_point_name FROM locations "
                    + "WHERE name = 'Drive Point Migration Test With Point'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Glenridding");
            }
        }
    }
}
