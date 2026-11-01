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
 * Proves V210 against a real Postgres: {@code slot_atmosphere.coastal_mist_band} is a nullable VARCHAR(10),
 * {@code coastal_mist_scored} is a NOT NULL boolean defaulting to false, and a row planted before the migration
 * reads NULL / false (so no pre-existing row is marked as scored).
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md). It migrates to V209 first so it can plant a row before V210 runs.
 */
@Testcontainers
class SlotAtmosphereCoastalMistMigrationTest {

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
    @DisplayName("V210 adds a nullable band and a NOT NULL scored flag; an existing row reads NULL / false")
    void addsColumns() throws SQLException {
        flyway("209").migrate();
        long locationId;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO locations (name, lat, lon) "
                    + "VALUES ('Coastal Mist Migration Test Point', 55.42, -1.59)");
            try (ResultSet rs = st.executeQuery(
                    "SELECT id FROM locations WHERE name = 'Coastal Mist Migration Test Point'")) {
                rs.next();
                locationId = rs.getLong(1);
            }
            st.executeUpdate("INSERT INTO slot_atmosphere (location_id, evaluation_date, event_type, "
                    + "inversion_score, inversion_scored, evaluated_at) VALUES (" + locationId
                    + ", DATE '2026-11-01', 'SUNRISE', 7.0, TRUE, TIMESTAMPTZ '2026-10-31 20:00:00+00')");
        }

        flyway("210").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertColumn(st, "coastal_mist_band", "character varying", "YES");
            assertColumn(st, "coastal_mist_scored", "boolean", "NO");
            try (ResultSet rs = st.executeQuery("SELECT coastal_mist_band, coastal_mist_scored "
                    + "FROM slot_atmosphere WHERE location_id = " + locationId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("coastal_mist_band")).as("a pre-V210 row has no band").isNull();
                assertThat(rs.getBoolean("coastal_mist_scored")).as("a pre-V210 row is not scored").isFalse();
            }
            st.executeUpdate("INSERT INTO slot_atmosphere (location_id, evaluation_date, event_type, "
                    + "coastal_mist_band, coastal_mist_scored, evaluated_at) VALUES (" + locationId
                    + ", DATE '2026-11-02', 'SUNRISE', 'LIGHT', TRUE, TIMESTAMPTZ '2026-11-01 20:00:00+00')");
            try (ResultSet rs = st.executeQuery("SELECT coastal_mist_band, coastal_mist_scored "
                    + "FROM slot_atmosphere WHERE evaluation_date = DATE '2026-11-02'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("coastal_mist_band")).isEqualTo("LIGHT");
                assertThat(rs.getBoolean("coastal_mist_scored")).isTrue();
            }
        }
    }

    private static void assertColumn(Statement st, String column, String dataType, String nullable)
            throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT data_type, is_nullable, character_maximum_length "
                + "FROM information_schema.columns "
                + "WHERE table_name = 'slot_atmosphere' AND column_name = '" + column + "'")) {
            assertThat(rs.next()).as("slot_atmosphere." + column + " exists").isTrue();
            assertThat(rs.getString("data_type")).isEqualTo(dataType);
            assertThat(rs.getString("is_nullable")).isEqualTo(nullable);
            if ("coastal_mist_band".equals(column)) {
                assertThat(rs.getInt("character_maximum_length")).isEqualTo(10);
            }
        }
    }
}
