package com.gregochr.goldenhour.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V195 against a real Postgres (Flyway runs every migration in {@link IntegrationTestBase}): the legacy
 * {@code actual_outcome} table is gone, and its replacement {@code outcome_rating} is untouched.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md), so the migration is proven before merge only by CI's Backend job.
 */
class DropActualOutcomeMigrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("actual_outcome no longer exists after Flyway has run")
    void actualOutcomeIsDropped() {
        String regclass = jdbc.queryForObject("SELECT to_regclass('actual_outcome')::text", String.class);

        assertThat(regclass).isNull();
    }

    @Test
    @DisplayName("outcome_rating, the table that replaced it, still exists")
    void outcomeRatingRemains() {
        String regclass = jdbc.queryForObject("SELECT to_regclass('outcome_rating')::text", String.class);

        assertThat(regclass).isEqualTo("outcome_rating");
    }
}
