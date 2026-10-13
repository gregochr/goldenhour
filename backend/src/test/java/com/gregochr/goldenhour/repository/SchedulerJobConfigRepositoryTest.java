package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves {@link SchedulerJobConfigEntity} loads a {@code schedule_type} this build does not know
 * and never writes a guess over it. Rows are inserted with raw SQL, and every assertion reads the
 * column through {@link JdbcTemplate}, never the persistence context.
 */
@DataJpaTest
@SuppressWarnings({"unchecked", "rawtypes"})
class SchedulerJobConfigRepositoryTest {

    private static final String UNKNOWN = "SOLAR_OFFSET";

    @Autowired
    private SchedulerJobConfigRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void insertRows() {
        insert("known_a", "CRON", "0 0 1 * * *");
        insert("unknown_b", UNKNOWN, "0 0 2 * * *");
        insert("known_c", "CRON", "0 0 3 * * *");
        entityManager.clear();
    }

    private void insert(String key, String type, String cron) {
        jdbc.update("INSERT INTO scheduler_job_config (job_key, display_name, schedule_type, "
                + "cron_expression, status, updated_at) "
                + "VALUES (?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP)", key, key, type, cron);
    }

    private String storedType(String key) {
        return jdbc.queryForObject(
                "SELECT schedule_type FROM scheduler_job_config WHERE job_key = ?", String.class, key);
    }

    private Map<String, Object> row(String key) {
        return jdbc.queryForMap(
                "SELECT status, last_fire_time, last_completion_time FROM scheduler_job_config "
                        + "WHERE job_key = ?", key);
    }

    @Test
    @DisplayName("an unknown schedule_type does not fail the load of the whole table")
    void unknownScheduleType_loadsWithTheRest() {
        List<SchedulerJobConfigEntity> all = repository.findAllByOrderByIdAsc();

        assertThat(all).extracting(SchedulerJobConfigEntity::getJobKey)
                .containsExactly("known_a", "unknown_b", "known_c");
        SchedulerJobConfigEntity unknown = all.get(1);
        assertThat(unknown.getScheduleType()).isNull();
        assertThat(unknown.getScheduleTypeRaw()).isEqualTo(UNKNOWN);
        assertThat(all.get(0).getScheduleType()).isEqualTo(ScheduleType.CRON);
    }

    @Test
    @DisplayName("saving a loaded row never overwrites its stored schedule_type")
    void wholeEntitySave_neverOverwritesScheduleType() {
        SchedulerJobConfigEntity unknown = repository.findByJobKey("unknown_b").orElseThrow();
        unknown.setStatus(SchedulerJobStatus.PAUSED);
        unknown.setLastFireTime(Instant.parse("2026-10-13T01:00:00Z"));
        // Even a deliberate in-memory change to the type must not reach the database.
        unknown.setScheduleType(ScheduleType.FIXED_DELAY);
        repository.saveAndFlush(unknown);
        entityManager.clear();

        // Positive control: this save did write (status and fire time moved).
        assertThat(row("unknown_b").get("status")).isEqualTo("PAUSED");
        assertThat(row("unknown_b").get("last_fire_time")).isNotNull();
        assertThat(storedType("unknown_b")).isEqualTo(UNKNOWN);
    }

    @Test
    @DisplayName("the scheduler's own writers (pause, resume, a fire) leave the original string intact")
    void schedulerWriters_leaveScheduleTypeIntact() {
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn((ScheduledFuture) mock(ScheduledFuture.class));
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenReturn((ScheduledFuture) mock(ScheduledFuture.class));
        AuroraProperties aurora = mock(AuroraProperties.class);
        DynamicSchedulerService service = new DynamicSchedulerService(repository, taskScheduler,
                aurora);
        service.registerJobTarget("unknown_b", () -> { });
        service.registerJobTarget("known_a", () -> { });
        service.registerJobTarget("known_c", () -> { });

        service.initSchedules();
        service.pause("unknown_b");
        service.resume("unknown_b");
        service.triggerNow("unknown_b");
        ArgumentCaptor<Runnable> fired = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(fired.capture(), any(Instant.class));
        fired.getValue().run();
        entityManager.flush();
        entityManager.clear();

        // The two known rows were scheduled; the unknown one never was.
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(CronTrigger.class));
        verify(taskScheduler, never()).scheduleWithFixedDelay(any(Runnable.class),
                any(Duration.class));
        // Positive controls: the pause and the fire both wrote.
        assertThat(row("unknown_b").get("status")).isEqualTo("PAUSED");
        assertThat(row("unknown_b").get("last_completion_time")).isNotNull();
        assertThat(storedType("unknown_b")).isEqualTo(UNKNOWN);
    }

    @Test
    @DisplayName("a known type still round-trips through the builder")
    void knownScheduleType_roundTrips() {
        repository.saveAndFlush(SchedulerJobConfigEntity.builder().jobKey("fresh")
                .displayName("fresh").scheduleType(ScheduleType.FIXED_DELAY).fixedDelayMs(60_000L)
                .build());
        entityManager.clear();

        assertThat(storedType("fresh")).isEqualTo("FIXED_DELAY");
        assertThat(repository.findByJobKey("fresh").orElseThrow().getScheduleType())
                .isEqualTo(ScheduleType.FIXED_DELAY);
    }
}
