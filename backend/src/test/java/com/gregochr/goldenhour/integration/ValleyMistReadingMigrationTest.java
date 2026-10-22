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
 * Proves V185 against a real Postgres: {@code locations.mist_prone} (BOOLEAN NOT NULL DEFAULT
 * FALSE) and {@code slot_atmosphere.mist_score} (nullable DOUBLE PRECISION) /
 * {@code mist_scored} (BOOLEAN NOT NULL DEFAULT FALSE), and that rows planted before the migration
 * read FALSE / NULL / FALSE, which is the "pre-column row" state the V158 rule distinguishes from an
 * authoritative fresh null.
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md). It migrates to V184 first so it can plant rows before V185 runs, which
 * {@code IntegrationTestBase} (already fully migrated) cannot do.
 */
@Testcontainers
class ValleyMistReadingMigrationTest {

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
    @DisplayName("V185 adds the three columns with the right types, and pre-existing rows read FALSE/NULL/FALSE")
    void addsColumnsAndDefaultsExistingRows() throws SQLException {
        flyway("184").migrate();
        long locationId;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO locations (name, lat, lon) "
                    + "VALUES ('Mist Migration Test Point', 54.43, -3.03)");
            try (ResultSet rs = st.executeQuery(
                    "SELECT id FROM locations WHERE name = 'Mist Migration Test Point'")) {
                rs.next();
                locationId = rs.getLong(1);
            }
            st.executeUpdate("INSERT INTO slot_atmosphere (location_id, evaluation_date, event_type, "
                    + "inversion_score, inversion_scored, evaluated_at) VALUES (" + locationId
                    + ", DATE '2026-10-17', 'SUNRISE', 4.0, TRUE, TIMESTAMPTZ '2026-10-16 20:00:00+00')");
        }

        flyway("185").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertColumn(st, "locations", "mist_prone", "boolean", "NO");
            assertColumn(st, "slot_atmosphere", "mist_score", "double precision", "YES");
            assertColumn(st, "slot_atmosphere", "mist_scored", "boolean", "NO");
            assertColumn(st, "slot_atmosphere", "mist_potential", "character varying", "YES");
            try (ResultSet rs = st.executeQuery("SELECT character_maximum_length FROM information_schema.columns "
                    + "WHERE table_name = 'slot_atmosphere' AND column_name = 'mist_potential'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("VARCHAR(10) holds the longest band name FOGBOUND").isEqualTo(10);
            }

            try (ResultSet rs = st.executeQuery(
                    "SELECT mist_prone FROM locations WHERE id = " + locationId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).as("a location planted before V185 is not flagged").isFalse();
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT mist_score, mist_scored, mist_potential FROM slot_atmosphere "
                            + "WHERE location_id = " + locationId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("mist_potential")).as("no band on a pre-column row").isNull();
                assertThat(rs.getObject("mist_score")).as("no score on a pre-column row").isNull();
                assertThat(rs.getBoolean("mist_scored"))
                        .as("a pre-column row is NOT an authoritative answer").isFalse();
                assertThat(rs.next()).isFalse();
            }

            // A fresh write: mist_scored TRUE, and the band name stored beside a (high) score.
            st.executeUpdate("INSERT INTO slot_atmosphere (location_id, evaluation_date, event_type, "
                    + "mist_score, mist_scored, mist_potential, evaluated_at) VALUES (" + locationId
                    + ", DATE '2026-10-18', 'SUNRISE', 9.5, TRUE, 'FOGBOUND', TIMESTAMPTZ '2026-10-17 20:00:00+00')");
            try (ResultSet rs = st.executeQuery(
                    "SELECT mist_score, mist_scored, mist_potential FROM slot_atmosphere "
                            + "WHERE evaluation_date = DATE '2026-10-18'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble("mist_score")).isEqualTo(9.5);
                assertThat(rs.getBoolean("mist_scored")).isTrue();
                assertThat(rs.getString("mist_potential")).isEqualTo("FOGBOUND");
            }
        }
    }

    private static void assertColumn(Statement st, String table, String column, String type,
            String nullable) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT data_type, is_nullable, column_default "
                + "FROM information_schema.columns WHERE table_name = '" + table
                + "' AND column_name = '" + column + "'")) {
            assertThat(rs.next()).as(table + "." + column + " exists").isTrue();
            assertThat(rs.getString("data_type")).isEqualTo(type);
            assertThat(rs.getString("is_nullable")).isEqualTo(nullable);
            if ("NO".equals(nullable)) {
                assertThat(rs.getString("column_default")).as(table + "." + column + " default")
                        .containsIgnoringCase("false");
            }
        }
    }
}
