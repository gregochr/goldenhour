package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.repository.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    /** The migration's backfill statement, read from the migration file itself so the test cannot drift from it. */
    private String backfillSql() throws IOException {
        String file = new ClassPathResource("db/migration/V230__app_user_places_hint_dismissed.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        int start = file.indexOf("UPDATE app_user");
        assertThat(start).as("V230 carries the backfill").isGreaterThanOrEqualTo(0);
        return file.substring(start, file.indexOf(';', start));
    }

    @Test
    @DisplayName("the backfill stamps every reader holding a favourite row (a date here) and leaves the others null")
    void backfillStampsOnlyReadersWithAFavourite() throws IOException {
        jdbc.update("INSERT INTO app_user (username, password, role, enabled, created_at, "
                + "password_change_required, marketing_email_opt_in) "
                + "VALUES ('v230-saved', 'hash', 'PRO_USER', TRUE, now(), FALSE, TRUE)");
        jdbc.update("INSERT INTO app_user (username, password, role, enabled, created_at, "
                + "password_change_required, marketing_email_opt_in) "
                + "VALUES ('v230-never', 'hash', 'PRO_USER', TRUE, now(), FALSE, TRUE)");
        Long saved = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'v230-saved'", Long.class);
        jdbc.update("INSERT INTO user_favourite (user_id, almanac_key, created_at) "
                + "VALUES (?, 'king-tide:2026-12-03', now())", saved);

        // Run on rows as they stood before the migration (the column empty), exactly as Flyway ran it.
        jdbc.update("UPDATE app_user SET places_hint_dismissed_at = NULL WHERE username LIKE 'v230-%'");
        jdbc.update(backfillSql());

        assertThat(users.findByUsername("v230-saved").orElseThrow().getPlacesHintDismissedAt()).isNotNull();
        assertThat(users.findByUsername("v230-never").orElseThrow().getPlacesHintDismissedAt()).isNull();
        jdbc.update("DELETE FROM user_favourite WHERE user_id = ?", saved);
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
