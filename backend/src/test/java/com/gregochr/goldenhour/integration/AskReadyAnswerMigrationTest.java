package com.gregochr.goldenhour.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V165 against a real Postgres (Flyway runs every migration in {@link IntegrationTestBase}):
 * the {@code ask_ready_answer} table, its columns and nullability, and the unique key that makes the
 * Ready precompute's write an upsert.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development
 * machine (see CLAUDE.md). The migration is therefore proven before merge only by CI's Backend job;
 * the store and the repository queries built on it are proven locally on H2 ({@code AskReadyStoreTest}).
 */
class AskReadyAnswerMigrationTest extends IntegrationTestBase {

    private static final String INSERT = "INSERT INTO ask_ready_answer (scope_key, question_id, "
            + "question_text, window_ids, briefing_generated_at, pipeline_run_id, answer_json) "
            + "VALUES (?, ?, 'Best spot tonight?', '2026-10-05_sunset', '2026-10-05 05:02:11', ?, '{}')";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("ask_ready_answer has the expected columns, types and nullability")
    void columnsExist() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable, character_maximum_length "
                        + "FROM information_schema.columns WHERE table_name = 'ask_ready_answer' "
                        + "ORDER BY column_name");

        assertThat(columns).extracting(c -> c.get("column_name")).containsExactly("answer_json",
                "briefing_generated_at", "created_at", "id", "pipeline_run_id", "question_id", "question_text",
                "scope_key", "window_ids");
        assertThat(column(columns, "answer_json")).containsEntry("data_type", "text")
                .containsEntry("is_nullable", "NO");
        assertThat(column(columns, "window_ids")).containsEntry("data_type", "text")
                .containsEntry("is_nullable", "NO");
        assertThat(column(columns, "briefing_generated_at"))
                .containsEntry("data_type", "timestamp without time zone").containsEntry("is_nullable", "NO");
        assertThat(column(columns, "created_at")).containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "NO");
        assertThat(column(columns, "pipeline_run_id")).containsEntry("data_type", "bigint")
                .containsEntry("is_nullable", "YES");
        assertThat(column(columns, "scope_key")).containsEntry("character_maximum_length", 20)
                .containsEntry("is_nullable", "NO");
        assertThat(column(columns, "question_id")).containsEntry("character_maximum_length", 30);
        assertThat(column(columns, "question_text")).containsEntry("character_maximum_length", 200);
    }

    private static Map<String, Object> column(List<Map<String, Object>> columns, String name) {
        return columns.stream().filter(c -> name.equals(c.get("column_name"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a row can be written with no pipeline run (an on-demand precompute) and created_at defaults")
    void pipelineRunIsOptionalAndCreatedAtDefaults() {
        jdbc.update(INSERT, "ALL", "BEST_NEXT", null);

        Map<String, Object> row = jdbc.queryForMap("SELECT pipeline_run_id, created_at FROM ask_ready_answer "
                + "WHERE scope_key = 'ALL' AND question_id = 'BEST_NEXT'");
        assertThat(row.get("pipeline_run_id")).isNull();
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    @DisplayName("(scope_key, question_id) is unique: the same pair twice is refused, a different scope or "
            + "question is not")
    void scopeAndQuestionAreUnique() {
        jdbc.update(INSERT, "7", "BEST_WEEKEND", 1L);

        assertThatThrownBy(() -> jdbc.update(INSERT, "7", "BEST_WEEKEND", 2L))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(INSERT, "8", "BEST_WEEKEND", 2L);
        jdbc.update(INSERT, "7", "BEST_SOON", 2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ask_ready_answer WHERE scope_key IN ('7','8')",
                Integer.class)).isEqualTo(3);
    }
}
