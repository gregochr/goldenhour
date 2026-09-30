package com.gregochr.goldenhour.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
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
 * Proves V159 ({@code rename_survivor_atmosphere_to_slot_atmosphere}) against a real Postgres 17
 * engine, the same {@link ClearDriveTimeStampWithoutRowsMigrationTest}/
 * {@link RetireDeadOptimisationStrategiesMigrationTest} shape: Flyway is driven directly so the
 * test can stop at V158 — the schema as a production database holds it before this rename — seed a
 * row under the OLD table name, apply V159, and assert the table, its primary key, its foreign key,
 * its unique constraint and its index all now exist under the {@code slot_atmosphere} spelling
 * with the row's data intact, and that none of the five OLD names exist any more.
 *
 * <p>This is a pure rename (V159's own header comment); the point of this test is exactly that
 * nothing behavioural changes — same columns, same data, same row count — only the names differ.
 *
 * <p>The container field is non-static for the same reason
 * {@link ClearDriveTimeStampWithoutRowsMigrationTest}'s own javadoc gives: a fresh container (and
 * therefore a fresh schema) per test method.
 */
@Testcontainers
class SlotAtmosphereRenameMigrationTest {

    @Container
    @SuppressWarnings("resource")
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("goldenhour_migration_test")
                    .withUsername("test")
                    .withPassword("test");

    private Flyway flywayTo(String targetVersion) {
        FluentConfiguration config = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
        if (targetVersion != null) {
            config = config.target(targetVersion);
        }
        return config.load();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /**
     * Returns a location id already seeded by an earlier migration (V84 is the first to insert
     * one), so no new {@code locations} row is needed — matching
     * {@link ClearDriveTimeStampWithoutRowsMigrationTest#anySeededLocationId}.
     */
    private long anySeededLocationId(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT id FROM locations LIMIT 1")) {
            assertThat(rs.next()).as("an earlier migration has already seeded a location by V158").isTrue();
            return rs.getLong(1);
        }
    }

    private boolean regclassExists(Statement st, String name) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT to_regclass('" + name + "')")) {
            rs.next();
            return rs.getObject(1) != null;
        }
    }

    private boolean constraintExists(Statement st, String tableName, String constraintName)
            throws SQLException {
        try (ResultSet rs = st.executeQuery(
                "SELECT 1 FROM pg_constraint c JOIN pg_class t ON c.conrelid = t.oid "
                        + "WHERE t.relname = '" + tableName + "' AND c.conname = '"
                        + constraintName + "'")) {
            return rs.next();
        }
    }

    @Test
    @DisplayName("V159 renames the table, its primary key, its foreign key, its unique constraint "
            + "and its index to the slot_atmosphere spelling, preserves the seeded row's data, and "
            + "leaves none of the five old survivor_atmosphere names behind")
    void v159_renamesTableAndEveryDependentObject_dataIntact() throws SQLException {
        // 1. Stop at V158: the schema as a production database holds it before this rename.
        flywayTo("158").migrate();

        long locationId;
        long seededRowId;

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            locationId = anySeededLocationId(st);

            // Seed a row under the OLD table name, with a non-null reading on every V115/V123/
            // V124/V158 column, so a rename that silently dropped or truncated anything is caught.
            try (ResultSet rs = st.executeQuery(
                    "INSERT INTO survivor_atmosphere "
                            + "(location_id, evaluation_date, event_type, aerosol_optical_depth, "
                            + "dust, pm2_5, surge_risk_level, snow_depth_m, freezing_level_m, "
                            + "humidity, temperature_celsius, inversion_score, inversion_scored, "
                            + "surge_total_m, surge_wind_speed_ms, surge_wind_direction_deg, "
                            + "evaluated_at) "
                            + "VALUES (" + locationId + ", '2026-09-30', 'SUNSET', 0.42, 60.00, "
                            + "12.00, 'HIGH', 0.04, 850.0, 88, -2.0, 7.5, TRUE, 0.6, 9.0, 250.0, "
                            + "'2026-09-30T20:47:00Z') RETURNING id")) {
                rs.next();
                seededRowId = rs.getLong(1);
            }
        }

        // 2. Apply V159 alone — it is the latest migration in this change.
        flywayTo(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            // The renamed table exists and the old name is gone.
            assertThat(regclassExists(st, "slot_atmosphere"))
                    .as("slot_atmosphere exists after V159").isTrue();
            assertThat(regclassExists(st, "survivor_atmosphere"))
                    .as("survivor_atmosphere no longer exists after V159").isFalse();

            // Every dependent object was renamed too, not just the table.
            assertThat(constraintExists(st, "slot_atmosphere", "slot_atmosphere_pkey"))
                    .as("primary key renamed to slot_atmosphere_pkey").isTrue();
            assertThat(constraintExists(st, "slot_atmosphere", "survivor_atmosphere_pkey"))
                    .as("old survivor_atmosphere_pkey name gone").isFalse();

            assertThat(constraintExists(st, "slot_atmosphere", "fk_slot_atmosphere_location"))
                    .as("foreign key renamed to fk_slot_atmosphere_location").isTrue();
            assertThat(constraintExists(st, "slot_atmosphere", "fk_survivor_atmosphere_location"))
                    .as("old fk_survivor_atmosphere_location name gone").isFalse();

            assertThat(constraintExists(st, "slot_atmosphere", "uq_slot_atmosphere"))
                    .as("unique constraint renamed to uq_slot_atmosphere").isTrue();
            assertThat(constraintExists(st, "slot_atmosphere", "uq_survivor_atmosphere"))
                    .as("old uq_survivor_atmosphere name gone").isFalse();

            assertThat(regclassExists(st, "idx_slot_atmosphere_date"))
                    .as("index renamed to idx_slot_atmosphere_date").isTrue();
            assertThat(regclassExists(st, "idx_survivor_atmosphere_date"))
                    .as("old idx_survivor_atmosphere_date name gone").isFalse();

            // The seeded row's data survived the rename untouched, under the new table name.
            try (ResultSet rs = st.executeQuery(
                    "SELECT location_id, evaluation_date::text, event_type, dust, "
                            + "inversion_score, inversion_scored, surge_total_m "
                            + "FROM slot_atmosphere WHERE id = " + seededRowId)) {
                assertThat(rs.next()).as("the seeded row is still readable by its id").isTrue();
                assertThat(rs.getLong("location_id")).isEqualTo(locationId);
                assertThat(rs.getString("evaluation_date")).isEqualTo("2026-09-30");
                assertThat(rs.getString("event_type")).isEqualTo("SUNSET");
                assertThat(rs.getBigDecimal("dust")).isEqualByComparingTo("60.00");
                assertThat(rs.getDouble("inversion_score")).isEqualTo(7.5);
                assertThat(rs.getBoolean("inversion_scored")).isTrue();
                assertThat(rs.getDouble("surge_total_m")).isEqualTo(0.6);
            }

            // Exactly one row total — the rename did not duplicate or drop anything.
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM slot_atmosphere")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(1L);
            }
        }
    }
}
