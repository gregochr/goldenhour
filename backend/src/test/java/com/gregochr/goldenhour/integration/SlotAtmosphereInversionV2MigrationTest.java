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
 * Proves V202 against a real Postgres: {@code slot_atmosphere.inversion_score_v1} and
 * {@code inversion_score_v2_raw} are nullable DOUBLE PRECISION, and a row planted before the migration reads
 * NULL in both (so no pre-existing row is marked as a "v2 era" row).
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md). It migrates to V201 first so it can plant a row before V202 runs.
 */
@Testcontainers
class SlotAtmosphereInversionV2MigrationTest {

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
    @DisplayName("V202 adds two nullable double columns; an existing row reads NULL; both can be written")
    void addsNullableColumns() throws SQLException {
        flyway("201").migrate();
        long locationId;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO locations (name, lat, lon) "
                    + "VALUES ('Inversion V2 Migration Test Point', 54.568, -3.170)");
            try (ResultSet rs = st.executeQuery(
                    "SELECT id FROM locations WHERE name = 'Inversion V2 Migration Test Point'")) {
                rs.next();
                locationId = rs.getLong(1);
            }
            st.executeUpdate("INSERT INTO slot_atmosphere (location_id, evaluation_date, event_type, "
                    + "inversion_score, inversion_scored, evaluated_at) VALUES (" + locationId
                    + ", DATE '2026-11-01', 'SUNRISE', 7.0, TRUE, TIMESTAMPTZ '2026-10-31 20:00:00+00')");
        }

        flyway("202").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertNullableDouble(st, "inversion_score_v1");
            assertNullableDouble(st, "inversion_score_v2_raw");
            try (ResultSet rs = st.executeQuery("SELECT inversion_score, inversion_score_v1, "
                    + "inversion_score_v2_raw FROM slot_atmosphere WHERE location_id = " + locationId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble("inversion_score")).isEqualTo(7.0);
                assertThat(rs.getObject("inversion_score_v1")).as("a pre-V202 row is not a v2-era row").isNull();
                assertThat(rs.getObject("inversion_score_v2_raw")).isNull();
            }
            st.executeUpdate("INSERT INTO slot_atmosphere (location_id, evaluation_date, event_type, "
                    + "inversion_score, inversion_scored, inversion_score_v1, inversion_score_v2_raw, "
                    + "evaluated_at) VALUES (" + locationId + ", DATE '2026-11-02', 'SUNRISE', 7.0, TRUE, 6.0, "
                    + "8.0, TIMESTAMPTZ '2026-11-01 20:00:00+00')");
            try (ResultSet rs = st.executeQuery("SELECT inversion_score_v1, inversion_score_v2_raw "
                    + "FROM slot_atmosphere WHERE evaluation_date = DATE '2026-11-02'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble("inversion_score_v1")).isEqualTo(6.0);
                assertThat(rs.getDouble("inversion_score_v2_raw")).isEqualTo(8.0);
            }
        }
    }

    private static void assertNullableDouble(Statement st, String column) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT data_type, is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'slot_atmosphere' AND column_name = '" + column + "'")) {
            assertThat(rs.next()).as("slot_atmosphere." + column + " exists").isTrue();
            assertThat(rs.getString("data_type")).isEqualTo("double precision");
            assertThat(rs.getString("is_nullable")).isEqualTo("YES");
        }
    }
}
