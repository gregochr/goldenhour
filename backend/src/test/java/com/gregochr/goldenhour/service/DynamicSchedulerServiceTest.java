package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DynamicSchedulerService}.
 */
@ExtendWith(MockitoExtension.class)
class DynamicSchedulerServiceTest {

    @Mock
    private SchedulerJobConfigRepository repository;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private AuroraProperties auroraProperties;

    private DynamicSchedulerService service;

    @BeforeEach
    void setUp() {
        service = new DynamicSchedulerService(repository, taskScheduler, auroraProperties);
        lenient().when(auroraProperties.isEnabled()).thenReturn(true);
    }

    // -------------------------------------------------------------------------
    // initSchedules
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("initSchedules schedules ACTIVE jobs")
    void initSchedules_schedulesActiveJobs() {
        SchedulerJobConfigEntity config = cronJob("tide_refresh", "0 0 2 * * MON",
                SchedulerJobStatus.ACTIVE);
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(config));
        when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn(mock(ScheduledFuture.class));
        service.registerJobTarget("tide_refresh", () -> { });

        service.initSchedules();

        verify(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    @DisplayName("initSchedules disables aurora jobs when aurora is disabled")
    void initSchedules_disablesAuroraJobsWhenAuroraDisabled() {
        when(auroraProperties.isEnabled()).thenReturn(false);
        SchedulerJobConfigEntity aurora = fixedDelayJob("aurora_polling", 300000L,
                SchedulerJobStatus.ACTIVE);
        aurora.setConfigSource("aurora.enabled");
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(aurora));

        service.initSchedules();

        assertThat(aurora.getStatus()).isEqualTo(SchedulerJobStatus.DISABLED_BY_CONFIG);
        verify(repository).save(aurora);
        verify(taskScheduler, never()).scheduleWithFixedDelay(any(Runnable.class),
                any(Duration.class));
    }

    @Test
    @DisplayName("initSchedules re-enables aurora jobs when aurora becomes enabled")
    void initSchedules_reEnablesAuroraJobsWhenEnabled() {
        SchedulerJobConfigEntity aurora = fixedDelayJob("aurora_polling", 300000L,
                SchedulerJobStatus.DISABLED_BY_CONFIG);
        aurora.setConfigSource("aurora.enabled");
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(aurora));
        when(taskScheduler.scheduleWithFixedDelay(any(Runnable.class), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));
        service.registerJobTarget("aurora_polling", () -> { });

        service.initSchedules();

        assertThat(aurora.getStatus()).isEqualTo(SchedulerJobStatus.ACTIVE);
    }

    // -------------------------------------------------------------------------
    // pause
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("pause cancels the scheduled future")
    @SuppressWarnings("unchecked")
    void pause_cancelsScheduledFuture() {
        SchedulerJobConfigEntity config = cronJob("tide_refresh", "0 0 2 * * MON",
                SchedulerJobStatus.ACTIVE);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(repository.findByJobKey("tide_refresh")).thenReturn(Optional.of(config));
        when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenAnswer(invocation -> future);
        service.registerJobTarget("tide_refresh", () -> { });
        service.scheduleJob(config);

        service.pause("tide_refresh");

        verify(future).cancel(false);
        assertThat(config.getStatus()).isEqualTo(SchedulerJobStatus.PAUSED);
        verify(repository).save(config);
    }

    @Test
    @DisplayName("pause rejects DISABLED_BY_CONFIG jobs")
    void pause_rejectsDisabledByConfig() {
        SchedulerJobConfigEntity config = cronJob("aurora_polling", "0 0 * * * *",
                SchedulerJobStatus.DISABLED_BY_CONFIG);
        config.setConfigSource("aurora.enabled");
        when(repository.findByJobKey("aurora_polling")).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.pause("aurora_polling"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled by config");
    }

    // -------------------------------------------------------------------------
    // resume
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("resume reschedules a paused job")
    void resume_reschedulesJob() {
        SchedulerJobConfigEntity config = cronJob("tide_refresh", "0 0 2 * * MON",
                SchedulerJobStatus.PAUSED);
        when(repository.findByJobKey("tide_refresh")).thenReturn(Optional.of(config));
        when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn(mock(ScheduledFuture.class));
        service.registerJobTarget("tide_refresh", () -> { });

        service.resume("tide_refresh");

        assertThat(config.getStatus()).isEqualTo(SchedulerJobStatus.ACTIVE);
        verify(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    @DisplayName("resume rejects DISABLED_BY_CONFIG jobs")
    void resume_rejectsDisabledByConfig() {
        SchedulerJobConfigEntity config = fixedDelayJob("aurora_polling", 300000L,
                SchedulerJobStatus.DISABLED_BY_CONFIG);
        config.setConfigSource("aurora.enabled");
        when(repository.findByJobKey("aurora_polling")).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.resume("aurora_polling"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled by config");
    }

    // -------------------------------------------------------------------------
    // updateSchedule
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateSchedule reschedules a CRON job with new expression")
    void updateSchedule_reschedulesCronJob() {
        SchedulerJobConfigEntity config = cronJob("daily_briefing", "0 0 4,14,22 * * *",
                SchedulerJobStatus.ACTIVE);
        when(repository.findByJobKey("daily_briefing")).thenReturn(Optional.of(config));
        when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn(mock(ScheduledFuture.class));
        service.registerJobTarget("daily_briefing", () -> { });
        service.scheduleJob(config);

        service.updateSchedule("daily_briefing", "0 0 6,18 * * *", null);

        assertThat(config.getCronExpression()).isEqualTo("0 0 6,18 * * *");
    }

    @Test
    @DisplayName("updateSchedule rejects invalid cron expression")
    void updateSchedule_rejectsInvalidCron() {
        SchedulerJobConfigEntity config = cronJob("tide_refresh", "0 0 2 * * MON",
                SchedulerJobStatus.ACTIVE);
        when(repository.findByJobKey("tide_refresh")).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.updateSchedule("tide_refresh", "invalid", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid cron");
    }

    @Test
    @DisplayName("updateSchedule rejects fixed delay shorter than 10 seconds")
    void updateSchedule_rejectsShortFixedDelay() {
        SchedulerJobConfigEntity config = fixedDelayJob("run_progress_cleanup", 300000L,
                SchedulerJobStatus.ACTIVE);
        when(repository.findByJobKey("run_progress_cleanup")).thenReturn(Optional.of(config));

        assertThatThrownBy(() ->
                service.updateSchedule("run_progress_cleanup", null, 5000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least");
    }

    // -------------------------------------------------------------------------
    // triggerNow
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("triggerNow runs the job immediately")
    void triggerNow_runsImmediately() {
        SchedulerJobConfigEntity config = cronJob("tide_refresh", "0 0 2 * * MON",
                SchedulerJobStatus.ACTIVE);
        when(repository.findByJobKey("tide_refresh")).thenReturn(Optional.of(config));
        service.registerJobTarget("tide_refresh", () -> { });

        service.triggerNow("tide_refresh");

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(captor.capture(), any(Instant.class));
        assertThat(captor.getValue()).isNotNull();
    }

    @Test
    @DisplayName("triggerNow rejects DISABLED_BY_CONFIG jobs")
    void triggerNow_rejectsDisabledByConfig() {
        SchedulerJobConfigEntity config = fixedDelayJob("aurora_polling", 300000L,
                SchedulerJobStatus.DISABLED_BY_CONFIG);
        config.setConfigSource("aurora.enabled");
        when(repository.findByJobKey("aurora_polling")).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.triggerNow("aurora_polling"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled by config");
    }

    // -------------------------------------------------------------------------
    // calculateNextFireTime
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("calculateNextFireTime returns next time for CRON job")
    void calculateNextFireTime_cronJob() {
        SchedulerJobConfigEntity config = cronJob("tide_refresh", "0 0 2 * * MON",
                SchedulerJobStatus.ACTIVE);

        Instant next = service.calculateNextFireTime(config);

        assertThat(next).isNotNull();
        assertThat(next).isAfter(Instant.now());
    }

    @Test
    @DisplayName("calculateNextFireTime returns next time for FIXED_DELAY job")
    void calculateNextFireTime_fixedDelayJob() {
        SchedulerJobConfigEntity config = fixedDelayJob("run_progress_cleanup", 300000L,
                SchedulerJobStatus.ACTIVE);
        config.setLastCompletionTime(Instant.now().minusSeconds(60));

        Instant next = service.calculateNextFireTime(config);

        assertThat(next).isNotNull();
        assertThat(next).isAfter(Instant.now());
    }

    @Test
    @DisplayName("calculateNextFireTime returns null for PAUSED job")
    void calculateNextFireTime_pausedJobReturnsNull() {
        SchedulerJobConfigEntity config = cronJob("tide_refresh", "0 0 2 * * MON",
                SchedulerJobStatus.PAUSED);

        Instant next = service.calculateNextFireTime(config);

        assertThat(next).isNull();
    }

    // -------------------------------------------------------------------------
    // wrapTarget — persistence of fire/completion times
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("wrapTarget persists lastFireTime and lastCompletionTime when runnable executes")
    void wrapTarget_persistsFireAndCompletionTimes() {
        SchedulerJobConfigEntity config = cronJob("tide_refresh", "0 0 2 * * MON",
                SchedulerJobStatus.ACTIVE);
        when(repository.findByJobKey("tide_refresh")).thenReturn(Optional.of(config));

        AtomicBoolean ran = new AtomicBoolean(false);
        service.registerJobTarget("tide_refresh", () -> ran.set(true));

        // Capture the wrapped runnable from triggerNow
        service.triggerNow("tide_refresh");

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(captor.capture(), any(Instant.class));

        // Execute the wrapped runnable
        captor.getValue().run();

        assertThat(ran.get()).isTrue();
        // findByJobKey called twice in wrapTarget (once for fire time, once for completion)
        // plus once in triggerNow itself
        verify(repository, atLeast(2)).findByJobKey("tide_refresh");
        // save called at least twice — once for fire time, once for completion time
        verify(repository, atLeast(2)).save(config);
        assertThat(config.getLastFireTime()).isNotNull();
        assertThat(config.getLastCompletionTime()).isNotNull();
        assertThat(config.getLastCompletionTime()).isAfterOrEqualTo(config.getLastFireTime());
    }

    @Test
    @DisplayName("wrapTarget persists lastCompletionTime even when target throws")
    void wrapTarget_persistsCompletionTimeOnFailure() {
        SchedulerJobConfigEntity config = cronJob("tide_refresh", "0 0 2 * * MON",
                SchedulerJobStatus.ACTIVE);
        when(repository.findByJobKey("tide_refresh")).thenReturn(Optional.of(config));

        service.registerJobTarget("tide_refresh", () -> {
            throw new RuntimeException("boom");
        });

        service.triggerNow("tide_refresh");

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(captor.capture(), any(Instant.class));

        assertThatThrownBy(() -> captor.getValue().run())
                .isInstanceOf(RuntimeException.class);

        // Completion time should still be persisted via finally block
        assertThat(config.getLastCompletionTime()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // scheduleJob — FIXED_DELAY path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("scheduleJob uses scheduleWithFixedDelay for FIXED_DELAY jobs")
    void scheduleJob_usesFixedDelayForFixedDelayJobs() {
        SchedulerJobConfigEntity config = fixedDelayJob("run_progress_cleanup", 300000L,
                SchedulerJobStatus.ACTIVE);
        when(taskScheduler.scheduleWithFixedDelay(any(Runnable.class), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));
        service.registerJobTarget("run_progress_cleanup", () -> { });

        service.scheduleJob(config);

        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(taskScheduler).scheduleWithFixedDelay(any(Runnable.class),
                durationCaptor.capture());
        assertThat(durationCaptor.getValue()).isEqualTo(Duration.ofMillis(300000));
    }

    @Test
    @DisplayName("scheduleJob skips scheduling when no target is registered")
    void scheduleJob_skipsWhenNoTargetRegistered() {
        SchedulerJobConfigEntity config = cronJob("unknown_job", "0 0 * * * *",
                SchedulerJobStatus.ACTIVE);

        service.scheduleJob(config);

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(CronTrigger.class));
        verify(taskScheduler, never()).scheduleWithFixedDelay(any(Runnable.class),
                any(Duration.class));
    }

    // -------------------------------------------------------------------------
    // concurrent registration
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("registerJobTarget from multiple threads does not lose entries")
    void registerJobTarget_concurrentRegistration() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final String key = "job_" + i;
            new Thread(() -> {
                service.registerJobTarget(key, () -> { });
                latch.countDown();
            }).start();
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Verify all 10 registrations are present by scheduling each one.
        // Use lenient stubbing since scheduleJob calls wrapTarget → findByJobKey,
        // but the wrapped runnable isn't executed here, so some stubs won't be used.
        lenient().when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn(mock(ScheduledFuture.class));

        int scheduled = 0;
        for (int i = 0; i < threadCount; i++) {
            SchedulerJobConfigEntity config = cronJob("job_" + i, "0 0 * * * *",
                    SchedulerJobStatus.ACTIVE);
            service.scheduleJob(config);
            scheduled++;
        }

        assertThat(scheduled).isEqualTo(threadCount);
        verify(taskScheduler, times(threadCount))
                .schedule(any(Runnable.class), any(CronTrigger.class));
    }

    // -------------------------------------------------------------------------
    // triggerNow — no registered target
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("triggerNow throws when no target is registered for the job")
    void triggerNow_throwsWhenNoTargetRegistered() {
        SchedulerJobConfigEntity config = cronJob("unregistered", "0 0 * * * *",
                SchedulerJobStatus.ACTIVE);
        when(repository.findByJobKey("unregistered")).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.triggerNow("unregistered"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No registered target");
    }

    // -------------------------------------------------------------------------
    // pause/resume/trigger — nonexistent job key
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("pause throws NoSuchElementException for unknown job key")
    void pause_throwsForUnknownJobKey() {
        when(repository.findByJobKey("nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pause("nonexistent"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("resume throws NoSuchElementException for unknown job key")
    void resume_throwsForUnknownJobKey() {
        when(repository.findByJobKey("nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resume("nonexistent"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("triggerNow throws NoSuchElementException for unknown job key")
    void triggerNow_throwsForUnknownJobKey() {
        when(repository.findByJobKey("nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.triggerNow("nonexistent"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("updateSchedule throws NoSuchElementException for unknown job key")
    void updateSchedule_throwsForUnknownJobKey() {
        when(repository.findByJobKey("nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateSchedule("nonexistent", "0 0 * * * *", null))
                .isInstanceOf(NoSuchElementException.class);
    }

    // -------------------------------------------------------------------------
    // updateSchedule — FIXED_DELAY path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateSchedule updates fixedDelayMs for FIXED_DELAY job")
    void updateSchedule_updatesFixedDelay() {
        SchedulerJobConfigEntity config = fixedDelayJob("run_progress_cleanup", 300000L,
                SchedulerJobStatus.ACTIVE);
        when(repository.findByJobKey("run_progress_cleanup"))
                .thenReturn(Optional.of(config));
        when(taskScheduler.scheduleWithFixedDelay(any(Runnable.class), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));
        service.registerJobTarget("run_progress_cleanup", () -> { });
        service.scheduleJob(config);

        service.updateSchedule("run_progress_cleanup", null, 600000L);

        assertThat(config.getFixedDelayMs()).isEqualTo(600000L);
    }

    // -------------------------------------------------------------------------
    // calculateNextFireTime — FIXED_DELAY with no prior run uses initial delay
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("calculateNextFireTime uses initialDelayMs when no prior completion")
    void calculateNextFireTime_fixedDelayWithoutPriorRun() {
        SchedulerJobConfigEntity config = fixedDelayJob("aurora_polling", 300000L,
                SchedulerJobStatus.ACTIVE);
        config.setInitialDelayMs(60000L);
        config.setLastCompletionTime(null);

        Instant now = Instant.now();
        Instant next = service.calculateNextFireTime(config);

        assertThat(next).isNotNull();
        // Should be approximately now + 60s (initial delay)
        assertThat(next).isBetween(now.plusMillis(59000), now.plusMillis(61000));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SchedulerJobConfigEntity cronJob(String key, String cron,
            SchedulerJobStatus status) {
        return SchedulerJobConfigEntity.builder()
                .id(1L)
                .jobKey(key)
                .displayName(key)
                .scheduleType(ScheduleType.CRON)
                .cronExpression(cron)
                .status(status)
                .build();
    }

    private SchedulerJobConfigEntity fixedDelayJob(String key, Long delayMs,
            SchedulerJobStatus status) {
        return SchedulerJobConfigEntity.builder()
                .id(2L)
                .jobKey(key)
                .displayName(key)
                .scheduleType(ScheduleType.FIXED_DELAY)
                .fixedDelayMs(delayMs)
                .initialDelayMs(60000L)
                .status(status)
                .build();
    }
}
