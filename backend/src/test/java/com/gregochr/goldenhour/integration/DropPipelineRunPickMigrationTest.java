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
 * Proves V227 ({@code drop_pipeline_run_pick_and_retire_best_bet_model_row}) against a real Postgres 17 engine.
 *
 * <p>Flyway is driven directly, as {@link RetireTermRunTypesMigrationTest} does, so the test stops at V226, seeds
 * the state V227 acts on, then applies it. V227 drops {@code pipeline_run_pick} and deletes the
 * {@code BRIEFING_BEST_BET} {@code model_selection} row, and must leave the {@code job_run} and
 * {@code api_call_log} rows of that run type (cost history) and every other row alone.
 */
@Testcontainers
class DropPipelineRunPickMigrationTest {

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

    @Test
    @DisplayName("V227 drops pipeline_run_pick and the BRIEFING_BEST_BET model row, keeping job_run, "
            + "api_call_log, pipeline_run and the other model rows")
    void v227_dropsPickTableAndModelRowOnly() throws SQLException {
        flywayTo("226").migrate();

        long modelRowsBefore;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO pipeline_run (id, cycle_type, status, trigger_time) "
                    + "VALUES (1, 'NIGHTLY', 'COMPLETED', now())");
            st.execute("INSERT INTO pipeline_run_pick (id, pipeline_run_id, pick_rank, headline, recorded_at) "
                    + "VALUES (10, 1, 1, 'a pick', now())");
            long jobRunId;
            try (ResultSet rs = st.executeQuery("INSERT INTO job_run (run_type, started_at) "
                    + "VALUES ('BRIEFING_BEST_BET', CURRENT_TIMESTAMP) RETURNING id")) {
                rs.next();
                jobRunId = rs.getLong(1);
            }
            st.execute("INSERT INTO api_call_log (job_run_id, service, called_at) VALUES ("
                    + jobRunId + ", 'ANTHROPIC', CURRENT_TIMESTAMP)");

            // Positive controls: V69 seeded the model row, and the pick table is populated.
            assertThat(count(st, "SELECT count(*) FROM model_selection WHERE run_type = 'BRIEFING_BEST_BET'"))
                    .isEqualTo(1);
            assertThat(count(st, "SELECT count(*) FROM pipeline_run_pick")).isEqualTo(1);
            modelRowsBefore = count(st, "SELECT count(*) FROM model_selection");
        }

        flywayTo("227").migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_name = 'pipeline_run_pick'")).isZero();
            assertThat(count(st, "SELECT count(*) FROM model_selection WHERE run_type = 'BRIEFING_BEST_BET'"))
                    .isZero();
            assertThat(count(st, "SELECT count(*) FROM model_selection")).isEqualTo(modelRowsBefore - 1);

            assertThat(count(st, "SELECT count(*) FROM job_run WHERE run_type = 'BRIEFING_BEST_BET'"))
                    .isEqualTo(1);
            assertThat(count(st, "SELECT count(*) FROM api_call_log")).isEqualTo(1);
            assertThat(count(st, "SELECT count(*) FROM pipeline_run")).isEqualTo(1);
        }
    }
}
