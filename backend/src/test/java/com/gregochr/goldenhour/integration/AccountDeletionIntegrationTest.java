package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.StaleCredentialsException;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.service.CredentialChangeWriter.VersionExpectation;
import com.gregochr.goldenhour.service.DeletedAccount;
import com.gregochr.goldenhour.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves account deletion against a real Postgres built by Flyway (login-registration plan §1 #2, §2 #7),
 * the one place the foreign keys exist as production has them.
 *
 * <p>⚠️ The H2 test profile cannot show this: it is {@code ddl-auto: create-drop} and
 * {@code UserDriveTimeEntity} maps {@code userId} as a plain {@code Long}, so its schema has NO foreign key
 * from {@code user_drive_time} to {@code app_user}. V67 created that key with no {@code ON DELETE} rule, so
 * on Postgres deleting a user who holds drive times is refused — which is why both deletion paths purge the
 * rows first. The account used here holds a row in each table that references {@code app_user} except the
 * cascading verification-token table: drive times (no rule), Ask usage and an outcome rating (cascade), a
 * refresh token and a password-reset token (cascade) and an {@code ask_log} row (set null).
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md), so this is proven before merge only by CI's Backend job. The service logic is proven locally on
 * H2 by {@code UserServiceTest} and {@code UserServiceDeletionTest}.
 */
class AccountDeletionIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private UserService userService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final List<Long> createdUsers = new ArrayList<>();
    private final List<Long> createdLocations = new ArrayList<>();

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM ask_log");
        for (Long id : createdUsers) {
            jdbc.update("DELETE FROM user_drive_time WHERE user_id = ?", id);
            jdbc.update("DELETE FROM app_user WHERE id = ?", id);
        }
        for (Long id : createdLocations) {
            jdbc.update("DELETE FROM locations WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM app_user_email_claim");
    }

    private long newUser(String name, UserRole role, String rawPassword) {
        AppUserEntity user = AppUserEntity.builder().username(name).email(name + "@example.com")
                .password(passwordEncoder.encode(rawPassword)).role(role).enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build();
        long id = users.save(user).getId();
        createdUsers.add(id);
        jdbc.update("INSERT INTO app_user_email_claim (email_lower, claimed_at) VALUES (?, now())",
                name + "@example.com");
        return id;
    }

    private long newLocation(String name) {
        Long id = jdbc.queryForObject("INSERT INTO locations (name, lat, lon) VALUES (?, 55.0, -1.5) RETURNING id",
                Long.class, name);
        createdLocations.add(id);
        return id;
    }

    /** Gives the user a row in every table that references {@code app_user}. */
    private void populate(long userId, long locationId) {
        jdbc.update("INSERT INTO user_drive_time (user_id, location_id, drive_duration_seconds) VALUES (?, ?, 900)",
                userId, locationId);
        jdbc.update("INSERT INTO ask_usage (user_id, usage_date, used, engine_calls) VALUES (?, CURRENT_DATE, 1, 1)",
                userId);
        jdbc.update("INSERT INTO outcome_rating (user_id, location_id, event_date, event_type, rating, event_time)"
                + " VALUES (?, ?, CURRENT_DATE, 'SUNRISE', 4, now())", userId, locationId);
        jdbc.update("INSERT INTO refresh_token (user_id, token_hash, expires_at, revoked)"
                + " VALUES (?, ?, now() + interval '30 days', false)", userId, "hash-" + userId);
        jdbc.update("INSERT INTO password_reset_token (user_id, token_hash, expires_at, created_at)"
                + " VALUES (?, ?, now() + interval '1 hour', now())", userId, "reset-" + userId);
        jdbc.update("INSERT INTO ask_log (user_id, scope_key, view, outcome, duration_ms)"
                + " VALUES (?, 'ALL', 'plan', 'READY_MATCH', 12)", userId);
        // V192: a sub-region pick (V191 seeds the sub-regions). Cascades with the account; no purge needed.
        jdbc.update("INSERT INTO app_user_sub_region (user_id, area_id, created_at)"
                + " SELECT ?, id, now() FROM area WHERE kind = 'SUB_REGION' ORDER BY id LIMIT 1", userId);
    }

    private int count(String table, long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
    }

    @Test
    @DisplayName("WITHOUT the purge a delete of a user holding drive times is refused by the foreign key — "
            + "the defect the purge exists for")
    void deleteWithoutThePurge_isRefusedByTheDriveTimeForeignKey() {
        long id = newUser("fkreader", UserRole.PRO_USER, "Correct1!");
        populate(id, newLocation("FK Hill"));

        assertThatThrownBy(() -> jdbc.update("DELETE FROM app_user WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("user_drive_time", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("the own-account path purges the drive times and deletes the account: cascades fire, the "
            + "ask_log row SURVIVES with a null user, the email claim is released")
    void ownAccount_isDeleted_cascadesFireAndAskLogSurvives() {
        long id = newUser("selfdelete", UserRole.PRO_USER, "Correct1!");
        populate(id, newLocation("Self Hill"));
        String hash = userService.verifyPassword("selfdelete", "Correct1!").orElseThrow();

        DeletedAccount deleted = userService.deleteOwnAccount("selfdelete", hash, VersionExpectation.exact(0));

        assertThat(deleted).isEqualTo(new DeletedAccount("selfdelete", "selfdelete@example.com"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id = ?", Integer.class, id)).isZero();
        assertThat(count("user_drive_time", id)).isZero();
        assertThat(count("ask_usage", id)).isZero();
        assertThat(count("outcome_rating", id)).isZero();
        assertThat(count("refresh_token", id)).isZero();
        assertThat(count("password_reset_token", id)).isZero();
        assertThat(count("app_user_sub_region", id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ask_log WHERE user_id IS NULL "
                + "AND scope_key = 'ALL' AND outcome = 'READY_MATCH'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user_email_claim WHERE email_lower = ?",
                Integer.class, "selfdelete@example.com")).isZero();
    }

    @Test
    @DisplayName("the admin path purges and deletes the same way")
    void adminPath_isDeleted_likewise() {
        long boss = newUser("theboss", UserRole.ADMIN, "Correct1!");
        long victim = newUser("victim", UserRole.LITE_USER, "Correct1!");
        populate(victim, newLocation("Admin Hill"));

        DeletedAccount deleted = userService.deleteUser(victim, "theboss");

        assertThat(deleted).isEqualTo(new DeletedAccount("victim", "victim@example.com"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id = ?", Integer.class, victim))
                .isZero();
        assertThat(count("user_drive_time", victim)).isZero();
        assertThat(count("app_user_sub_region", victim)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id = ?", Integer.class, boss))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a stale session (the credential version moved) deletes nothing and purges nothing: the "
            + "refusal comes under the row lock, before the purge, and every row is still there")
    void staleSession_deletesNothingAndKeepsTheDriveTimes() {
        long id = newUser("stalereader", UserRole.PRO_USER, "Correct1!");
        populate(id, newLocation("Stale Hill"));
        String hash = userService.verifyPassword("stalereader", "Correct1!").orElseThrow();
        jdbc.update("UPDATE app_user SET credentials_version = 1 WHERE id = ?", id);

        assertThatThrownBy(() -> userService.deleteOwnAccount("stalereader", hash, VersionExpectation.exact(0)))
                .isInstanceOf(StaleCredentialsException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(count("user_drive_time", id)).isEqualTo(1);
        assertThat(count("ask_usage", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("an ADMIN cannot delete their own account through the own-account path, and keeps every row")
    void admin_cannotDeleteThemselves() {
        long id = newUser("soleadmin", UserRole.ADMIN, "Correct1!");
        populate(id, newLocation("Admin Self Hill"));
        String hash = userService.verifyPassword("soleadmin", "Correct1!").orElseThrow();

        assertThatThrownBy(() -> userService.deleteOwnAccount("soleadmin", hash, VersionExpectation.exact(0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Admin accounts are deleted by another admin");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(count("user_drive_time", id)).isEqualTo(1);
    }
}
