package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
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

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'v190-%'");
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

    private int sent(long id) {
        return jdbc.queryForObject("SELECT verification_reminders_sent FROM app_user WHERE id = ?",
                Integer.class, id);
    }

    private Object finalAt(long id) {
        return jdbc.queryForObject("SELECT final_reminder_sent_at FROM app_user WHERE id = ?", Timestamp.class, id);
    }
}
