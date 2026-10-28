package com.gregochr.goldenhour.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
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
 * Proves V197 against a real Postgres: each index created for a query on a growing table exists
 * after every migration, and the planner uses it for that query's shape.
 *
 * <p>The tables are empty, so {@code enable_seqscan = off} is set for the session: it makes the
 * planner choose an index whenever one can serve the predicate, which is exactly the question
 * (is the index usable for this query), without depending on table statistics. CI-only — there is
 * no Docker on the development machine (see CLAUDE.md).
 */
@Testcontainers
class GrowingTableIndexMigrationTest {

    @Container
    @SuppressWarnings("resource")
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("goldenhour_migration_test")
                    .withUsername("test")
                    .withPassword("test");

    @BeforeEach
    void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private String indexDef(String indexName) throws SQLException {
        String sql = "SELECT indexdef FROM pg_indexes WHERE indexname = '" + indexName + "'";
        try (Connection c = connect(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as(indexName + " exists after migrate()").isTrue();
            return rs.getString(1);
        }
    }

    private String explain(String query) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("SET enable_seqscan = off");
            try (ResultSet rs = st.executeQuery("EXPLAIN " + query)) {
                while (rs.next()) {
                    plan.append(rs.getString(1)).append('\n');
                }
            }
        }
        return plan.toString();
    }

    @Test
    @DisplayName("I1 idx_api_call_log_batch_id is partial on batch_id and serves the batch lookups")
    void apiCallLogBatchIdIndex() throws SQLException {
        assertThat(indexDef("idx_api_call_log_batch_id"))
                .contains("(batch_id)").contains("WHERE (batch_id IS NOT NULL)");
        assertThat(explain("SELECT custom_id FROM api_call_log WHERE batch_id = 'msgbatch_x'"))
                .contains("idx_api_call_log_batch_id");
        assertThat(explain("SELECT custom_id FROM api_call_log "
                + "WHERE batch_id IN ('msgbatch_a', 'msgbatch_b') "
                + "AND custom_id IS NOT NULL"))
                .contains("idx_api_call_log_batch_id");
    }

    @Test
    @DisplayName("I2 idx_forecast_evaluation_pending_run_at serves the abandonment sweep")
    void pendingAbandonmentIndex() throws SQLException {
        assertThat(indexDef("idx_forecast_evaluation_pending_run_at"))
                .contains("(forecast_run_at)").contains("batch_state");
        assertThat(explain("SELECT id FROM forecast_evaluation "
                + "WHERE batch_state = 'PENDING' AND forecast_run_at < now()"))
                .contains("idx_forecast_evaluation_pending_run_at");
    }

    @Test
    @DisplayName("I3 idx_forecast_score_pipeline_run serves the per-cycle scored-components lookup")
    void forecastScorePipelineRunIndex() throws SQLException {
        assertThat(indexDef("idx_forecast_score_pipeline_run")).contains("(pipeline_run_id)");
        assertThat(explain("SELECT location_id, forecast_type_id FROM forecast_score "
                + "WHERE pipeline_run_id = 1 AND forecast_type_id IN (1, 2)"))
                .contains("idx_forecast_score_pipeline_run");
    }

    @Test
    @DisplayName("I4 idx_forecast_evaluation_date_type serves the by-date-and-type read")
    void forecastEvaluationDateTypeIndex() throws SQLException {
        assertThat(indexDef("idx_forecast_evaluation_date_type"))
                .contains("(target_date, target_type)");
        assertThat(explain("SELECT id FROM forecast_evaluation "
                + "WHERE target_date = DATE '2026-10-28' AND target_type IN ('SUNRISE', 'SUNSET')"))
                .contains("idx_forecast_evaluation_date_type");
    }

    @Test
    @DisplayName("I6 idx_job_run_run_type_started serves the run-type and started_at reads")
    void jobRunRunTypeStartedIndex() throws SQLException {
        assertThat(indexDef("idx_job_run_run_type_started"))
                .contains("(run_type, started_at)");
        assertThat(explain("SELECT id FROM job_run "
                + "WHERE run_type = 'SHORT_TERM' AND started_at >= now() - interval '1 day'"))
                .contains("idx_job_run_run_type_started");
    }
}
