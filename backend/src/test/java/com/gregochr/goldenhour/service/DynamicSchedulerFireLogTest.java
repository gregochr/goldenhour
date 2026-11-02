package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerFireLogEntity;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.entity.SchedulerFireTrigger;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.repository.SchedulerFireLogRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins what {@code DynamicSchedulerService.wrapTarget} records for every fire (scheduler-redesign-plan.md S1):
 * the {@code scheduler_fire_log} row, the outcome a job reports through {@link SchedulerFireOutcome}, the
 * scoped writes that replaced the whole-entity saves (the lost-update race), the trigger kinds, the
 * {@code schedule_changed_at} stamp and the startup orphan sweep.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class DynamicSchedulerFireLogTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final String KEY = "tide_refresh";
    private static final long LOG_ID = 7L;

    @Mock
    private SchedulerJobConfigRepository repository;

    @Mock
    private SchedulerFireLogRepository fireLog;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private AuroraProperties auroraProperties;

    @Mock
    private SolarTriggerFactory solarTriggers;

    @Mock
    private SolarCatchUpPolicy solarCatchUp;

    @Mock
    private ScheduledFuture<?> future;

    private DynamicSchedulerService service;

    @BeforeEach
    void setUp() {
        service = new DynamicSchedulerService(repository, fireLog, taskScheduler, auroraProperties,
                solarTriggers, solarCatchUp, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // -------------------------------------------------------------------------
    // Fire outcome recorded by the wrapper
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a job that returns normally is logged RUNNING at the clock instant, then completed SUCCEEDED")
    void success_isLoggedAndCompletedSucceeded() {
        stubInsert();
        service.registerJobTarget(KEY, () -> { });

        scheduledRunnable().run();

        SchedulerFireLogEntity row = insertedRow();
        assertThat(row.getJobKey()).isEqualTo(KEY);
        assertThat(row.getFiredAt()).isEqualTo(NOW);
        assertThat(row.getStatus()).isEqualTo(SchedulerFireStatus.RUNNING);
        assertThat(row.getTriggerKind()).isEqualTo(SchedulerFireTrigger.SCHEDULED);
        verify(fireLog).complete(LOG_ID, NOW, SchedulerFireStatus.SUCCEEDED, null, null);
    }

    @Test
    @DisplayName("a PARTIAL report and its summary pass through to the completed row")
    void partialReport_passesThrough() {
        stubInsert();
        service.registerJobTarget(KEY,
                () -> SchedulerFireOutcome.report(SchedulerFireStatus.PARTIAL, "3 of 5 refreshed"));

        scheduledRunnable().run();

        verify(fireLog).complete(LOG_ID, NOW, SchedulerFireStatus.PARTIAL, "3 of 5 refreshed", null);
    }

    @Test
    @DisplayName("a SKIPPED report and its summary pass through to the completed row")
    void skippedReport_passesThrough() {
        stubInsert();
        service.registerJobTarget(KEY,
                () -> SchedulerFireOutcome.report(SchedulerFireStatus.SKIPPED, "aurora is switched off"));

        scheduledRunnable().run();

        verify(fireLog).complete(LOG_ID, NOW, SchedulerFireStatus.SKIPPED, "aurora is switched off", null);
    }

    @Test
    @DisplayName("the last report wins")
    void lastReportWins() {
        stubInsert();
        service.registerJobTarget(KEY, () -> {
            SchedulerFireOutcome.report(SchedulerFireStatus.FAILED, "first");
            SchedulerFireOutcome.report(SchedulerFireStatus.PARTIAL, "second");
        });

        scheduledRunnable().run();

        verify(fireLog).complete(LOG_ID, NOW, SchedulerFireStatus.PARTIAL, "second", null);
    }

    @Test
    @DisplayName("a pipeline run linked by the job is stored on the completed row")
    void linkedPipelineRun_isStored() {
        stubInsert();
        service.registerJobTarget(KEY, () -> SchedulerFireOutcome.linkPipelineRun(99L));

        scheduledRunnable().run();

        verify(fireLog).complete(LOG_ID, NOW, SchedulerFireStatus.SUCCEEDED, null, 99L);
    }

    @Test
    @DisplayName("an exception is logged FAILED as 'ExceptionClass: message' and rethrown unchanged")
    void exception_isLoggedFailedAndRethrown() {
        stubInsert();
        IllegalStateException boom = new IllegalStateException("upstream down");
        service.registerJobTarget(KEY, () -> {
            throw boom;
        });

        Runnable wrapped = scheduledRunnable();

        assertThatThrownBy(wrapped::run).isSameAs(boom);
        verify(fireLog).complete(LOG_ID, NOW, SchedulerFireStatus.FAILED,
                "IllegalStateException: upstream down", null);
        verify(repository).updateLastCompletionTime(KEY, NOW);
    }

    @Test
    @DisplayName("an exception message with line breaks is sanitised before it is stored")
    void exceptionSummary_isSanitised() {
        stubInsert();
        service.registerJobTarget(KEY, () -> {
            throw new IllegalStateException("line one\nline two\r\nforged log line");
        });

        Runnable wrapped = scheduledRunnable();

        assertThatThrownBy(wrapped::run).isInstanceOf(IllegalStateException.class);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(fireLog).complete(eq(LOG_ID), eq(NOW), eq(SchedulerFireStatus.FAILED), summary.capture(), any());
        assertThat(summary.getValue()).startsWith("IllegalStateException: line one")
                .doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    @DisplayName("a reported summary is sanitised and cut to the 200-character column")
    void reportedSummary_isSanitisedAndTruncatedTo200() {
        stubInsert();
        String longSummary = "x".repeat(150) + "\n" + "y".repeat(300);
        service.registerJobTarget(KEY, () -> SchedulerFireOutcome.report(SchedulerFireStatus.SUCCEEDED, longSummary));

        scheduledRunnable().run();

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(fireLog).complete(eq(LOG_ID), eq(NOW), eq(SchedulerFireStatus.SUCCEEDED), summary.capture(), any());
        assertThat(summary.getValue()).hasSize(200).doesNotContain("\n").startsWith("x".repeat(150));
    }

    @Test
    @DisplayName("a failure to insert the log row is swallowed: the job still runs and is not completed in the log")
    void insertFailure_doesNotStopTheJob() {
        when(fireLog.save(any(SchedulerFireLogEntity.class))).thenThrow(new IllegalStateException("db down"));
        AtomicReference<Boolean> ran = new AtomicReference<>(false);
        service.registerJobTarget(KEY, () -> ran.set(true));

        assertThatCode(() -> scheduledRunnable().run()).doesNotThrowAnyException();

        assertThat(ran.get()).isTrue();
        verify(repository).updateLastFireTime(KEY, NOW);
        verify(repository).updateLastCompletionTime(KEY, NOW);
        verify(fireLog, never()).complete(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a failure to complete the log row does not throw out of the fire")
    void completeFailure_doesNotThrow() {
        stubInsert();
        when(fireLog.complete(anyLong(), any(), any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        service.registerJobTarget(KEY, () -> { });

        assertThatCode(() -> scheduledRunnable().run()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a completion failure does not replace the job's own exception")
    void completeFailure_doesNotReplaceTheJobsException() {
        stubInsert();
        when(fireLog.complete(anyLong(), any(), any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        IllegalArgumentException boom = new IllegalArgumentException("job failed");
        service.registerJobTarget(KEY, () -> {
            throw boom;
        });

        Runnable wrapped = scheduledRunnable();

        assertThatThrownBy(wrapped::run).isSameAs(boom);
    }

    // -------------------------------------------------------------------------
    // Scoped writes: the lost-update race
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a fire writes last-fire and last-completion by scoped update and never reads and saves the row")
    void fire_usesScopedUpdates_notFindAndSave() {
        stubInsert();
        service.registerJobTarget(KEY, () -> { });
        Runnable wrapped = scheduledRunnable();
        clearInvocations(repository);

        wrapped.run();

        InOrder order = inOrder(repository);
        order.verify(repository).updateLastFireTime(KEY, NOW);
        order.verify(repository).updateLastCompletionTime(KEY, NOW);
        verify(repository, never()).findByJobKey(any());
        verify(repository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Trigger kinds and the manual flag
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("triggerNow logs MANUAL and tells the target manual == true")
    void triggerNow_isManual() {
        stubInsert();
        SchedulerJobConfigEntity config = cronJob(KEY, SchedulerJobStatus.ACTIVE);
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(config));
        AtomicReference<Boolean> manual = new AtomicReference<>();
        service.registerManualAwareJobTarget(KEY, manual::set);

        service.triggerNow(KEY);
        ArgumentCaptor<Runnable> run = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(run.capture(), any(Instant.class));
        run.getValue().run();

        assertThat(manual.get()).isTrue();
        assertThat(insertedRow().getTriggerKind()).isEqualTo(SchedulerFireTrigger.MANUAL);
    }

    @Test
    @DisplayName("an installed schedule logs SCHEDULED and tells the target manual == false")
    void installedSchedule_isScheduled() {
        stubInsert();
        AtomicReference<Boolean> manual = new AtomicReference<>();
        service.registerManualAwareJobTarget(KEY, manual::set);

        scheduledRunnable().run();

        assertThat(manual.get()).isFalse();
        assertThat(insertedRow().getTriggerKind()).isEqualTo(SchedulerFireTrigger.SCHEDULED);
    }

    // -------------------------------------------------------------------------
    // schedule_changed_at
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("pause stamps schedule_changed_at")
    void pause_stampsScheduleChangedAt() {
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(cronJob(KEY, SchedulerJobStatus.ACTIVE)));

        service.pause(KEY);

        verify(repository).updateScheduleChangedAt(KEY, NOW);
    }

    @Test
    @DisplayName("resume stamps schedule_changed_at")
    void resume_stampsScheduleChangedAt() {
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(cronJob(KEY, SchedulerJobStatus.PAUSED)));
        service.registerJobTarget(KEY, () -> { });
        when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class))).thenReturn((ScheduledFuture) future);

        service.resume(KEY);

        verify(repository).updateScheduleChangedAt(KEY, NOW);
    }

    @Test
    @DisplayName("updateSchedule stamps schedule_changed_at")
    void updateSchedule_stampsScheduleChangedAt() {
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(cronJob(KEY, SchedulerJobStatus.PAUSED)));

        service.updateSchedule(KEY, "0 30 4 * * *", null);

        verify(repository).updateScheduleChangedAt(KEY, NOW);
    }

    @Test
    @DisplayName("switchScheduleType stamps schedule_changed_at")
    void switchScheduleType_stampsScheduleChangedAt() {
        String cycle = ForecastCycleJobs.NIGHTLY_JOB;
        SchedulerJobConfigEntity config = cronJob(cycle, SchedulerJobStatus.PAUSED);
        when(repository.findByJobKey(cycle)).thenReturn(Optional.of(config));
        when(repository.updateScheduleType(eq(1L), eq("CRON"), eq(null), eq(null), any(Instant.class)))
                .thenReturn(1);

        service.switchScheduleType(cycle, ScheduleType.CRON, null, null);

        verify(repository).updateScheduleChangedAt(cycle, NOW);
    }

    @Test
    @DisplayName("a failure to stamp schedule_changed_at does not fail the pause")
    void stampFailure_doesNotFailPause() {
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(cronJob(KEY, SchedulerJobStatus.ACTIVE)));
        when(repository.updateScheduleChangedAt(KEY, NOW)).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> service.pause(KEY)).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Orphan sweep
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("initSchedules marks leftover RUNNING fires FAILED before it reads or schedules any job")
    void initSchedules_sweepsOrphansFirst() {
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of());

        service.initSchedules();

        InOrder order = inOrder(fireLog, repository);
        order.verify(fireLog).markOrphans(NOW, "Interrupted by a restart");
        order.verify(repository).findAllByOrderByIdAsc();
    }

    @Test
    @DisplayName("a failing orphan sweep does not stop jobs being scheduled")
    void orphanSweepFailure_doesNotBlockStartup() {
        when(fireLog.markOrphans(any(Instant.class), any(String.class)))
                .thenThrow(new IllegalStateException("db down"));
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of());

        assertThatCode(() -> service.initSchedules()).doesNotThrowAnyException();

        verify(repository).findAllByOrderByIdAsc();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Installs a CRON schedule for {@link #KEY} and returns the wrapped runnable the scheduler was given. */
    private Runnable scheduledRunnable() {
        when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class))).thenReturn((ScheduledFuture) future);
        service.scheduleJob(cronJob(KEY, SchedulerJobStatus.ACTIVE));
        ArgumentCaptor<Runnable> run = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(run.capture(), any(Trigger.class));
        return run.getValue();
    }

    private void stubInsert() {
        when(fireLog.save(any(SchedulerFireLogEntity.class))).thenAnswer(inv -> {
            SchedulerFireLogEntity row = inv.getArgument(0);
            row.setId(LOG_ID);
            return row;
        });
    }

    private SchedulerFireLogEntity insertedRow() {
        ArgumentCaptor<SchedulerFireLogEntity> row = ArgumentCaptor.forClass(SchedulerFireLogEntity.class);
        verify(fireLog).save(row.capture());
        return row.getValue();
    }

    private static SchedulerJobConfigEntity cronJob(String key, SchedulerJobStatus status) {
        return SchedulerJobConfigEntity.builder()
                .id(1L)
                .jobKey(key)
                .displayName(key)
                .scheduleType(ScheduleType.CRON)
                .cronExpression("0 0 2 * * MON")
                .status(status)
                .build();
    }
}
