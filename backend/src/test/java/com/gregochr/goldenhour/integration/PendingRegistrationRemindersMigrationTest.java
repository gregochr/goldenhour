package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V190 against a real Postgres (Flyway runs every migration in {@link IntegrationTestBase}): the two
 * {@code app_user} columns' types, nullability and default, the default taking effect for a row inserted
 * without them, the entity mapping passing {@code ddl-auto: validate}, the seeded hourly scheduler row, and the
 * backfill {@code UPDATE} (re-run here against freshly inserted rows, because Flyway ran it against an empty
 * table): every pending non-admin account is marked as already reminded at {@code created_at + 144 h}, and
 * active and admin rows are untouched.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md), so the migration is proven before merge only by CI's Backend job.
 */
class PendingRegistrationRemindersMigrationTest extends IntegrationTestBase {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 1, 9, 30);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private UserService userService;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM user_drive_time WHERE user_id IN "
                + "(SELECT id FROM app_user WHERE username LIKE 'v190-%')");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'v190-%'");
        jdbc.update("DELETE FROM app_user_email_claim WHERE email_lower LIKE 'v190-%'");
        jdbc.update("DELETE FROM locations WHERE name = 'v190-location'");
    }

    private long user(String name, boolean enabled, String password, UserRole role) {
        return users.save(AppUserEntity.builder().username(name).email(name + "@example.com").password(password)
                .role(role).enabled(enabled).createdAt(CREATED).build()).getId();
    }

    private static Map<String, Object> column(List<Map<String, Object>> columns, String name) {
        return columns.stream().filter(c -> name.equals(c.get("column_name"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the two columns exist: an INTEGER NOT NULL default 0, and a nullable zone-less TIMESTAMP")
    void columnsExist() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_name = 'app_user' AND column_name IN "
                        + "('verification_reminders_sent', 'final_reminder_sent_at')");

        assertThat(column(columns, "verification_reminders_sent"))
                .containsEntry("data_type", "integer")
                .containsEntry("is_nullable", "NO");
        assertThat(String.valueOf(column(columns, "verification_reminders_sent").get("column_default")))
                .isEqualTo("0");
        assertThat(column(columns, "final_reminder_sent_at"))
                .containsEntry("data_type", "timestamp without time zone")
                .containsEntry("is_nullable", "YES");
    }

    @Test
    @DisplayName("a row saved through the entity (which omits both columns) reads 0 and NULL, and the "
            + "entity mapping passes schema validation")
    void entityInsertTakesTheDefaults() {
        long id = user("v190-new", false, "", UserRole.LITE_USER);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT verification_reminders_sent, final_reminder_sent_at FROM app_user WHERE id = ?", id);

        assertThat(((Number) row.get("verification_reminders_sent")).intValue()).isZero();
        assertThat(row.get("final_reminder_sent_at")).isNull();
        assertThat(users.findPendingStage(id).orElseThrow().getRemindersSent()).isZero();
    }

    @Test
    @DisplayName("the backfill marks pending rows sent=2 with created_at + 144 h; active and admin rows keep "
            + "0 and NULL")
    void backfillMarksOnlyPendingNonAdminRows() throws Exception {
        long pending = user("v190-pending", false, "", UserRole.LITE_USER);
        long active = user("v190-active", true, "a-hash", UserRole.LITE_USER);
        long admin = user("v190-admin", false, "", UserRole.ADMIN);
        long adminActive = user("v190-admin2", true, "a-hash", UserRole.ADMIN);

        String sql = new String(new ClassPathResource("db/migration/V190__pending_registration_reminders.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String update = sql.substring(sql.indexOf("UPDATE app_user"), sql.indexOf(";", sql.indexOf("UPDATE app_user")));
        jdbc.update(update);

        assertThat(sent(pending)).isEqualTo(2);
        assertThat(finalAt(pending)).isEqualTo(Timestamp.valueOf(CREATED.plusHours(144)));
        for (long untouched : new long[] {active, admin, adminActive}) {
            assertThat(sent(untouched)).isZero();
            assertThat(finalAt(untouched)).isNull();
        }
    }

    @Test
    @DisplayName("V190 seeds exactly one pending_registration_sweep row: CRON hourly at :20, ACTIVE")
    void seedsTheSweepJob() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT schedule_type, cron_expression, status FROM scheduler_job_config "
                        + "WHERE job_key = 'pending_registration_sweep'");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("schedule_type", "CRON")
                .containsEntry("cron_expression", "0 20 * * * *")
                .containsEntry("status", "ACTIVE");
    }

    @Test
    @DisplayName("advanceReminderStage on Postgres: next=1 leaves the final stamp NULL, next=2 stamps the passed "
            + "now, a mismatched expected stage changes 0 rows, and revert restores both columns")
    void advanceAndRevertOnPostgres() {
        long id = user("v190-cas", false, "", UserRole.LITE_USER);
        LocalDateTime now = LocalDateTime.of(2026, 10, 24, 12, 0);

        assertThat(users.advanceReminderStage(id, 1, 2, now)).isZero();
        assertThat(sent(id)).isZero();

        assertThat(users.advanceReminderStage(id, 0, 1, now)).isEqualTo(1);
        assertThat(sent(id)).isEqualTo(1);
        assertThat(finalAt(id)).isNull();

        assertThat(users.advanceReminderStage(id, 1, 2, now)).isEqualTo(1);
        assertThat(sent(id)).isEqualTo(2);
        assertThat(finalAt(id)).isEqualTo(Timestamp.valueOf(now));

        assertThat(users.revertReminderStage(id, 1, 2, null)).isEqualTo(1);
        assertThat(sent(id)).isEqualTo(1);
        assertThat(finalAt(id)).isNull();

        assertThat(users.revertReminderStage(id, 0, 1, null)).isEqualTo(1);
        assertThat(sent(id)).isZero();
        assertThat(finalAt(id)).isNull();
    }

    @Test
    @DisplayName("purgeAbandonedPending on Postgres removes the user, its expired token (FK cascade) and its "
            + "drive time, releases the email claim, and returns false while an unexpired token exists")
    void purgeOnPostgres() {
        long id = user("v190-purge", false, "", UserRole.LITE_USER);
        Long location = jdbc.queryForObject(
                "INSERT INTO locations (name, lat, lon) VALUES ('v190-location', 55.0, -1.5) RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO user_drive_time (user_id, location_id, drive_duration_seconds) VALUES (?, ?, 900)",
                id, location);
        jdbc.update("INSERT INTO app_user_email_claim (email_lower, claimed_at) VALUES (?, now())",
                "v190-purge@example.com");
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO email_verification_token (token_hash, user_id, expires_at, verified, created_at)"
                + " VALUES ('v190-live', ?, ?, false, ?)", id, Timestamp.valueOf(now.plusHours(2)),
                Timestamp.valueOf(now.minusHours(22)));

        assertThat(userService.purgeAbandonedPending(id, now)).isFalse();
        assertThat(rows("app_user", "id = ?", id)).isEqualTo(1);

        jdbc.update("UPDATE email_verification_token SET expires_at = ? WHERE token_hash = 'v190-live'",
                Timestamp.valueOf(now.minusHours(1)));

        assertThat(userService.purgeAbandonedPending(id, now)).isTrue();
        assertThat(rows("app_user", "id = ?", id)).isZero();
        assertThat(rows("email_verification_token", "user_id = ?", id)).isZero();
        assertThat(rows("user_drive_time", "user_id = ?", id)).isZero();
        assertThat(rows("app_user_email_claim", "email_lower = ?", "v190-purge@example.com")).isZero();
    }

    private int rows(String table, String where, Object arg) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class, arg);
    }

    private int sent(long id) {
        return jdbc.queryForObject("SELECT verification_reminders_sent FROM app_user WHERE id = ?",
                Integer.class, id);
    }

    private Object finalAt(long id) {
        return jdbc.queryForObject("SELECT final_reminder_sent_at FROM app_user WHERE id = ?", Timestamp.class, id);
    }
}
