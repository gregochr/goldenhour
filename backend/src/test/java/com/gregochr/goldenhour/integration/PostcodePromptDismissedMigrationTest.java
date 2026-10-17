package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.repository.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V181 ({@code app_user.postcode_prompt_dismissed_at}) against a real Postgres (Flyway runs
 * every migration in {@link IntegrationTestBase}): the column's type and nullability, that a row
 * inserted without it — every account that existed before V181 — reads NULL ("never dismissed", so the
 * one-time prompt is due for an existing account without a postcode), and that the column-scoped update
 * writes it and overwrites it on the production dialect.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md), so the migration is proven before merge only by CI's Backend job. The same update
 * runs locally on H2 in {@code AppUserRepositoryPostcodePromptTest}.
 */
class PostcodePromptDismissedMigrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AppUserRepository users;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'v181-%'");
    }

    @Test
    @DisplayName("the column is a nullable timestamp with time zone with no default")
    void columnExists() {
        Map<String, Object> column = jdbc.queryForMap(
                "SELECT data_type, is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_name = 'app_user' AND column_name = 'postcode_prompt_dismissed_at'");

        assertThat(column)
                .containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "YES")
                .containsEntry("column_default", null);
    }

    @Test
    @DisplayName("a row inserted WITHOUT the column (every pre-V181 account) reads null: never dismissed")
    void legacyRowReadsNull() {
        jdbc.update("INSERT INTO app_user (username, password, role, enabled, created_at, "
                + "password_change_required, marketing_email_opt_in) "
                + "VALUES ('v181-legacy', 'hash', 'LITE_USER', TRUE, now(), FALSE, TRUE)");

        AppUserEntity user = users.findByUsername("v181-legacy").orElseThrow();

        assertThat(user.getPostcodePromptDismissedAt()).isNull();
        assertThat(user.getHomePostcode()).isNull();
    }

    @Test
    @DisplayName("the scoped update writes the instant, a second call overwrites it, and an unknown "
            + "username updates nothing")
    void scopedUpdateOnPostgres() {
        jdbc.update("INSERT INTO app_user (username, password, role, enabled, created_at, "
                + "password_change_required, marketing_email_opt_in) "
                + "VALUES ('v181-dismiss', 'hash', 'PRO_USER', TRUE, now(), FALSE, TRUE)");
        Instant first = Instant.parse("2026-10-17T08:00:00Z");
        Instant second = Instant.parse("2026-10-18T09:30:00Z");

        assertThat(users.updatePostcodePromptDismissedAtByUsername("v181-nobody", first)).isZero();
        assertThat(users.updatePostcodePromptDismissedAtByUsername("v181-dismiss", first)).isEqualTo(1);
        assertThat(users.findByUsername("v181-dismiss").orElseThrow().getPostcodePromptDismissedAt())
                .isEqualTo(first);

        assertThat(users.updatePostcodePromptDismissedAtByUsername("v181-dismiss", second)).isEqualTo(1);
        assertThat(users.findByUsername("v181-dismiss").orElseThrow().getPostcodePromptDismissedAt())
                .isEqualTo(second);
    }
}
