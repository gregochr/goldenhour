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
 * Proves V200 against a real Postgres: with a populated run/result pair in each of the two model-test
 * families (including a re-run whose {@code parent_run_id} points at another run, and a result row that
 * references a region and a location), migrating to the latest version drops all four tables and leaves the
 * seeded region and location rows intact.
 *
 * <p>CI-only — it starts its own Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md).
 */
@Testcontainers
class DropModelTestTablesMigrationTest {

    private static final String[] DROPPED_TABLES = {
        "briefing_model_test_result", "briefing_model_test_run", "model_test_result", "model_test_run"
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

    private boolean tableExists(Statement st, String table) throws SQLException {
        try (ResultSet rs = st.executeQuery(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = '" + table + "'")) {
            rs.next();
            return rs.getInt(1) == 1;
        }
    }

    private long count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void seed(Statement st) throws SQLException {
        st.execute("INSERT INTO regions (name) VALUES ('Migration Test Region')");
        st.execute("INSERT INTO locations (name, lat, lon) VALUES ('Migration Test Location', 55.0, -1.5)");
        st.execute("INSERT INTO model_test_run (started_at, target_date, target_type) "
                + "VALUES (NOW(), DATE '2026-10-01', 'SUNRISE')");
        st.execute("INSERT INTO model_test_run (started_at, target_date, target_type, parent_run_id) "
                + "SELECT NOW(), DATE '2026-10-01', 'SUNRISE', MIN(id) FROM model_test_run");
        st.execute("INSERT INTO model_test_result (test_run_id, region_id, region_name, location_id, "
                + "location_name, target_date, target_type, evaluation_model, created_at) "
                + "SELECT (SELECT MIN(id) FROM model_test_run), r.id, r.name, l.id, l.name, "
                + "DATE '2026-10-01', 'SUNRISE', 'HAIKU', NOW() "
                + "FROM regions r, locations l "
                + "WHERE r.name = 'Migration Test Region' AND l.name = 'Migration Test Location'");
        st.execute("INSERT INTO briefing_model_test_run (started_at) VALUES (NOW())");
        st.execute("INSERT INTO briefing_model_test_result (test_run_id, evaluation_model, created_at) "
                + "SELECT MIN(id), 'HAIKU', NOW() FROM briefing_model_test_run");
    }

    @Test
    @DisplayName("All four model-test tables are dropped and regions and locations are untouched")
    void dropsTheFourTablesAndKeepsRegionsAndLocations() throws SQLException {
        flyway("199").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            for (String table : DROPPED_TABLES) {
                assertThat(tableExists(st, table)).as("%s exists at V199", table).isTrue();
            }
            seed(st);
            assertThat(count(st, "SELECT COUNT(*) FROM model_test_result")).isEqualTo(1);
        }

        flyway("latest").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            for (String table : DROPPED_TABLES) {
                assertThat(tableExists(st, table)).as("%s exists after V200", table).isFalse();
            }
            assertThat(count(st, "SELECT COUNT(*) FROM regions WHERE name = 'Migration Test Region'"))
                    .isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM locations WHERE name = 'Migration Test Location'"))
                    .isEqualTo(1);
        }
    }
}
