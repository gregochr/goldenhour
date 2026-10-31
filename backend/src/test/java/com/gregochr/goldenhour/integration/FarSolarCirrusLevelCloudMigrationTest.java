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
 * Proves V209 against a real Postgres: {@code forecast_evaluation} gains two nullable INTEGER columns
 * (250 and 200 hPa cloud at the far point), and a row written before the migration
 * reads null in every one of them.
 *
 * <p>CI-only — it starts its own Testcontainer, like {@code SchedulerSolarOffsetColumnsMigrationTest},
 * and there is no Docker on the development machine (see CLAUDE.md). It migrates to V208 first so it
 * can plant rows before V209 runs, which {@code IntegrationTestBase} (already fully migrated) cannot do.
 */
@Testcontainers
class FarSolarCirrusLevelCloudMigrationTest {

    private static final String[] FORECAST_COLUMNS = {
        "far_solar_cloud_200hpa", "far_solar_cloud_250hpa"
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
    @DisplayName("V209 adds two nullable integer columns and a row planted at V208 read null in all of them")
    void addsNullableColumnsAndLeavesExistingRowsNull() throws SQLException {
        flyway("208").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO locations (name, lat, lon) "
                    + "VALUES ('Migration Test Point', 54.775, -1.585)");
            st.executeUpdate("INSERT INTO forecast_evaluation (location_lat, location_lon, location_id, "
                    + "target_date, target_type, forecast_run_at, days_ahead) "
                    + "SELECT 54.775, -1.585, id, DATE '2026-10-12', 'SUNSET', "
                    + "TIMESTAMP '2026-10-12 06:00:00', 0 FROM locations "
                    + "WHERE name = 'Migration Test Point'");
        }

        flyway("209").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertNullableIntegerColumns(st, "forecast_evaluation", FORECAST_COLUMNS);
            assertAllNull(st, "forecast_evaluation", FORECAST_COLUMNS);
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
