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
 * Proves V199 against a real Postgres: {@code locations.valley_floor_m} (nullable INTEGER) and
 * {@code valley_floor_lat} / {@code valley_floor_lng} (nullable DOUBLE PRECISION), and that a row planted
 * before the migration reads NULL in all three.
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md). It migrates to V198 first so it can plant a row before V199 runs.
 */
@Testcontainers
class LocationValleyFloorMigrationTest {

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
    @DisplayName("V199 adds three nullable columns with the right types; an existing row reads NULL")
    void addsNullableColumns() throws SQLException {
        flyway("198").migrate();
        long locationId;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO locations (name, lat, lon) "
                    + "VALUES ('Valley Floor Migration Test Point', 54.6, -3.1)");
            try (ResultSet rs = st.executeQuery(
                    "SELECT id FROM locations WHERE name = 'Valley Floor Migration Test Point'")) {
                rs.next();
                locationId = rs.getLong(1);
            }
        }

        flyway("199").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertNullableColumn(st, "valley_floor_m", "integer");
            assertNullableColumn(st, "valley_floor_lat", "double precision");
            assertNullableColumn(st, "valley_floor_lng", "double precision");
            try (ResultSet rs = st.executeQuery("SELECT valley_floor_m, valley_floor_lat, valley_floor_lng "
                    + "FROM locations WHERE id = " + locationId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("valley_floor_m")).isNull();
                assertThat(rs.getObject("valley_floor_lat")).isNull();
                assertThat(rs.getObject("valley_floor_lng")).isNull();
            }
            st.executeUpdate("UPDATE locations SET valley_floor_m = 80, valley_floor_lat = 54.58, "
                    + "valley_floor_lng = -3.12 WHERE id = " + locationId);
            try (ResultSet rs = st.executeQuery("SELECT valley_floor_m, valley_floor_lat, valley_floor_lng "
                    + "FROM locations WHERE id = " + locationId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("valley_floor_m")).isEqualTo(80);
                assertThat(rs.getDouble("valley_floor_lat")).isEqualTo(54.58);
                assertThat(rs.getDouble("valley_floor_lng")).isEqualTo(-3.12);
            }
        }
    }

    private static void assertNullableColumn(Statement st, String column, String type) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT data_type, is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'locations' AND column_name = '" + column + "'")) {
            assertThat(rs.next()).as("locations." + column + " exists").isTrue();
            assertThat(rs.getString("data_type")).isEqualTo(type);
            assertThat(rs.getString("is_nullable")).isEqualTo("YES");
        }
    }
}
