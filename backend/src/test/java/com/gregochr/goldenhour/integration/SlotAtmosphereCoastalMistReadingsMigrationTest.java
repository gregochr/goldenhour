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
 * Proves V211 against a real Postgres: {@code slot_atmosphere.coastal_mist_low_cloud_pct} is a nullable integer and
 * {@code coastal_mist_precip_mm} a nullable double precision, and a row planted before the migration reads NULL
 * for both (so no pre-existing row looks like it was measured).
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md). It migrates to V210 first so it can plant a row before V211 runs.
 */
@Testcontainers
class SlotAtmosphereCoastalMistReadingsMigrationTest {

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
    @DisplayName("V211 adds two nullable reading columns; an existing row reads NULL for both")
    void addsColumns() throws SQLException {
        flyway("210").migrate();
        long locationId;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO locations (name, lat, lon) "
                    + "VALUES ('Coastal Readings Migration Test Point', 55.42, -1.59)");
            try (ResultSet rs = st.executeQuery(
                    "SELECT id FROM locations WHERE name = 'Coastal Readings Migration Test Point'")) {
                rs.next();
                locationId = rs.getLong(1);
            }
            st.executeUpdate("INSERT INTO slot_atmosphere (location_id, evaluation_date, event_type, "
                    + "coastal_mist_band, coastal_mist_scored, evaluated_at) VALUES (" + locationId
                    + ", DATE '2026-11-01', 'SUNRISE', 'LIGHT', TRUE, TIMESTAMPTZ '2026-10-31 20:00:00+00')");
        }

        flyway("211").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertColumn(st, "coastal_mist_low_cloud_pct", "integer");
            assertColumn(st, "coastal_mist_precip_mm", "double precision");
            try (ResultSet rs = st.executeQuery("SELECT coastal_mist_low_cloud_pct, coastal_mist_precip_mm "
                    + "FROM slot_atmosphere WHERE location_id = " + locationId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("coastal_mist_low_cloud_pct")).as("a pre-V211 row has no low cloud").isNull();
                assertThat(rs.getObject("coastal_mist_precip_mm")).as("a pre-V211 row has no precipitation").isNull();
            }
            st.executeUpdate("INSERT INTO slot_atmosphere (location_id, evaluation_date, event_type, "
                    + "coastal_mist_low_cloud_pct, coastal_mist_precip_mm, evaluated_at) VALUES (" + locationId
                    + ", DATE '2026-11-02', 'SUNRISE', 35, 0.25, TIMESTAMPTZ '2026-11-01 20:00:00+00')");
            try (ResultSet rs = st.executeQuery("SELECT coastal_mist_low_cloud_pct, coastal_mist_precip_mm "
                    + "FROM slot_atmosphere WHERE evaluation_date = DATE '2026-11-02'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("coastal_mist_low_cloud_pct")).isEqualTo(35);
                assertThat(rs.getDouble("coastal_mist_precip_mm")).isEqualTo(0.25);
            }
        }
    }

    private static void assertColumn(Statement st, String column, String dataType) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT data_type, is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'slot_atmosphere' AND column_name = '" + column + "'")) {
            assertThat(rs.next()).as("slot_atmosphere." + column + " exists").isTrue();
            assertThat(rs.getString("data_type")).isEqualTo(dataType);
            assertThat(rs.getString("is_nullable")).isEqualTo("YES");
        }
    }
}
