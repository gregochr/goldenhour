package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AskLogRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.service.ask.AskLog;
import com.gregochr.goldenhour.service.ask.AskLogCleanupJob;
import com.gregochr.goldenhour.service.ask.DatabaseAskLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V167 against a real Postgres (Flyway runs every migration in {@link IntegrationTestBase}):
 * the {@code ask_log} columns, the check constraint that makes "a question is kept only when the engine
 * answered" a property of the table, <b>{@code ON DELETE SET NULL}</b> (deleting a user that has log
 * rows must succeed, keep the rows and drop the link), the seeded cleanup job and its free cron slot,
 * and the real {@link DatabaseAskLog} and {@link AskLogCleanupJob} beans on the production dialect.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md), so the migration is proven before merge only by CI's Backend job. The same store and
 * retention logic is proven locally on H2 by {@code AskLogPersistenceTest}.
 */
class AskLogMigrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private AskLogRepository logs;
    @Autowired
    private SchedulerJobConfigRepository schedulerJobConfigRepository;
    @Autowired
    private DatabaseAskLog askLog;
    @Autowired
    private AskLogCleanupJob cleanupJob;
    @Autowired
    private Clock clock;

    private final List<Long> createdUsers = new ArrayList<>();

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM ask_log");
        users.deleteAllById(createdUsers);
    }

    private long newUser(String name) {
        AppUserEntity user = AppUserEntity.builder().username(name).email(name + "@example.com")
                .password("{bcrypt}hash").role(UserRole.LITE_USER).enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build();
        long id = users.save(user).getId();
        createdUsers.add(id);
        return id;
    }

    private static Map<String, Object> column(List<Map<String, Object>> columns, String name) {
        return columns.stream().filter(c -> name.equals(c.get("column_name"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("ask_log has the expected columns, types and nullability")
    void columnsExist() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type, character_maximum_length, is_nullable "
                        + "FROM information_schema.columns WHERE table_name = 'ask_log' ORDER BY column_name");

        assertThat(columns).extracting(c -> c.get("column_name")).containsExactly("created_at", "duration_ms",
                "id", "missing", "normalised_question", "outcome", "scope_key", "user_id", "view");
        assertThat(column(columns, "user_id")).containsEntry("is_nullable", "YES");
        assertThat(column(columns, "normalised_question")).containsEntry("is_nullable", "YES")
                .containsEntry("character_maximum_length", 200);
        assertThat(column(columns, "missing")).containsEntry("is_nullable", "YES")
                .containsEntry("character_maximum_length", 60);
        assertThat(column(columns, "created_at")).containsEntry("is_nullable", "NO")
                .containsEntry("data_type", "timestamp with time zone");
        for (String required : List.of("scope_key", "view", "outcome", "duration_ms")) {
            assertThat(column(columns, required)).as(required).containsEntry("is_nullable", "NO");
        }
    }

    @Test
    @DisplayName("the check constraint refuses a stored question on any outcome but CLAUDE_OK and CLAUDE_CANT")
    void questionOnlyWhenTheEngineAnswered() {
        for (String outcome : List.of("CLAUDE_OK", "CLAUDE_CANT")) {
            logs.insertRow(Instant.now(), null, "ALL", "map", outcome, "a question", null, 5L);
        }
        for (String outcome : List.of("READY_MATCH", "PREFILTER_CANT", "CACHE_HIT", "CLAUDE_FAILED")) {
            assertThatThrownBy(() -> logs.insertRow(Instant.now(), null, "ALL", "map", outcome, "a question",
                    null, 5L)).as(outcome).isInstanceOf(DataIntegrityViolationException.class);
            logs.insertRow(Instant.now(), null, "ALL", "map", outcome, null, null, 5L);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ask_log", Integer.class)).isEqualTo(6);
    }

    @Test
    @DisplayName("a log row for a user that does not exist is refused: the foreign key is real")
    void foreignKeyIsEnforced() {
        assertThatThrownBy(() -> logs.insertRow(Instant.now(), 987_654_321L, "ALL", "map", "CACHE_HIT", null,
                null, 5L)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("deleting a user that has log rows succeeds, keeps every row and nulls user_id (ON DELETE SET NULL)")
    void deletingAUserKeepsTheRowsAndNullsTheLink() {
        long user = newUser("log-set-null");
        long bystander = newUser("log-bystander");
        logs.insertRow(Instant.now(), user, "ALL", "map", "CLAUDE_OK", "a question", null, 5L);
        logs.insertRow(Instant.now(), user, "ALL", "map", "CACHE_HIT", null, null, 5L);
        logs.insertRow(Instant.now(), bystander, "ALL", "map", "CACHE_HIT", null, null, 5L);

        users.deleteById(user);
        createdUsers.remove(Long.valueOf(user));

        assertThat(users.findById(user)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ask_log", Integer.class)).as("nothing lost")
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ask_log WHERE user_id IS NULL", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ask_log WHERE user_id = ?", Integer.class,
                bystander)).as("another user's rows are untouched").isEqualTo(1);
    }

    @Test
    @DisplayName("V167 seeds the ask_log_cleanup job at 03:55 UTC, ACTIVE, and no other job shares that slot")
    void v167SeedsTheCleanupJob() {
        SchedulerJobConfigEntity row = schedulerJobConfigRepository.findByJobKey("ask_log_cleanup")
                .orElseThrow();

        assertThat(row.getScheduleType()).isEqualTo(ScheduleType.CRON);
        assertThat(row.getCronExpression()).isEqualTo("0 55 3 * * *");
        assertThat(row.getStatus()).isEqualTo(SchedulerJobStatus.ACTIVE);
        assertThat(row.getDisplayName()).isEqualTo("Ask Log Cleanup");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM scheduler_job_config WHERE cron_expression = ?",
                Integer.class, "0 55 3 * * *")).as("a free slot").isEqualTo(1);
    }

    @Test
    @DisplayName("the created_at index exists: it serves the nightly prune and the metrics window")
    void createdAtIsIndexed() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename = 'ask_log' "
                + "AND indexname = 'idx_ask_log_created_at'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("the real log bean writes through the production dialect: the question only for an engine "
            + "answer, capped, and missing only for a can't-answer")
    void theRealLogBeanWrites() {
        long user = newUser("log-bean");
        askLog.record(new AskLog.Entry(user, "ALL", "plan", AskLog.Outcome.CLAUDE_CANT, "q".repeat(250),
                "m".repeat(80), 40L));
        askLog.record(new AskLog.Entry(user, "ALL", "plan", AskLog.Outcome.READY_MATCH, "never kept",
                "never kept", 3L));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT outcome, normalised_question, missing, user_id FROM ask_log ORDER BY outcome");
        assertThat(rows).hasSize(2);
        Map<String, Object> cant = rows.get(0);
        assertThat(cant).containsEntry("outcome", "CLAUDE_CANT").containsEntry("user_id", user);
        assertThat(String.valueOf(cant.get("normalised_question"))).hasSize(200);
        assertThat(String.valueOf(cant.get("missing"))).hasSize(60);
        Map<String, Object> ready = rows.get(1);
        assertThat(ready.get("normalised_question")).isNull();
        assertThat(ready.get("missing")).isNull();
    }

    @Test
    @DisplayName("the real cleanup job deletes rows older than 90 days and keeps the 90-day-old boundary row")
    void theRealCleanupJobPrunes() {
        Instant now = clock.instant();
        logs.insertRow(now.minus(Duration.ofDays(91)), null, "ALL", "map", "CACHE_HIT", null, null, 1L);
        logs.insertRow(now.minus(Duration.ofDays(89)), null, "ALL", "map", "CACHE_HIT", null, null, 1L);
        logs.insertRow(now, null, "ALL", "map", "CACHE_HIT", null, null, 1L);

        int deleted = cleanupJob.prune();

        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ask_log", Integer.class)).isEqualTo(2);
    }
}
