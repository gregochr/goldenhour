package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.SchedulerFireLogEntity;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.entity.SchedulerFireTrigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the scoped queries of {@link SchedulerFireLogRepository} and the scoped writes added to
 * {@link SchedulerJobConfigRepository} on H2. Every assertion reads through {@link JdbcTemplate}, never the
 * persistence context, so a write the entity would have ignored cannot hide.
 */
@DataJpaTest
class SchedulerFireLogRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-11-02T10:00:00Z");

    @Autowired
    private SchedulerFireLogRepository fireLog;

    @Autowired
    private SchedulerJobConfigRepository configs;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private long insertRunning(String key, Instant firedAt) {
        SchedulerFireLogEntity row = new SchedulerFireLogEntity();
        row.setJobKey(key);
        row.setFiredAt(firedAt);
        row.setStatus(SchedulerFireStatus.RUNNING);
        row.setTriggerKind(SchedulerFireTrigger.SCHEDULED);
        long id = fireLog.saveAndFlush(row).getId();
        entityManager.clear();
        return id;
    }

    private Map<String, Object> stored(long id) {
        return jdbc.queryForMap("SELECT * FROM scheduler_fire_log WHERE id = ?", id);
    }

    @Test
    @DisplayName("complete writes the four outcome columns and leaves job_key, fired_at and trigger alone")
    void complete_writesOnlyTheOutcome() {
        long id = insertRunning("tide_refresh", T0);

        int updated = fireLog.complete(id, T0.plusSeconds(5), SchedulerFireStatus.PARTIAL, "2 of 3", 41L);

        assertThat(updated).isEqualTo(1);
        Map<String, Object> row = stored(id);
        assertThat(row.get("STATUS")).isEqualTo("PARTIAL");
        assertThat(row.get("SUMMARY")).isEqualTo("2 of 3");
        assertThat(row.get("PIPELINE_RUN_ID")).isEqualTo(41L);
        assertThat(row.get("COMPLETED_AT")).isNotNull();
        assertThat(row.get("JOB_KEY")).isEqualTo("tide_refresh");
        assertThat(row.get("TRIGGER")).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("deleteByFiredAtBefore deletes strictly older rows in one statement and keeps the boundary row")
    void deleteByFiredAtBefore_keepsTheBoundary() {
        insertRunning("a", T0.minusSeconds(1));
        insertRunning("b", T0);
        insertRunning("c", T0.plusSeconds(1));

        int deleted = fireLog.deleteByFiredAtBefore(T0);

        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT job_key FROM scheduler_fire_log ORDER BY job_key", String.class))
                .containsExactly("b", "c");
    }

    @Test
    @DisplayName("markOrphans fails only RUNNING rows older than the cutoff")
    void markOrphans_failsOnlyOldRunningRows() {
        long oldRunning = insertRunning("old_running", T0.minusSeconds(60));
        long newRunning = insertRunning("new_running", T0.plusSeconds(60));
        long oldDone = insertRunning("old_done", T0.minusSeconds(120));
        fireLog.complete(oldDone, T0, SchedulerFireStatus.SUCCEEDED, null, null);

        int marked = fireLog.markOrphans(T0, "Interrupted by a restart");

        assertThat(marked).isEqualTo(1);
        assertThat(stored(oldRunning).get("STATUS")).isEqualTo("FAILED");
        assertThat(stored(oldRunning).get("SUMMARY")).isEqualTo("Interrupted by a restart");
        assertThat(stored(newRunning).get("STATUS")).isEqualTo("RUNNING");
        assertThat(stored(oldDone).get("STATUS")).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("the scoped config updates write last-fire, last-completion and schedule_changed_at by job key")
    void configScopedUpdates_writeByJobKey() {
        jdbc.update("INSERT INTO scheduler_job_config (job_key, display_name, schedule_type, cron_expression, "
                + "status, updated_at) VALUES ('j1', 'j1', 'CRON', '0 0 1 * * *', 'ACTIVE', CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO scheduler_job_config (job_key, display_name, schedule_type, cron_expression, "
                + "status, updated_at) VALUES ('j2', 'j2', 'CRON', '0 0 2 * * *', 'ACTIVE', CURRENT_TIMESTAMP)");

        assertThat(configs.updateLastFireTime("j1", T0)).isEqualTo(1);
        assertThat(configs.updateLastCompletionTime("j1", T0.plusSeconds(9))).isEqualTo(1);
        assertThat(configs.updateScheduleChangedAt("j1", T0.plusSeconds(20))).isEqualTo(1);
        assertThat(configs.updateLastFireTime("missing", T0)).isZero();

        Map<String, Object> j1 = jdbc.queryForMap("SELECT * FROM scheduler_job_config WHERE job_key = 'j1'");
        assertThat(j1.get("LAST_FIRE_TIME")).isNotNull();
        assertThat(j1.get("LAST_COMPLETION_TIME")).isNotNull();
        assertThat(j1.get("SCHEDULE_CHANGED_AT")).isNotNull();
        assertThat(j1.get("STATUS")).isEqualTo("ACTIVE");
        Map<String, Object> j2 = jdbc.queryForMap("SELECT * FROM scheduler_job_config WHERE job_key = 'j2'");
        assertThat(j2.get("LAST_FIRE_TIME")).isNull();
        assertThat(j2.get("SCHEDULE_CHANGED_AT")).isNull();
    }

    @Test
    @DisplayName("findNewestPerJob returns the newest row of each job, once")
    void findNewestPerJob_oneNewestRowPerJob() {
        Instant base = Instant.parse("2027-03-05T10:00:00Z");
        insertRunning("a_job", base);
        long newestA = insertRunning("a_job", base.plusSeconds(3600));
        long onlyB = insertRunning("b_job", base.plusSeconds(60));

        List<SchedulerFireLogEntity> newest = fireLog.findNewestPerJob();

        assertThat(newest).extracting(SchedulerFireLogEntity::getId).containsExactlyInAnyOrder(newestA, onlyB);
        assertThat(newest).extracting(SchedulerFireLogEntity::getFiredAt)
                .containsExactlyInAnyOrder(base.plusSeconds(3600), base.plusSeconds(60));
    }

    @Test
    @DisplayName("findNewestPerJob is empty when nothing has fired")
    void findNewestPerJob_emptyLog() {
        assertThat(fireLog.findNewestPerJob()).isEmpty();
    }

    @Test
    @DisplayName("the duration query returns only completed SUCCEEDED and PARTIAL fires of the job, newest first")
    void durationQuery_filtersByStatusJobAndCompletion() {
        Instant base = Instant.parse("2027-03-05T10:00:00Z");
        long oldOk = insertRunning("a_job", base);
        fireLog.complete(oldOk, base.plusSeconds(10), SchedulerFireStatus.SUCCEEDED, null, null);
        long newPartial = insertRunning("a_job", base.plusSeconds(100));
        fireLog.complete(newPartial, base.plusSeconds(120), SchedulerFireStatus.PARTIAL, null, null);
        long failed = insertRunning("a_job", base.plusSeconds(200));
        fireLog.complete(failed, base.plusSeconds(210), SchedulerFireStatus.FAILED, null, null);
        long running = insertRunning("a_job", base.plusSeconds(300));
        long otherJob = insertRunning("b_job", base.plusSeconds(400));
        fireLog.complete(otherJob, base.plusSeconds(410), SchedulerFireStatus.SUCCEEDED, null, null);

        List<SchedulerFireLogEntity> fires = fireLog
                .findTop200ByJobKeyAndStatusInAndCompletedAtIsNotNullOrderByFiredAtDesc("a_job",
                        List.of(SchedulerFireStatus.SUCCEEDED, SchedulerFireStatus.PARTIAL));

        assertThat(fires).extracting(SchedulerFireLogEntity::getId).containsExactly(newPartial, oldOk);
        assertThat(running).isNotIn(fires.stream().map(SchedulerFireLogEntity::getId).toList());
    }

    @Test
    @DisplayName("a Pause committed during a fire survives the fire's scoped last-completion write")
    void pauseDuringFire_isNotReverted() {
        jdbc.update("INSERT INTO scheduler_job_config (job_key, display_name, schedule_type, cron_expression, "
                + "status, updated_at) VALUES ('j1', 'j1', 'CRON', '0 0 1 * * *', 'ACTIVE', CURRENT_TIMESTAMP)");
        // The fire starts: the old wrapper read the row here and saved this copy back in its finally block.
        configs.updateLastFireTime("j1", T0);
        // An admin pauses the job while the fire is still running.
        jdbc.update("UPDATE scheduler_job_config SET status = 'PAUSED' WHERE job_key = 'j1'");

        configs.updateLastCompletionTime("j1", T0.plusSeconds(30));

        assertThat(jdbc.queryForObject("SELECT status FROM scheduler_job_config WHERE job_key = 'j1'",
                String.class)).isEqualTo("PAUSED");
    }
}
