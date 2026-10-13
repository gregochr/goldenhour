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
 * Proves V175 against a real Postgres: {@code forecast_evaluation} and {@code cloud_verification} each
 * gain five nullable INTEGER columns (the far-point mid/high and dark-hour cloud capture), and rows
 * written before the migration read null in every one of them.
 *
 * <p>CI-only — it starts its own Testcontainer, like {@code SchedulerSolarOffsetColumnsMigrationTest},
 * and there is no Docker on the development machine (see CLAUDE.md). It migrates to V174 first so it
 * can plant rows before V175 runs, which {@code IntegrationTestBase} (already fully migrated) cannot do.
 */
@Testcontainers
class FarSolarCloudCaptureMigrationTest {

    private static final String[] FORECAST_COLUMNS = {
        "far_solar_high_cloud", "far_solar_high_cloud_dark", "far_solar_low_cloud_dark",
        "far_solar_mid_cloud", "far_solar_mid_cloud_dark"
    };

    private static final String[] VERIFICATION_COLUMNS = {
        "far_high_cloud", "far_high_cloud_dark", "far_low_cloud_dark",
        "far_mid_cloud", "far_mid_cloud_dark"
    };

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
    @DisplayName("V175 adds ten nullable integer columns and rows planted at V174 read null in all of them")
    void addsNullableColumnsAndLeavesExistingRowsNull() throws SQLException {
        flyway("174").migrate();
        long evaluationId;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO locations (name, lat, lon) "
                    + "VALUES ('Migration Test Point', 54.775, -1.585)");
            st.executeUpdate("INSERT INTO forecast_evaluation (location_lat, location_lon, location_id, "
                    + "target_date, target_type, forecast_run_at, days_ahead) "
                    + "SELECT 54.775, -1.585, id, DATE '2026-10-12', 'SUNSET', "
                    + "TIMESTAMP '2026-10-12 06:00:00', 0 FROM locations "
                    + "WHERE name = 'Migration Test Point'");
            try (ResultSet rs = st.executeQuery("SELECT id FROM forecast_evaluation")) {
                assertThat(rs.next()).isTrue();
                evaluationId = rs.getLong(1);
            }
            st.executeUpdate("INSERT INTO cloud_verification (forecast_evaluation_id, horizon_sample_lat, "
                    + "horizon_sample_lon, verified_at) VALUES (" + evaluationId
                    + ", 54.9, -3.2, TIMESTAMP '2026-10-18 12:00:00')");
        }

        flyway("175").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertNullableIntegerColumns(st, "forecast_evaluation", FORECAST_COLUMNS);
            assertNullableIntegerColumns(st, "cloud_verification", VERIFICATION_COLUMNS);
            assertAllNull(st, "forecast_evaluation", FORECAST_COLUMNS);
            assertAllNull(st, "cloud_verification", VERIFICATION_COLUMNS);
        }
    }

    private static void assertNullableIntegerColumns(Statement st, String table, String[] columns)
            throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT column_name, data_type, is_nullable "
                + "FROM information_schema.columns WHERE table_name = '" + table + "' "
                + "AND column_name IN (" + quotedList(columns) + ") ORDER BY column_name")) {
            for (String column : columns) {
                assertThat(rs.next()).as(table + "." + column + " exists").isTrue();
                assertThat(rs.getString("column_name")).isEqualTo(column);
                assertThat(rs.getString("data_type")).isEqualTo("integer");
                assertThat(rs.getString("is_nullable")).isEqualTo("YES");
            }
            assertThat(rs.next()).as("no unexpected extra column on " + table).isFalse();
        }
    }

    private static void assertAllNull(Statement st, String table, String[] columns) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT " + String.join(", ", columns)
                + " FROM " + table)) {
            assertThat(rs.next()).as("the planted " + table + " row survives").isTrue();
            for (String column : columns) {
                assertThat(rs.getObject(column)).as(table + "." + column).isNull();
            }
            assertThat(rs.next()).isFalse();
        }
    }

    private static String quotedList(String[] columns) {
        StringBuilder sb = new StringBuilder();
        for (String column : columns) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append('\'').append(column).append('\'');
        }
        return sb.toString();
    }
}
