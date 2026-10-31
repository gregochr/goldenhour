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
 * Proves V205 against a real Postgres: {@code locations.tide_lat} / {@code tide_lng} (nullable
 * DOUBLE PRECISION, both-or-neither CHECK), the {@code tide_fetch} table (columns, foreign-key
 * cascade) and the data block that copies a neighbour's coordinates onto the ten affected
 * locations by exact name.
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md). It migrates to V204 first so it can plant a roster before V205 runs.
 */
@Testcontainers
class TidePointMigrationTest {

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

    private static void insertLocation(Statement st, String name, double lat, double lon) throws SQLException {
        st.executeUpdate("INSERT INTO locations (name, lat, lon) VALUES ('" + name.replace("'", "''")
                + "', " + lat + ", " + lon + ")");
    }

    private static long idOf(Statement st, String name) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT id FROM locations WHERE name = '" + name + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void assertTidePoint(Statement st, String name, Double lat, Double lng) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT tide_lat, tide_lng FROM locations WHERE name = '"
                + name.replace("'", "''") + "'")) {
            assertThat(rs.next()).as(name).isTrue();
            assertThat((Double) rs.getObject("tide_lat")).as(name + " tide_lat").isEqualTo(lat);
            assertThat((Double) rs.getObject("tide_lng")).as(name + " tide_lng").isEqualTo(lng);
        }
    }

    @Test
    @DisplayName("V205 copies each source location's coordinates onto the ten targets and no others")
    void dataBlockSetsTheTenTidePoints() throws SQLException {
        flyway("204").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            insertLocation(st, "Runswick Bay", 54.5, -0.7);
            insertLocation(st, "Staithes", 54.56, -0.79);
            insertLocation(st, "Redcar", 54.62, -1.06);
            insertLocation(st, "Saltburn-by-the-Sea", 54.58, -0.97);
            insertLocation(st, "Alnmouth", 55.39, -1.6);
            insertLocation(st, "Black Hole", 55.5, -1.6);
            insertLocation(st, "Cullernose Point", 55.48, -1.59);
            insertLocation(st, "Dunstanburgh Castle", 55.49, -1.6);
            insertLocation(st, "Howick Scar", 55.44, -1.59);
            insertLocation(st, "Rumbling Kern", 55.45, -1.58);
            insertLocation(st, "Sugar Sands", 55.46, -1.58);
            insertLocation(st, "The Bathing House, Craster", 55.47, -1.59);
            insertLocation(st, "Newbiggin-by-the-Sea", 55.18, -1.5);
            insertLocation(st, "Cresswell Beach", 55.23, -1.54);
            insertLocation(st, "Whitby Piers", 54.49, -0.61);
        }

        flyway("205").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertTidePoint(st, "Staithes", 54.5, -0.7);
            assertTidePoint(st, "Saltburn-by-the-Sea", 54.62, -1.06);
            java.util.List<String> crasterGroup = java.util.List.of("Black Hole", "Cullernose Point",
                    "Dunstanburgh Castle", "Howick Scar", "Rumbling Kern", "Sugar Sands",
                    "The Bathing House, Craster");
            for (String craster : crasterGroup) {
                assertTidePoint(st, craster, 55.39, -1.6);
            }
            assertTidePoint(st, "Cresswell Beach", 55.18, -1.5);
            assertTidePoint(st, "Runswick Bay", null, null);
            assertTidePoint(st, "Alnmouth", null, null);
            assertTidePoint(st, "Whitby Piers", null, null);
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM locations WHERE tide_lat IS NOT NULL")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(10);
            }
        }
    }

    @Test
    @DisplayName("V205 on an empty roster sets nothing and does not fail")
    void emptyRosterIsHarmless() throws SQLException {
        flyway("205").migrate();

        try (Connection c = connect(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM locations WHERE tide_lat IS NOT NULL")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    @DisplayName("tide_lat and tide_lng are both set or both null; tide_fetch rows cascade with the location")
    void checkConstraintAndCascade() throws SQLException {
        flyway("205").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            insertLocation(st, "Tide Point Migration Test Point", 54.6, -3.1);
            long id = idOf(st, "Tide Point Migration Test Point");

            assertThatThrownBy(() -> st.executeUpdate(
                    "UPDATE locations SET tide_lat = 54.5 WHERE id = " + id))
                    .isInstanceOf(SQLException.class);
            st.executeUpdate("UPDATE locations SET tide_lat = 54.5, tide_lng = -3.0 WHERE id = " + id);
            st.executeUpdate("UPDATE locations SET tide_lat = NULL, tide_lng = NULL WHERE id = " + id);

            st.executeUpdate("INSERT INTO tide_fetch (location_id, fetched_at, reason, success) "
                    + "VALUES (" + id + ", '2026-11-01 02:00:00', 'SEED', true)");
            try (ResultSet rs = st.executeQuery("SELECT extremes_stored, atlas, call_count FROM tide_fetch "
                    + "WHERE location_id = " + id)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("extremes_stored")).isZero();
                assertThat(rs.getObject("atlas")).isNull();
                assertThat(rs.getObject("call_count")).isNull();
            }

            st.executeUpdate("DELETE FROM locations WHERE id = " + id);
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM tide_fetch WHERE location_id = " + id)) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    @Test
    @DisplayName("tide_fetch has the planned columns and types")
    void tideFetchColumns() throws SQLException {
        flyway("205").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertColumn(st, "reason", "character varying", "NO");
            assertColumn(st, "success", "boolean", "NO");
            assertColumn(st, "fetched_at", "timestamp without time zone", "NO");
            assertColumn(st, "window_from", "timestamp without time zone", "YES");
            assertColumn(st, "request_lat", "double precision", "YES");
            assertColumn(st, "response_lng", "double precision", "YES");
            assertColumn(st, "atlas", "character varying", "YES");
            assertColumn(st, "response_datum", "character varying", "YES");
            assertColumn(st, "call_count", "integer", "YES");
            assertColumn(st, "extremes_stored", "integer", "NO");
        }
    }

    private static void assertColumn(Statement st, String column, String type, String nullable)
            throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT data_type, is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'tide_fetch' AND column_name = '" + column + "'")) {
            assertThat(rs.next()).as(column).isTrue();
            assertThat(rs.getString("data_type")).as(column + " type").isEqualTo(type);
            assertThat(rs.getString("is_nullable")).as(column + " nullable").isEqualTo(nullable);
        }
    }
}
