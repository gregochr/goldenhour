package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link DynamicSchedulerService} with a real H2 database.
 *
 * <p>Verifies that the entity, repository, and service wire together correctly,
 * and that {@code initSchedules()} handles both empty and seeded tables.
 */
@SpringBootTest
class DynamicSchedulerServiceIntegrationTest {

    @Autowired
    private DynamicSchedulerService schedulerService;

    @Autowired
    private SchedulerJobConfigRepository repository;

    @Test
    @DisplayName("Repository correctly persists and retrieves scheduler job config")
    void repository_persistsAndRetrieves() {
        SchedulerJobConfigEntity entity = SchedulerJobConfigEntity.builder()
                .jobKey("integration_test_job")
                .displayName("Integration Test")
                .description("Test job for integration testing")
                .scheduleType(ScheduleType.CRON)
                .cronExpression("0 0 3 * * *")
                .status(SchedulerJobStatus.ACTIVE)
                .build();
        repository.save(entity);

        var found = repository.findByJobKey("integration_test_job");
        assertThat(found).isPresent();
        assertThat(found.get().getDisplayName()).isEqualTo("Integration Test");
        assertThat(found.get().getScheduleType()).isEqualTo(ScheduleType.CRON);
        assertThat(found.get().getCronExpression()).isEqualTo("0 0 3 * * *");
        assertThat(found.get().getStatus()).isEqualTo(SchedulerJobStatus.ACTIVE);

        repository.delete(found.get());
    }

    @Test
    @DisplayName("findAllByOrderByIdAsc returns rows in insertion order")
    void repository_findAllOrderedByIdAsc() {
        SchedulerJobConfigEntity a = SchedulerJobConfigEntity.builder()
                .jobKey("order_test_a").displayName("A")
                .scheduleType(ScheduleType.CRON).cronExpression("0 0 1 * * *")
                .status(SchedulerJobStatus.ACTIVE).build();
        SchedulerJobConfigEntity b = SchedulerJobConfigEntity.builder()
                .jobKey("order_test_b").displayName("B")
                .scheduleType(ScheduleType.CRON).cronExpression("0 0 2 * * *")
                .status(SchedulerJobStatus.ACTIVE).build();
        repository.save(a);
        repository.save(b);

        List<SchedulerJobConfigEntity> all = repository.findAllByOrderByIdAsc();
        List<String> keys = all.stream()
                .map(SchedulerJobConfigEntity::getJobKey)
                .filter(k -> k.startsWith("order_test_"))
                .toList();
        assertThat(keys).containsExactly("order_test_a", "order_test_b");

        repository.delete(a);
        repository.delete(b);
    }

    @Test
    @DisplayName("Full lifecycle: register target, save config, schedule, pause, resume")
    void fullLifecycle_registerSchedulePauseResume() {
        // Seed a config row
        SchedulerJobConfigEntity config = SchedulerJobConfigEntity.builder()
                .jobKey("lifecycle_test")
                .displayName("Lifecycle Test")
                .scheduleType(ScheduleType.CRON)
                .cronExpression("0 0 4 * * *")
                .status(SchedulerJobStatus.ACTIVE)
                .build();
        repository.save(config);

        // Register a target
        AtomicBoolean executed = new AtomicBoolean(false);
        schedulerService.registerJobTarget("lifecycle_test", () -> executed.set(true));

        // Schedule it
        schedulerService.scheduleJob(
                repository.findByJobKey("lifecycle_test").orElseThrow());

        // Pause
        SchedulerJobConfigEntity paused = schedulerService.pause("lifecycle_test");
        assertThat(paused.getStatus()).isEqualTo(SchedulerJobStatus.PAUSED);
        assertThat(repository.findByJobKey("lifecycle_test").orElseThrow().getStatus())
                .isEqualTo(SchedulerJobStatus.PAUSED);

        // Resume
        SchedulerJobConfigEntity resumed = schedulerService.resume("lifecycle_test");
        assertThat(resumed.getStatus()).isEqualTo(SchedulerJobStatus.ACTIVE);

        // Trigger now
        schedulerService.triggerNow("lifecycle_test");

        // Cleanup
        repository.delete(repository.findByJobKey("lifecycle_test").orElseThrow());
    }

    @Test
    @DisplayName("getAll returns all persisted configs")
    void getAll_returnsPersistedConfigs() {
        long countBefore = repository.count();

        SchedulerJobConfigEntity config = SchedulerJobConfigEntity.builder()
                .jobKey("getall_test")
                .displayName("GetAll Test")
                .scheduleType(ScheduleType.FIXED_DELAY)
                .fixedDelayMs(60000L)
                .status(SchedulerJobStatus.PAUSED)
                .build();
        repository.save(config);

        List<SchedulerJobConfigEntity> all = schedulerService.getAll();
        assertThat(all.size()).isEqualTo(countBefore + 1);

        repository.delete(config);
    }
}
