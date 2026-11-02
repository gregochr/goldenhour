package com.gregochr.goldenhour.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V216 against a real Postgres (Flyway runs every migration in {@link IntegrationTestBase}):
 * {@code sky_rating_eval_run} gains a nullable {@code git_commit_message} (500 characters) and a nullable
 * {@code fixture_names} text column, and a row written without either (every row before V216) still inserts
 * and reads null, while a row carrying both keeps them.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md), so the migration is proven before merge only by CI's Backend job.
 */
class SkyRatingEvalRunVersionMigrationTest extends IntegrationTestBase {

    private static final String INSERT_RUN = "INSERT INTO sky_rating_eval_run (run_timestamp, started_at, model, "
            + "runs_per_fixture, trigger_source, status, git_commit_hash";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("the message column is a nullable varchar(500) and fixture_names a nullable text column")
    void columnsHaveTheExpectedShape() {
        Integer messageLength = jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns "
                + "WHERE table_name = 'sky_rating_eval_run' AND column_name = 'git_commit_message'", Integer.class);

        assertThat(column("git_commit_message", "data_type")).isEqualTo("character varying");
        assertThat(messageLength).isEqualTo(500);
        assertThat(column("git_commit_message", "is_nullable")).isEqualTo("YES");
        assertThat(column("fixture_names", "data_type")).isEqualTo("text");
        assertThat(column("fixture_names", "is_nullable")).isEqualTo("YES");
    }

    @Test
    @DisplayName("a run inserted without the new columns reads null, and one with them keeps them")
    void insertWithAndWithoutTheColumns() {
        jdbc.update(INSERT_RUN + ") VALUES (now(), now(), 'SONNET', 1, 'MANUAL', 'COMPLETED', 'v215-historic')");
        jdbc.update(INSERT_RUN + ", git_commit_message, fixture_names) VALUES (now(), now(), 'SONNET', 1, "
                + "'MANUAL', 'COMPLETED', 'v215-subset', 'Fix the prompt', 'a-fixture,b-fixture')");
        try {
            assertThat(read("git_commit_message", "v215-historic")).isNull();
            assertThat(read("fixture_names", "v215-historic")).isNull();
            assertThat(read("git_commit_message", "v215-subset")).isEqualTo("Fix the prompt");
            assertThat(read("fixture_names", "v215-subset")).isEqualTo("a-fixture,b-fixture");
        } finally {
            jdbc.update("DELETE FROM sky_rating_eval_run WHERE git_commit_hash IN ('v215-historic', 'v215-subset')");
        }
    }

    private String read(String column, String hash) {
        return jdbc.queryForObject("SELECT " + column + " FROM sky_rating_eval_run WHERE git_commit_hash = ?",
                String.class, hash);
    }

    private String column(String name, String attribute) {
        return jdbc.queryForObject("SELECT " + attribute + " FROM information_schema.columns "
                + "WHERE table_name = 'sky_rating_eval_run' AND column_name = ?", String.class, name);
    }
}
