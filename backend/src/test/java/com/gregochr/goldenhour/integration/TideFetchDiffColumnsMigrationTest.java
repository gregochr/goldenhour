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
 * Proves V207 against a real Postgres: {@code tide_fetch} gains nullable {@code overlap_pairs}
 * (INTEGER), {@code max_lag_delta_min} and {@code max_height_delta_m} (DOUBLE PRECISION), existing
 * rows keep NULL in them, and a new row can carry values.
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md). It migrates to V206 first so it can plant a pre-V207 row.
 */
@Testcontainers
class TideFetchDiffColumnsMigrationTest {

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
    @DisplayName("V207 adds three nullable diff columns; an existing row keeps NULL and a new row can set them")
    void addsNullableDiffColumns() throws SQLException {
        flyway("206").migrate();
        long locationId;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO locations (name, lat, lon) VALUES ('Diff Columns Test Point', 54.6, -3.1)");
            try (ResultSet rs = st.executeQuery("SELECT id FROM locations WHERE name = 'Diff Columns Test Point'")) {
                rs.next();
                locationId = rs.getLong(1);
            }
            st.executeUpdate("INSERT INTO tide_fetch (location_id, fetched_at, reason, success) "
                    + "VALUES (" + locationId + ", '2026-11-01 02:00:00', 'TAIL', true)");
        }

        flyway("207").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertColumn(st, "overlap_pairs", "integer");
            assertColumn(st, "max_lag_delta_min", "double precision");
            assertColumn(st, "max_height_delta_m", "double precision");
            try (ResultSet rs = st.executeQuery("SELECT overlap_pairs, max_lag_delta_min, max_height_delta_m "
                    + "FROM tide_fetch WHERE location_id = " + locationId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("overlap_pairs")).isNull();
                assertThat(rs.getObject("max_lag_delta_min")).isNull();
                assertThat(rs.getObject("max_height_delta_m")).isNull();
            }
            st.executeUpdate("INSERT INTO tide_fetch (location_id, fetched_at, reason, success, overlap_pairs, "
                    + "max_lag_delta_min, max_height_delta_m) VALUES (" + locationId
                    + ", '2026-11-09 02:00:00', 'TAIL', true, 28, 46.0, 0.07)");
            try (ResultSet rs = st.executeQuery("SELECT overlap_pairs, max_lag_delta_min, max_height_delta_m "
                    + "FROM tide_fetch WHERE fetched_at = '2026-11-09 02:00:00'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("overlap_pairs")).isEqualTo(28);
                assertThat(rs.getDouble("max_lag_delta_min")).isEqualTo(46.0);
                assertThat(rs.getDouble("max_height_delta_m")).isEqualTo(0.07);
            }
        }
    }

    private static void assertColumn(Statement st, String column, String type) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT data_type, is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'tide_fetch' AND column_name = '" + column + "'")) {
            assertThat(rs.next()).as(column).isTrue();
            assertThat(rs.getString("data_type")).as(column + " type").isEqualTo(type);
            assertThat(rs.getString("is_nullable")).as(column + " nullable").isEqualTo("YES");
        }
    }
}
