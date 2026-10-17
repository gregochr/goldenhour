package com.gregochr.goldenhour.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V164 against a real Postgres: the {@code forecast_evaluation_prompt} table and its
 * cascading FK, the nullable {@code forecast_evaluation.sky_rating}, and the scheduler seed row.
 *
 * <p>CI-only (Postgres Testcontainer; no Docker on the development machine — see CLAUDE.md).
 */
class ForecastEvaluationPromptMigrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("forecast_evaluation.sky_rating exists as a nullable integer")
    void skyRatingColumnIsNullableInteger() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'forecast_evaluation' AND column_name = 'sky_rating'");

        assertThat(columns).hasSize(1);
        assertThat(columns.get(0)).containsEntry("data_type", "integer")
                .containsEntry("is_nullable", "YES");
    }

    @Test
    @DisplayName("forecast_evaluation_prompt has the expected columns and the created_at index")
    void tableAndIndexExist() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'forecast_evaluation_prompt' ORDER BY column_name");

        assertThat(columns).hasSize(3);
        assertThat(columns.get(0)).containsEntry("column_name", "created_at")
                .containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "NO");
        assertThat(columns.get(1)).containsEntry("column_name", "evaluation_id")
                .containsEntry("data_type", "bigint").containsEntry("is_nullable", "NO");
        assertThat(columns.get(2)).containsEntry("column_name", "user_message")
                .containsEntry("data_type", "text").containsEntry("is_nullable", "NO");

        Integer indexes = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'forecast_evaluation_prompt' "
                        + "AND indexname = 'idx_forecast_evaluation_prompt_created_at'",
                Integer.class);
        assertThat(indexes).isEqualTo(1);
    }

    @Test
    @DisplayName("deleting the parent forecast_evaluation row cascades to its prompt row")
    void foreignKeyCascadesOnParentDelete() {
        Long locationId = jdbc.queryForObject(
                "INSERT INTO locations (name, lat, lon) VALUES ('V164 Test Hill', 55.0, -1.5) "
                        + "RETURNING id", Long.class);
        Long evaluationId = jdbc.queryForObject(
                "INSERT INTO forecast_evaluation (location_id, location_lat, location_lon, "
                        + "target_date, target_type, forecast_run_at, days_ahead) "
                        + "VALUES (?, 55.0, -1.5, CURRENT_DATE, 'SUNRISE', "
                        + "CURRENT_TIMESTAMP, 0) RETURNING id", Long.class, locationId);
        jdbc.update("INSERT INTO forecast_evaluation_prompt (evaluation_id, user_message) "
                + "VALUES (?, 'hello')", evaluationId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM forecast_evaluation_prompt WHERE evaluation_id = ?",
                Integer.class, evaluationId)).isEqualTo(1);

        jdbc.update("DELETE FROM forecast_evaluation WHERE id = ?", evaluationId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM forecast_evaluation_prompt WHERE evaluation_id = ?",
                Integer.class, evaluationId)).isZero();
    }

    @Test
    @DisplayName("the forecast_prompt_cleanup scheduler row is seeded for 03:50 UTC")
    void schedulerJobIsSeeded() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT cron_expression, schedule_type, status FROM scheduler_job_config "
                        + "WHERE job_key = 'forecast_prompt_cleanup'");

        assertThat(row).containsEntry("cron_expression", "0 50 3 * * *")
                .containsEntry("schedule_type", "CRON").containsEntry("status", "ACTIVE");
    }
}
