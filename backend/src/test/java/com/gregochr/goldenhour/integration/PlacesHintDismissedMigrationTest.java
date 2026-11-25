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
 * Proves V230 ({@code app_user.places_hint_dismissed_at}) against a real Postgres (Flyway runs every
 * migration in {@link IntegrationTestBase}): the column's type and nullability, that a row inserted
 * without it (every account that existed before V230) reads NULL ("the hint is still due"), and that
 * the column-scoped update stamps it once and never overwrites it, on the production dialect.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md). The same update runs locally on H2 in {@code AppUserRepositoryPlacesHintTest}.
 */
class PlacesHintDismissedMigrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AppUserRepository users;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'v230-%'");
    }

    @Test
    @DisplayName("the column is a nullable timestamp with time zone with no default")
    void columnExists() {
        Map<String, Object> column = jdbc.queryForMap(
                "SELECT data_type, is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_name = 'app_user' AND column_name = 'places_hint_dismissed_at'");

        assertThat(column)
                .containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "YES")
                .containsEntry("column_default", null);
    }

    @Test
    @DisplayName("a row inserted WITHOUT the column (every pre-V230 account) reads null: the hint is due")
    void legacyRowReadsNull() {
        jdbc.update("INSERT INTO app_user (username, password, role, enabled, created_at, "
                + "password_change_required, marketing_email_opt_in) "
                + "VALUES ('v230-legacy', 'hash', 'LITE_USER', TRUE, now(), FALSE, TRUE)");

        AppUserEntity user = users.findByUsername("v230-legacy").orElseThrow();

        assertThat(user.getPlacesHintDismissedAt()).isNull();
    }

    @Test
    @DisplayName("the scoped update stamps the instant once, never overwrites it, and an unknown "
            + "username updates nothing")
    void scopedUpdateOnPostgres() {
        jdbc.update("INSERT INTO app_user (username, password, role, enabled, created_at, "
                + "password_change_required, marketing_email_opt_in) "
                + "VALUES ('v230-stamp', 'hash', 'PRO_USER', TRUE, now(), FALSE, TRUE)");
        Instant first = Instant.parse("2026-11-25T08:00:00Z");
        Instant second = Instant.parse("2026-11-26T09:30:00Z");

        assertThat(users.updatePlacesHintDismissedAtByUsernameIfNull("v230-nobody", first)).isZero();
        assertThat(users.updatePlacesHintDismissedAtByUsernameIfNull("v230-stamp", first)).isEqualTo(1);
        assertThat(users.findByUsername("v230-stamp").orElseThrow().getPlacesHintDismissedAt())
                .isEqualTo(first);

        assertThat(users.updatePlacesHintDismissedAtByUsernameIfNull("v230-stamp", second)).isZero();
        assertThat(users.findByUsername("v230-stamp").orElseThrow().getPlacesHintDismissedAt())
                .isEqualTo(first);
    }
}
