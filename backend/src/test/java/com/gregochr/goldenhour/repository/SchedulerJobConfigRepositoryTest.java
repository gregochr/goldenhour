package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.SolarCatchUpPolicy;
import com.gregochr.goldenhour.service.SolarTriggerFactory;
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

    private static final String UNKNOWN = "LUNAR_PHASE";

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
                aurora, mock(SolarTriggerFactory.class), mock(SolarCatchUpPolicy.class),
                java.time.Clock.systemUTC());
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

    private Map<String, Object> solarColumns(String key) {
        return jdbc.queryForMap("SELECT schedule_type, solar_event, solar_offset_minutes, "
                + "cron_expression, status FROM scheduler_job_config WHERE job_key = ?", key);
    }

    @Test
    @DisplayName("updateScheduleType writes type, event and offset together and touches nothing else")
    void updateScheduleType_writesThreeColumnsOnly() {
        Long id = repository.findByJobKey("known_a").orElseThrow().getId();
        entityManager.clear();

        int updated = repository.updateScheduleType(id, "SOLAR_OFFSET", "SUNRISE", 255,
                Instant.parse("2026-10-13T10:00:00Z"));
        entityManager.clear();

        assertThat(updated).isEqualTo(1);
        Map<String, Object> row = solarColumns("known_a");
        assertThat(row.get("schedule_type")).isEqualTo("SOLAR_OFFSET");
        assertThat(row.get("solar_event")).isEqualTo("SUNRISE");
        assertThat(row.get("solar_offset_minutes")).isEqualTo(255);
        assertThat(row.get("cron_expression")).as("the fallback cron is kept").isEqualTo("0 0 1 * * *");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        SchedulerJobConfigEntity reloaded = repository.findByJobKey("known_a").orElseThrow();
        assertThat(reloaded.getScheduleType()).isEqualTo(ScheduleType.SOLAR_OFFSET);
        assertThat(reloaded.getSolarEvent()).isEqualTo(com.gregochr.goldenhour.entity.SolarEvent.SUNRISE);
        assertThat(reloaded.getSolarOffsetMinutes()).isEqualTo(255);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(Instant.parse("2026-10-13T10:00:00Z"));
    }

    @Test
    @DisplayName("updateScheduleType back to CRON with nulls clears the solar columns")
    void updateScheduleType_toCronClearsSolarColumns() {
        Long id = repository.findByJobKey("known_a").orElseThrow().getId();
        repository.updateScheduleType(id, "SOLAR_OFFSET", "SUNSET", 240, Instant.now());

        repository.updateScheduleType(id, "CRON", null, null, Instant.now());
        entityManager.clear();

        Map<String, Object> row = solarColumns("known_a");
        assertThat(row.get("schedule_type")).isEqualTo("CRON");
        assertThat(row.get("solar_event")).isNull();
        assertThat(row.get("solar_offset_minutes")).isNull();
    }

    @Test
    @DisplayName("a whole-entity save from a copy loaded BEFORE a type switch cannot undo it")
    void staleEntitySave_cannotRewriteTheSwitch() {
        SchedulerJobConfigEntity stale = repository.findByJobKey("known_a").orElseThrow();
        entityManager.detach(stale);
        repository.updateScheduleType(stale.getId(), "SOLAR_OFFSET", "SUNRISE", 255, Instant.now());
        entityManager.clear();

        stale.setStatus(SchedulerJobStatus.PAUSED);
        stale.setLastFireTime(Instant.now());
        stale.setSolarEvent(null);
        stale.setScheduleType(ScheduleType.CRON);
        repository.saveAndFlush(stale);
        entityManager.clear();

        Map<String, Object> row = solarColumns("known_a");
        assertThat(row.get("status")).as("positive control: the save did write").isEqualTo("PAUSED");
        assertThat(row.get("schedule_type")).isEqualTo("SOLAR_OFFSET");
        assertThat(row.get("solar_event")).isEqualTo("SUNRISE");
        assertThat(row.get("solar_offset_minutes")).isEqualTo(255);
    }

    @Test
    @DisplayName("an inserted row carries its solar fields; a stored event this build does not know "
            + "reads as null instead of failing the load")
    void insertAndUnknownEvent() {
        repository.saveAndFlush(SchedulerJobConfigEntity.builder().jobKey("fresh_solar")
                .displayName("fresh").scheduleType(ScheduleType.SOLAR_OFFSET)
                .solarEvent(com.gregochr.goldenhour.entity.SolarEvent.SUNSET).solarOffsetMinutes(240)
                .cronExpression("0 0 14 * * *").build());
        entityManager.clear();
        assertThat(solarColumns("fresh_solar").get("solar_event")).isEqualTo("SUNSET");

        jdbc.update("UPDATE scheduler_job_config SET solar_event = 'MOONRISE' WHERE job_key = 'fresh_solar'");
        entityManager.clear();

        SchedulerJobConfigEntity loaded = repository.findByJobKey("fresh_solar").orElseThrow();
        assertThat(loaded.getSolarEvent()).isNull();
        assertThat(loaded.getSolarEventRaw()).isEqualTo("MOONRISE");
    }
}
