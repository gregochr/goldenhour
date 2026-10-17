package com.gregochr.goldenhour.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V163 against a real Postgres (Flyway runs every migration in {@link IntegrationTestBase}):
 * {@code pipeline_run} gains {@code disposition_job_run_id BIGINT} and
 * {@code failures_settled_at TIMESTAMP WITH TIME ZONE}, both nullable.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development
 * machine (see CLAUDE.md). The migration is therefore proven before merge only by CI's Backend job;
 * the repository behaviour built on the columns is proven locally on H2.
 */
class PipelineRunFailureSettleStateMigrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("V163 adds disposition_job_run_id (bigint) and failures_settled_at (timestamptz) "
            + "to pipeline_run, both nullable")
    void columnsExistAndAreNullable() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'pipeline_run' "
                        + "AND column_name IN ('disposition_job_run_id', 'failures_settled_at') "
                        + "ORDER BY column_name");

        assertThat(columns).hasSize(2);
        assertThat(columns.get(0)).containsEntry("column_name", "disposition_job_run_id")
                .containsEntry("data_type", "bigint").containsEntry("is_nullable", "YES");
        assertThat(columns.get(1)).containsEntry("column_name", "failures_settled_at")
                .containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "YES");
    }

    @Test
    @DisplayName("a pipeline_run row inserted without the new columns carries NULL in both")
    void existingStyleRowsKeepNulls() {
        jdbc.update("INSERT INTO pipeline_run (cycle_type, status, trigger_time) "
                + "VALUES ('NIGHTLY', 'COMPLETED', CURRENT_TIMESTAMP)");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT disposition_job_run_id, failures_settled_at FROM pipeline_run "
                        + "ORDER BY id DESC LIMIT 1");

        assertThat(row.get("disposition_job_run_id")).isNull();
        assertThat(row.get("failures_settled_at")).isNull();
    }
}
