package com.gregochr.goldenhour.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V213 against a real Postgres (Flyway runs every migration in {@link IntegrationTestBase}): the
 * {@code sky_rating_eval_model_exclusion} opt-out table exists, starts empty (so every selectable model is
 * scheduled by default), and is keyed by model.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md), so the migration is proven before merge only by CI's Backend job.
 */
class EvalModelExclusionMigrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("the exclusion table exists and starts empty")
    void tableExistsAndIsEmpty() {
        String regclass = jdbc.queryForObject(
                "SELECT to_regclass('sky_rating_eval_model_exclusion')::text", String.class);
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM sky_rating_eval_model_exclusion", Integer.class);

        assertThat(regclass).isEqualTo("sky_rating_eval_model_exclusion");
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("the model is the primary key, so excluding a model twice is refused")
    void modelIsPrimaryKey() {
        jdbc.update("INSERT INTO sky_rating_eval_model_exclusion (model, excluded_at) VALUES (?, now())",
                "SONNET_55");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO sky_rating_eval_model_exclusion (model, excluded_at) VALUES (?, now())",
                    "SONNET_55")).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        } finally {
            jdbc.update("DELETE FROM sky_rating_eval_model_exclusion WHERE model = ?", "SONNET_55");
        }
    }
}
