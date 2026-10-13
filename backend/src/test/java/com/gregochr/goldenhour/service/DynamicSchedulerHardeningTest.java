package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the scheduler hardening (solar-relative-refresh-plan P1): no single job's configuration can
 * stop another job being scheduled, and no reschedule can leave a job unscheduled.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class DynamicSchedulerHardeningTest {

    @Mock
    private SchedulerJobConfigRepository repository;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private AuroraProperties auroraProperties;

    private DynamicSchedulerService service;

    private TimeZone originalZone;

    @BeforeEach
    void setUp() {
        originalZone = TimeZone.getDefault();
        service = new DynamicSchedulerService(repository, taskScheduler, auroraProperties);
        lenient().when(auroraProperties.isEnabled()).thenReturn(true);
        for (String key : List.of("job_a", "job_b", "job_c")) {
            service.registerJobTarget(key, () -> { });
        }
    }

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(originalZone);
    }

    @Test
    @DisplayName("a CRON row with a null cron does not stop the rows around it being scheduled")
    void initSchedules_nullCronInTheMiddle_otherJobsScheduled() {
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(
                cron("job_a", "0 0 1 * * *"), cron("job_b", null), cron("job_c", "0 0 3 * * *")));
        when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn(mock(ScheduledFuture.class));

        assertThatCode(() -> service.initSchedules()).doesNotThrowAnyException();

        ArgumentCaptor<CronTrigger> triggers = ArgumentCaptor.forClass(CronTrigger.class);
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), triggers.capture());
        assertThat(triggers.getAllValues()).extracting(CronTrigger::getExpression)
                .containsExactly("0 0 1 * * *", "0 0 3 * * *");
    }

    @Test
    @DisplayName("a FIXED_DELAY row with a null or zero delay is left unscheduled, the other is not")
    void initSchedules_nullDelay_otherJobsScheduled() {
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(
                delay("job_a", null), delay("job_b", 60_000L), delay("job_c", 0L)));
        when(taskScheduler.scheduleWithFixedDelay(any(Runnable.class), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        assertThatCode(() -> service.initSchedules()).doesNotThrowAnyException();

        ArgumentCaptor<Duration> delays = ArgumentCaptor.forClass(Duration.class);
        verify(taskScheduler).scheduleWithFixedDelay(any(Runnable.class), delays.capture());
        assertThat(delays.getValue()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("an unparseable cron is skipped alone")
    void initSchedules_unparseableCron_skippedAlone() {
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(
                cron("job_a", "not a cron"), cron("job_b", "0 0 2 * * *")));
        when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn(mock(ScheduledFuture.class));

        service.initSchedules();

        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    @DisplayName("a schedule type this build does not know is skipped; the rest are scheduled")
    void initSchedules_unrecognisedType_skippedOthersScheduled() {
        SchedulerJobConfigEntity unknown = cron("job_b", "0 0 2 * * *");
        unknown.setScheduleTypeRaw("SOLAR_OFFSET");
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(
                cron("job_a", "0 0 1 * * *"), unknown, cron("job_c", "0 0 3 * * *")));
        when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn(mock(ScheduledFuture.class));

        service.initSchedules();

        ArgumentCaptor<CronTrigger> triggers = ArgumentCaptor.forClass(CronTrigger.class);
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), triggers.capture());
        assertThat(triggers.getAllValues()).extracting(CronTrigger::getExpression)
                .containsExactly("0 0 1 * * *", "0 0 3 * * *");
    }

    @Test
    @DisplayName("a task scheduler that throws for one job does not stop the next job")
    void initSchedules_schedulerThrowsForOneRow_nextRowScheduled() {
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(
                cron("job_a", "0 0 1 * * *"), cron("job_b", "0 0 2 * * *")));
        when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenThrow(new RejectedExecutionException("pool closed"))
                .thenReturn((ScheduledFuture) mock(ScheduledFuture.class));

        assertThatCode(() -> service.initSchedules()).doesNotThrowAnyException();

        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    @DisplayName("a failing replacement install leaves the previous future running, not cancelled")
    void updateSchedule_replacementFailsToInstall_previousFutureKept() {
        SchedulerJobConfigEntity config = cron("job_a", "0 0 1 * * *");
        when(repository.findByJobKey("job_a")).thenReturn(Optional.of(config));
        ScheduledFuture<?> previous = mock(ScheduledFuture.class);
        when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn((ScheduledFuture) previous)
                .thenThrow(new RejectedExecutionException("pool closed"));
        service.scheduleJob(config);

        assertThatThrownBy(() -> service.updateSchedule("job_a", "0 0 5 * * *", null))
                .isInstanceOf(RejectedExecutionException.class);

        verify(previous, never()).cancel(anyBoolean());
    }

    @Test
    @DisplayName("an invalid cron changes nothing: not saved, previous future not cancelled")
    void updateSchedule_invalidCron_changesNothing() {
        SchedulerJobConfigEntity config = cron("job_a", "0 0 1 * * *");
        when(repository.findByJobKey("job_a")).thenReturn(Optional.of(config));
        ScheduledFuture<?> previous = mock(ScheduledFuture.class);
        when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn((ScheduledFuture) previous);
        service.scheduleJob(config);

        assertThatThrownBy(() -> service.updateSchedule("job_a", "nonsense", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(config.getCronExpression()).isEqualTo("0 0 1 * * *");
        verify(repository, never()).save(any());
        verify(previous, never()).cancel(anyBoolean());
    }

    @Test
    @DisplayName("a successful update installs the new future before cancelling the old one")
    void updateSchedule_installsNewBeforeCancellingOld() {
        SchedulerJobConfigEntity config = cron("job_a", "0 0 1 * * *");
        when(repository.findByJobKey("job_a")).thenReturn(Optional.of(config));
        ScheduledFuture<?> previous = mock(ScheduledFuture.class);
        ScheduledFuture<?> replacement = mock(ScheduledFuture.class);
        when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn((ScheduledFuture) previous)
                .thenReturn((ScheduledFuture) replacement);
        service.scheduleJob(config);

        service.updateSchedule("job_a", "0 0 5 * * *", null);

        InOrder order = inOrder(taskScheduler, previous);
        order.verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(CronTrigger.class));
        order.verify(previous).cancel(false);
        assertThat(config.getCronExpression()).isEqualTo("0 0 5 * * *");
    }

    @Test
    @DisplayName("updateSchedule refuses a job whose stored type is unrecognised, changing nothing")
    void updateSchedule_unrecognisedType_refused() {
        SchedulerJobConfigEntity config = cron("job_a", "0 0 1 * * *");
        config.setScheduleTypeRaw("SOLAR_OFFSET");
        when(repository.findByJobKey("job_a")).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.updateSchedule("job_a", "0 0 5 * * *", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SOLAR_OFFSET");

        assertThat(config.getCronExpression()).isEqualTo("0 0 1 * * *");
        verify(repository, never()).save(any());
        verifyNoInteractions(taskScheduler);
    }

    @Test
    @DisplayName("resume of an unrecognised-type job neither throws nor flips status nor schedules")
    void resume_unrecognisedType_leftAlone() {
        SchedulerJobConfigEntity config = cron("job_a", "0 0 1 * * *");
        config.setScheduleTypeRaw("SOLAR_OFFSET");
        config.setStatus(SchedulerJobStatus.PAUSED);
        when(repository.findByJobKey("job_a")).thenReturn(Optional.of(config));

        SchedulerJobConfigEntity result = service.resume("job_a");

        assertThat(result.getStatus()).isEqualTo(SchedulerJobStatus.PAUSED);
        verify(repository, never()).save(any());
        verifyNoInteractions(taskScheduler);
    }

    @Test
    @DisplayName("an unrecognised-type job can still be paused")
    void pause_unrecognisedType_works() {
        SchedulerJobConfigEntity config = cron("job_a", "0 0 1 * * *");
        config.setScheduleTypeRaw("SOLAR_OFFSET");
        when(repository.findByJobKey("job_a")).thenReturn(Optional.of(config));

        service.pause("job_a");

        assertThat(config.getStatus()).isEqualTo(SchedulerJobStatus.PAUSED);
        verify(repository).save(config);
    }

    @Test
    @DisplayName("next fire time is null, not a throw, for an unrecognised type or a bad cron")
    void calculateNextFireTime_unschedulableRows_null() {
        SchedulerJobConfigEntity unknown = cron("job_a", "0 0 1 * * *");
        unknown.setScheduleTypeRaw("SOLAR_OFFSET");

        assertThat(service.calculateNextFireTime(unknown)).isNull();
        assertThat(service.calculateNextFireTime(cron("job_b", "not a cron"))).isNull();
    }

    @Test
    @DisplayName("a cron is evaluated in UTC even when the JVM default zone is Europe/London")
    void cronTrigger_isEvaluatedInUtc_regardlessOfJvmZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));
        SchedulerJobConfigEntity config = cron("job_a", "0 0 15 * * *");
        when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn(mock(ScheduledFuture.class));

        service.scheduleJob(config);

        ArgumentCaptor<CronTrigger> trigger = ArgumentCaptor.forClass(CronTrigger.class);
        verify(taskScheduler).schedule(any(Runnable.class), trigger.capture());
        // 1 July is BST: a London-zoned trigger would fire at 14:00Z, a UTC one at 15:00Z.
        Clock clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);
        Instant next = trigger.getValue().nextExecution(new SimpleTriggerContext(clock));
        assertThat(next).isEqualTo(Instant.parse("2026-07-01T15:00:00Z"));
    }

    private static SchedulerJobConfigEntity cron(String key, String expression) {
        return SchedulerJobConfigEntity.builder().id(1L).jobKey(key).displayName(key)
                .scheduleType(ScheduleType.CRON).cronExpression(expression)
                .status(SchedulerJobStatus.ACTIVE).build();
    }

    private static SchedulerJobConfigEntity delay(String key, Long delayMs) {
        return SchedulerJobConfigEntity.builder().id(2L).jobKey(key).displayName(key)
                .scheduleType(ScheduleType.FIXED_DELAY).fixedDelayMs(delayMs)
                .status(SchedulerJobStatus.ACTIVE).build();
    }
}
