package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V193 against a real Postgres (Flyway runs every migration in {@link IntegrationTestBase}): the
 * {@code dormancy_warning_sent_at} column's type and nullability, the entity mapping passing
 * {@code ddl-auto: validate}, the seeded {@code dormant_account_sweep} row (daily, and PAUSED), and the sweep's
 * native locked read, conditional stamp and conditional delete on Postgres (where the timestamp types differ
 * from H2).
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md), so the migration is proven before merge only by CI's Backend job.
 */
class DormantAccountSweepMigrationTest extends IntegrationTestBase {

    private static final LocalDateTime NOW = LocalDateTime.of(2031, 6, 30, 10, 0);
    private static final LocalDateTime INACTIVE_CUTOFF = NOW.minusDays(90);
    private static final LocalDateTime WARN_CUTOFF = NOW.minusDays(14);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private UserService userService;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'v193-%'");
        jdbc.update("DELETE FROM app_user_email_claim WHERE email_lower LIKE 'v193-%'");
    }

    private long user(String name, UserRole role) {
        return users.save(AppUserEntity.builder().username(name).email(name + "@example.com").password("a-hash")
                .role(role).enabled(true).createdAt(NOW.minusDays(300)).lastActiveAt(NOW.minusDays(100)).build())
                .getId();
    }

    private LocalDateTime stampOf(long id) {
        Timestamp stamp = jdbc.queryForObject("SELECT dormancy_warning_sent_at FROM app_user WHERE id = ?",
                Timestamp.class, id);
        return stamp == null ? null : stamp.toLocalDateTime();
    }

    @Test
    @DisplayName("dormancy_warning_sent_at is a nullable zone-less TIMESTAMP, NULL for a row saved through the entity")
    void columnExists() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'app_user' AND column_name = 'dormancy_warning_sent_at'");

        assertThat(columns).hasSize(1);
        assertThat(columns.get(0)).containsEntry("data_type", "timestamp without time zone")
                .containsEntry("is_nullable", "YES");
        assertThat(stampOf(user("v193-new", UserRole.LITE_USER))).isNull();
    }

    @Test
    @DisplayName("V193 seeds exactly one dormant_account_sweep row: CRON daily at 10:15, PAUSED")
    void seedsThePausedJob() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT schedule_type, cron_expression, status FROM scheduler_job_config "
                        + "WHERE job_key = 'dormant_account_sweep'");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("schedule_type", "CRON")
                .containsEntry("cron_expression", "0 15 10 * * *")
                .containsEntry("status", "PAUSED");
    }

    @Test
    @DisplayName("on Postgres the stamp lands once, the locked read returns the state, and the purge deletes only "
            + "the warned dormant Lite account")
    void stampAndPurgeOnPostgres() {
        long lite = user("v193-lite", UserRole.LITE_USER);
        long pro = user("v193-pro", UserRole.PRO_USER);

        assertThat(users.stampDormancyWarning(lite, NOW.minusDays(20), UserRole.LITE_USER, INACTIVE_CUTOFF))
                .isEqualTo(1);
        assertThat(users.stampDormancyWarning(lite, NOW, UserRole.LITE_USER, INACTIVE_CUTOFF)).isZero();
        assertThat(users.stampDormancyWarning(pro, NOW.minusDays(20), UserRole.LITE_USER, INACTIVE_CUTOFF)).isZero();
        jdbc.update("UPDATE app_user SET dormancy_warning_sent_at = ? WHERE id = ?",
                Timestamp.valueOf(NOW.minusDays(20)), pro);

        AppUserRepository.DormancyState state = users.lockDormancyState(lite).orElseThrow();
        assertThat(state.role()).isEqualTo(UserRole.LITE_USER);
        assertThat(state.warningSentAt()).isEqualTo(NOW.minusDays(20));

        assertThat(userService.purgeDormant(pro, INACTIVE_CUTOFF, WARN_CUTOFF)).isEmpty();
        assertThat(userService.purgeDormant(lite, INACTIVE_CUTOFF, WARN_CUTOFF)).isPresent();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id = ?", Integer.class, lite)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id = ?", Integer.class, pro))
                .isEqualTo(1);
    }
}
