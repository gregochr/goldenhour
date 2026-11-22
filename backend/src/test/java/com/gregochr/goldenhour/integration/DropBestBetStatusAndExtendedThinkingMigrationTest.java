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
 * Proves V228 ({@code drop_best_bet_status_and_extended_thinking_columns}) against a real Postgres 17 engine.
 *
 * <p>Flyway is driven directly, as {@link DropPipelineRunPickMigrationTest} does, so the test stops at V227,
 * confirms both columns exist, then applies V228. V228 drops {@code pipeline_run.best_bet_status} and
 * {@code model_selection.extended_thinking} and must leave the rows themselves otherwise unchanged.
 */
@Testcontainers
class DropBestBetStatusAndExtendedThinkingMigrationTest {

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

    private static long count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long columnCount(Statement st, String table, String column) throws SQLException {
        return count(st, "SELECT count(*) FROM information_schema.columns "
                + "WHERE table_name = '" + table + "' AND column_name = '" + column + "'");
    }

    /** Every model_selection row as "run_type|active_model", in a stable order. */
    private static String modelRows(Statement st) throws SQLException {
        StringBuilder rows = new StringBuilder();
        try (ResultSet rs = st.executeQuery(
                "SELECT run_type, active_model FROM model_selection ORDER BY run_type")) {
            while (rs.next()) {
                rows.append(rs.getString(1)).append('|').append(rs.getString(2)).append(';');
            }
        }
        return rows.toString();
    }

    @Test
    @DisplayName("V228 drops best_bet_status and extended_thinking, leaving pipeline_run and model_selection rows")
    void v228_dropsBothColumnsKeepingRows() throws SQLException {
        flywayTo("227").migrate();

        String modelRowsBefore;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO pipeline_run (id, cycle_type, status, trigger_time, best_bet_status) "
                    + "VALUES (1, 'NIGHTLY', 'COMPLETED', now(), 'SUCCESS_NO_PICKS')");

            // Positive controls: both columns exist and the model_selection table is populated.
            assertThat(columnCount(st, "pipeline_run", "best_bet_status")).isEqualTo(1);
            assertThat(columnCount(st, "model_selection", "extended_thinking")).isEqualTo(1);
            modelRowsBefore = modelRows(st);
            assertThat(modelRowsBefore).isNotEmpty();
        }

        flywayTo("228").migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(columnCount(st, "pipeline_run", "best_bet_status")).isZero();
            assertThat(columnCount(st, "model_selection", "extended_thinking")).isZero();

            assertThat(count(st, "SELECT count(*) FROM pipeline_run WHERE id = 1 AND cycle_type = 'NIGHTLY' "
                    + "AND status = 'COMPLETED'")).isEqualTo(1);
            assertThat(modelRows(st)).isEqualTo(modelRowsBefore);
        }
    }
}
