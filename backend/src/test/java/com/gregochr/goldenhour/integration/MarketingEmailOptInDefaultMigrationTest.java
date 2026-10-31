package com.gregochr.goldenhour.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V205 against a real Postgres (Flyway runs every migration in {@link IntegrationTestBase}): a row
 * inserted WITHOUT {@code marketing_email_opt_in} reads FALSE, while an explicit TRUE is kept.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md), so the migration is proven before merge only by CI's Backend job.
 */
class MarketingEmailOptInDefaultMigrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'v205-%'");
    }

    @Test
    @DisplayName("a row inserted without the column reads FALSE: no consent unless given")
    void omittedColumnDefaultsToFalse() {
        jdbc.update("INSERT INTO app_user (username, password, role, enabled, created_at, "
                + "password_change_required) VALUES ('v205-omitted', 'hash', 'LITE_USER', TRUE, now(), FALSE)");

        Boolean optIn = jdbc.queryForObject(
                "SELECT marketing_email_opt_in FROM app_user WHERE username = 'v205-omitted'", Boolean.class);

        assertThat(optIn).isFalse();
    }

    @Test
    @DisplayName("an explicit TRUE is still stored as TRUE: only the default changed")
    void explicitTrueIsKept() {
        jdbc.update("INSERT INTO app_user (username, password, role, enabled, created_at, "
                + "password_change_required, marketing_email_opt_in) "
                + "VALUES ('v205-explicit', 'hash', 'LITE_USER', TRUE, now(), FALSE, TRUE)");

        Boolean optIn = jdbc.queryForObject(
                "SELECT marketing_email_opt_in FROM app_user WHERE username = 'v205-explicit'", Boolean.class);

        assertThat(optIn).isTrue();
    }
}
