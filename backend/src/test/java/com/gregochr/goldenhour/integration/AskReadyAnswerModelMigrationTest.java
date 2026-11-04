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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V219 against a real Postgres: {@code ask_ready_answer.evaluation_model} is added, every row
 * that existed before it is backfilled {@code HAIKU}, no other column of those rows changes, and a row
 * written afterwards may leave the column null.
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md).
 */
@Testcontainers
class AskReadyAnswerModelMigrationTest {

    private static final String INSERT = "INSERT INTO ask_ready_answer (scope_key, question_id, question_text, "
            + "window_ids, briefing_generated_at, pipeline_run_id, answer_json, created_at) VALUES "
            + "('%s', '%s', 'Best spot tonight?', '2026-10-05_sunset', '2026-10-05 05:02:11', 7, "
            + "'{\"answered\":true}', '2026-10-05 06:00:00+00')";

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

    /** Every row's non-model columns as one string each, ordered by key. */
    private List<String> otherColumns(Statement st) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT scope_key || '|' || question_id || '|' || question_text "
                + "|| '|' || window_ids || '|' || briefing_generated_at || '|' || pipeline_run_id || '|' "
                + "|| answer_json || '|' || created_at FROM ask_ready_answer ORDER BY scope_key, question_id")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    @Test
    @DisplayName("V219 backfills every existing row HAIKU and leaves its other columns untouched")
    void backfillsExistingRowsHaiku() throws SQLException {
        flyway("218").migrate();
        List<String> before;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate(String.format(INSERT, "ALL", "BEST_NEXT"));
            st.executeUpdate(String.format(INSERT, "3", "BEST_WEEKEND"));
            before = otherColumns(st);
            assertThat(before).hasSize(2);
        }

        flyway("219").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*), COUNT(*) FILTER (WHERE evaluation_model = "
                    + "'HAIKU') FROM ask_ready_answer")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
                assertThat(rs.getInt(2)).isEqualTo(2);
            }
            assertThat(otherColumns(st)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("V219's column is nullable: a row written afterwards without a model reads as null")
    void columnStaysNullable() throws SQLException {
        flyway("219").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate(String.format(INSERT, "ALL", "RARE_EVENTS"));
            try (ResultSet rs = st.executeQuery("SELECT evaluation_model FROM ask_ready_answer")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isNull();
            }
        }
    }
}
