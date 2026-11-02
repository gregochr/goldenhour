package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.SchedulerFireLogEntity;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.entity.SchedulerFireTrigger;
import com.gregochr.goldenhour.repository.SchedulerFireLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V215 against a real Postgres (Flyway runs every migration in {@link IntegrationTestBase}): the
 * {@code scheduler_fire_log} table and its two indexes, an insert plus the scoped completion round-trip, the
 * {@code schedule_changed_at} column, the seeded {@code scheduler_fire_log_cleanup} job with its cron, and
 * the job count (28 after V215).
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md), so the migration is proven before merge only by CI's Backend job.
 */
class SchedulerFireLogMigrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SchedulerFireLogRepository fireLog;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM scheduler_fire_log");
    }

    @Test
    @DisplayName("V215 creates scheduler_fire_log with the planned columns")
    void tableHasThePlannedColumns() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type, character_maximum_length, is_nullable "
                        + "FROM information_schema.columns WHERE table_name = 'scheduler_fire_log'");

        assertThat(columns).extracting(c -> c.get("column_name")).containsExactlyInAnyOrder(
                "id", "job_key", "fired_at", "completed_at", "status", "summary", "trigger",
                "pipeline_run_id");
        assertThat(column(columns, "job_key")).containsEntry("character_maximum_length", 50)
                .containsEntry("is_nullable", "NO");
        assertThat(column(columns, "fired_at")).containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "NO");
        assertThat(column(columns, "completed_at")).containsEntry("is_nullable", "YES");
        assertThat(column(columns, "status")).containsEntry("character_maximum_length", 12)
                .containsEntry("is_nullable", "NO");
        assertThat(column(columns, "summary")).containsEntry("character_maximum_length", 200)
                .containsEntry("is_nullable", "YES");
        assertThat(column(columns, "trigger")).containsEntry("character_maximum_length", 12)
                .containsEntry("is_nullable", "NO");
        assertThat(column(columns, "pipeline_run_id")).containsEntry("data_type", "bigint")
                .containsEntry("is_nullable", "YES");
    }

    @Test
    @DisplayName("V215 creates the (job_key, fired_at DESC) and (fired_at) indexes")
    void indexesExist() {
        List<String> definitions = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'scheduler_fire_log'", String.class);

        assertThat(definitions).anyMatch(d -> d.contains("(job_key, fired_at DESC)"));
        assertThat(definitions).anyMatch(d -> d.contains("(fired_at)"));
    }

    @Test
    @DisplayName("a RUNNING row inserted through the entity is completed by the scoped update")
    void insertThenScopedCompleteRoundTrips() {
        SchedulerFireLogEntity row = new SchedulerFireLogEntity();
        row.setJobKey("tide_refresh");
        row.setFiredAt(Instant.parse("2026-11-02T10:00:00Z"));
        row.setStatus(SchedulerFireStatus.RUNNING);
        row.setTriggerKind(SchedulerFireTrigger.MANUAL);
        long id = fireLog.save(row).getId();

        int updated = fireLog.complete(id, Instant.parse("2026-11-02T10:00:09Z"), SchedulerFireStatus.PARTIAL,
                "2 of 3", 77L);

        assertThat(updated).isEqualTo(1);
        Map<String, Object> stored = jdbc.queryForMap("SELECT * FROM scheduler_fire_log WHERE id = ?", id);
        assertThat(stored).containsEntry("job_key", "tide_refresh")
                .containsEntry("status", "PARTIAL")
                .containsEntry("summary", "2 of 3")
                .containsEntry("trigger", "MANUAL")
                .containsEntry("pipeline_run_id", 77L);
        assertThat(stored.get("completed_at")).isNotNull();
    }

    @Test
    @DisplayName("V215 adds a nullable schedule_changed_at timestamptz to scheduler_job_config")
    void scheduleChangedAtColumnExists() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'scheduler_job_config' AND column_name = 'schedule_changed_at'");

        assertThat(columns).hasSize(1);
        assertThat(columns.get(0)).containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "YES");
    }

    @Test
    @DisplayName("V215 seeds the scheduler_fire_log_cleanup job at 03:57 UTC, ACTIVE")
    void cleanupJobIsSeeded() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT display_name, schedule_type, cron_expression, status, time_zone "
                        + "FROM scheduler_job_config WHERE job_key = 'scheduler_fire_log_cleanup'");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("display_name", "Scheduler Fire Log Cleanup")
                .containsEntry("schedule_type", "CRON")
                .containsEntry("cron_expression", "0 57 3 * * *")
                .containsEntry("status", "ACTIVE")
                .containsEntry("time_zone", null);
    }

    @Test
    @DisplayName("after V215 there are 28 scheduler jobs")
    void jobCountIsTwentyEight() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM scheduler_job_config", Integer.class);

        assertThat(count).isEqualTo(28);
    }

    private static Map<String, Object> column(List<Map<String, Object>> columns, String name) {
        return columns.stream().filter(c -> name.equals(c.get("column_name"))).findFirst().orElseThrow();
    }
}
