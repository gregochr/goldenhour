package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SolarEvent;
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
import org.springframework.scheduling.support.CronTrigger;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the scheduler's handling of {@code SOLAR_OFFSET} (solar-relative-refresh-plan A3 and D1):
 * isolation of a bad solar row, the type-switch endpoint's validate-first ordering, and the startup
 * catch-up. The real {@link SolarTriggerFactory} is used; the catch-up policy is mocked because its
 * own rule is pinned in {@code SolarCatchUpPolicyTest}.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class DynamicSchedulerSolarTest {

    private static final String NIGHTLY = ForecastCycleJobs.NIGHTLY_JOB;
    private static final String INTRADAY = ForecastCycleJobs.INTRADAY_JOB;
    private static final String CRON = "0 0 1 * * *";
    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private static final java.time.Clock CLOCK = java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC);

    @Mock
    private SchedulerJobConfigRepository repository;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private AuroraProperties auroraProperties;

    @Mock
    private SolarCatchUpPolicy catchUp;

    @Mock
    private ScheduledFuture<?> future;

    private DynamicSchedulerService service;

    @BeforeEach
    void setUp() {
        SolarTriggerFactory factory = new SolarTriggerFactory(new SolarService(),
                new SolarReferencePoint("54.776", "-1.575"));
        service = new DynamicSchedulerService(repository, taskScheduler, auroraProperties, factory,
                catchUp, CLOCK);
        service.registerJobTarget(NIGHTLY, () -> { });
        service.registerJobTarget(INTRADAY, () -> { });
        service.registerJobTarget("tide_refresh", () -> { });
    }

    private static SchedulerJobConfigEntity.SchedulerJobConfigEntityBuilder row(long id, String key) {
        return SchedulerJobConfigEntity.builder().id(id).jobKey(key).displayName(key)
                .status(SchedulerJobStatus.ACTIVE).cronExpression(CRON);
    }

    private static SchedulerJobConfigEntity cronRow(long id, String key) {
        return row(id, key).scheduleType(ScheduleType.CRON).build();
    }

    private static SchedulerJobConfigEntity solarRow(long id, String key, SolarEvent event,
            Integer offset) {
        return row(id, key).scheduleType(ScheduleType.SOLAR_OFFSET).solarEvent(event)
                .solarOffsetMinutes(offset).build();
    }

    private void acceptAnySchedule() {
        when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class)))
                .thenReturn((ScheduledFuture) future);
    }

    // ---------------------------------------------------------------------------------------
    // Installing a solar job; validation in prepare()
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("an ACTIVE SOLAR_OFFSET row is installed with a SolarOffsetTrigger carrying its rule")
    void initSchedules_solarRow_installsSolarTrigger() {
        when(repository.findAllByOrderByIdAsc())
                .thenReturn(List.of(solarRow(1, NIGHTLY, SolarEvent.SUNRISE, 255)));
        acceptAnySchedule();

        service.initSchedules();

        ArgumentCaptor<Trigger> trigger = ArgumentCaptor.forClass(Trigger.class);
        verify(taskScheduler).schedule(any(Runnable.class), trigger.capture());
        assertThat(trigger.getValue()).isInstanceOf(SolarOffsetTrigger.class);
        SolarOffsetTrigger solar = (SolarOffsetTrigger) trigger.getValue();
        assertThat(solar.event()).isEqualTo(SolarEvent.SUNRISE);
        assertThat(solar.offsetMinutes()).isEqualTo(255);
    }

    @Test
    @DisplayName("incomplete or invalid solar rows are left unscheduled alone: the rows after them "
            + "are still scheduled")
    void initSchedules_badSolarRows_areIsolated() {
        SchedulerJobConfigEntity noEvent = solarRow(1, NIGHTLY, null, 255);
        SchedulerJobConfigEntity noOffset = solarRow(2, INTRADAY, SolarEvent.SUNSET, null);
        SchedulerJobConfigEntity tooBig = row(3, "other_a").scheduleType(ScheduleType.SOLAR_OFFSET)
                .solarEvent(SolarEvent.SUNSET).solarOffsetMinutes(721).build();
        SchedulerJobConfigEntity noCron = row(4, "other_b").scheduleType(ScheduleType.SOLAR_OFFSET)
                .solarEvent(SolarEvent.SUNSET).solarOffsetMinutes(240).cronExpression(null).build();
        SchedulerJobConfigEntity badCron = row(5, "other_c").scheduleType(ScheduleType.SOLAR_OFFSET)
                .solarEvent(SolarEvent.SUNSET).solarOffsetMinutes(240).cronExpression("nope").build();
        SchedulerJobConfigEntity good = cronRow(6, "tide_refresh");
        for (String key : List.of("other_a", "other_b", "other_c")) {
            service.registerJobTarget(key, () -> { });
        }
        when(repository.findAllByOrderByIdAsc())
                .thenReturn(List.of(noEvent, noOffset, tooBig, noCron, badCron, good));
        acceptAnySchedule();

        service.initSchedules();

        ArgumentCaptor<Trigger> trigger = ArgumentCaptor.forClass(Trigger.class);
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), trigger.capture());
        assertThat(trigger.getValue()).isInstanceOf(CronTrigger.class);
        verifyNoInteractions(catchUp);
    }

    @Test
    @DisplayName("calculateNextFireTime for a solar row is the trigger's own next fire, strictly "
            + "in the future; an incomplete row has none")
    void calculateNextFireTime_solarRow() {
        SolarOffsetTrigger expected = new SolarTriggerFactory(new SolarService(),
                new SolarReferencePoint("54.776", "-1.575"))
                .create(NIGHTLY, SolarEvent.SUNRISE, 255, CRON);

        Instant next = service.calculateNextFireTime(solarRow(1, NIGHTLY, SolarEvent.SUNRISE, 255));

        // The injected clock (13 Oct 10:00Z, after that morning's fire) decides: the next fire is
        // the 14th's, exactly as the trigger itself computes it.
        assertThat(next).isEqualTo(expected.fireFor(java.time.LocalDate.of(2026, 10, 14)).fire());
        assertThat(service.calculateNextFireTime(solarRow(2, NIGHTLY, null, 255))).isNull();
        assertThat(service.calculateNextFireTime(solarRow(3, NIGHTLY, SolarEvent.SUNRISE, null))).isNull();
    }

    @Test
    @DisplayName("updateSchedule on a solar job may correct the fallback cron and ignores the "
            + "fixedDelayMs the old admin screen sends; the solar trigger stays installed")
    void updateSchedule_solarRow() {
        SchedulerJobConfigEntity solar = solarRow(1, NIGHTLY, SolarEvent.SUNRISE, 255);
        when(repository.findByJobKey(NIGHTLY)).thenReturn(Optional.of(solar));
        acceptAnySchedule();

        SchedulerJobConfigEntity updated = service.updateSchedule(NIGHTLY, "0 30 1 * * *", 0L);

        assertThat(updated.getCronExpression()).isEqualTo("0 30 1 * * *");
        assertThat(updated.getFixedDelayMs()).isNull();
        ArgumentCaptor<Trigger> trigger = ArgumentCaptor.forClass(Trigger.class);
        verify(taskScheduler).schedule(any(Runnable.class), trigger.capture());
        assertThat(trigger.getValue()).isInstanceOf(SolarOffsetTrigger.class);
        assertThatThrownBy(() -> service.updateSchedule(NIGHTLY, "bad cron", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------------------
    // switchScheduleType
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("switching to SOLAR_OFFSET writes the three columns once, installs the solar "
            + "trigger BEFORE cancelling the old future, and leaves the cron alone")
    void switch_toSolar_installsNewBeforeCancellingOld() {
        SchedulerJobConfigEntity before = cronRow(7, NIGHTLY);
        SchedulerJobConfigEntity after = solarRow(7, NIGHTLY, SolarEvent.SUNRISE, 255);
        ScheduledFuture oldFuture = mock(ScheduledFuture.class);
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(before));
        when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn(oldFuture);
        service.initSchedules();
        when(repository.findByJobKey(NIGHTLY)).thenReturn(Optional.of(before), Optional.of(after));
        when(repository.updateScheduleType(eq(7L), eq("SOLAR_OFFSET"), eq("SUNRISE"), eq(255),
                any(Instant.class))).thenReturn(1);
        when(taskScheduler.schedule(any(Runnable.class), any(SolarOffsetTrigger.class)))
                .thenReturn((ScheduledFuture) future);

        SchedulerJobConfigEntity result = service.switchScheduleType(NIGHTLY, ScheduleType.SOLAR_OFFSET,
                SolarEvent.SUNRISE, 255);

        assertThat(result.getScheduleType()).isEqualTo(ScheduleType.SOLAR_OFFSET);
        assertThat(result.getCronExpression()).as("the cron is the fallback and is kept").isEqualTo(CRON);
        InOrder order = inOrder(repository, taskScheduler, oldFuture);
        order.verify(repository).updateScheduleType(eq(7L), eq("SOLAR_OFFSET"), eq("SUNRISE"), eq(255),
                any(Instant.class));
        order.verify(taskScheduler).schedule(any(Runnable.class), any(SolarOffsetTrigger.class));
        order.verify(oldFuture).cancel(false);
    }

    @Test
    @DisplayName("a request that fails validation touches nothing: no write, no new schedule, the "
            + "live future is not cancelled")
    void switch_invalidRequests_changeNothing() {
        SchedulerJobConfigEntity before = cronRow(7, NIGHTLY);
        ScheduledFuture oldFuture = mock(ScheduledFuture.class);
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(before));
        when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn(oldFuture);
        service.initSchedules();
        when(repository.findByJobKey(NIGHTLY)).thenReturn(Optional.of(before));

        assertThatThrownBy(() -> service.switchScheduleType(NIGHTLY, ScheduleType.SOLAR_OFFSET, null, 255))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.switchScheduleType(NIGHTLY, ScheduleType.SOLAR_OFFSET,
                SolarEvent.SUNRISE, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.switchScheduleType(NIGHTLY, ScheduleType.SOLAR_OFFSET,
                SolarEvent.SUNRISE, 721)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.switchScheduleType(NIGHTLY, ScheduleType.SOLAR_OFFSET,
                SolarEvent.SUNRISE, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.switchScheduleType(NIGHTLY, ScheduleType.FIXED_DELAY, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.switchScheduleType(NIGHTLY, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).updateScheduleType(anyLong(), any(), any(), any(), any());
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));
        verify(oldFuture, never()).cancel(false);
    }

    @Test
    @DisplayName("only the two forecast cycle jobs may become SOLAR_OFFSET")
    void switch_toSolar_isAllowListed() {
        SchedulerJobConfigEntity tide = cronRow(9, "tide_refresh");
        when(repository.findByJobKey("tide_refresh")).thenReturn(Optional.of(tide));

        assertThatThrownBy(() -> service.switchScheduleType("tide_refresh", ScheduleType.SOLAR_OFFSET,
                SolarEvent.SUNRISE, 255))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tide_refresh");

        verify(repository, never()).updateScheduleType(anyLong(), any(), any(), any(), any());
        verifyNoInteractions(taskScheduler);
    }

    @Test
    @DisplayName("the evening refresh may not become SOLAR_OFFSET: it is a plain CRON job")
    void switch_eveningToSolar_isRefused() {
        String evening = ForecastCycleJobs.EVENING_JOB;
        when(repository.findByJobKey(evening)).thenReturn(Optional.of(cronRow(10, evening)));

        assertThatThrownBy(() -> service.switchScheduleType(evening, ScheduleType.SOLAR_OFFSET,
                SolarEvent.SUNRISE, 255))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(evening);

        verify(repository, never()).updateScheduleType(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("switching back to CRON requires a stored, valid cron, and writes nothing otherwise")
    void switch_toCron_needsValidStoredCron() {
        SchedulerJobConfigEntity noCron = row(7, NIGHTLY).scheduleType(ScheduleType.SOLAR_OFFSET)
                .solarEvent(SolarEvent.SUNRISE).solarOffsetMinutes(255).cronExpression(null).build();
        SchedulerJobConfigEntity badCron = row(8, INTRADAY).scheduleType(ScheduleType.SOLAR_OFFSET)
                .solarEvent(SolarEvent.SUNSET).solarOffsetMinutes(240).cronExpression("garbage").build();
        when(repository.findByJobKey(NIGHTLY)).thenReturn(Optional.of(noCron));
        when(repository.findByJobKey(INTRADAY)).thenReturn(Optional.of(badCron));

        assertThatThrownBy(() -> service.switchScheduleType(NIGHTLY, ScheduleType.CRON, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.switchScheduleType(INTRADAY, ScheduleType.CRON, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).updateScheduleType(anyLong(), any(), any(), any(), any());
        verifyNoInteractions(taskScheduler);
    }

    @Test
    @DisplayName("round trip CRON -> SOLAR_OFFSET -> CRON: the second switch clears the solar "
            + "fields, installs a CronTrigger and the cron string is never changed")
    void switch_roundTrip_keepsCron() {
        SchedulerJobConfigEntity cron = cronRow(7, NIGHTLY);
        SchedulerJobConfigEntity solar = solarRow(7, NIGHTLY, SolarEvent.SUNRISE, 255);
        SchedulerJobConfigEntity backToCron = cronRow(7, NIGHTLY);
        when(repository.findByJobKey(NIGHTLY)).thenReturn(Optional.of(cron), Optional.of(solar),
                Optional.of(solar), Optional.of(backToCron));
        when(repository.updateScheduleType(eq(7L), any(), any(), any(), any())).thenReturn(1);
        acceptAnySchedule();

        service.switchScheduleType(NIGHTLY, ScheduleType.SOLAR_OFFSET, SolarEvent.SUNRISE, 255);
        SchedulerJobConfigEntity result = service.switchScheduleType(NIGHTLY, ScheduleType.CRON,
                SolarEvent.SUNSET, 30);

        assertThat(result.getCronExpression()).isEqualTo(CRON);
        verify(repository).updateScheduleType(eq(7L), eq("CRON"), eq(null), eq(null), any(Instant.class));
        ArgumentCaptor<Trigger> triggers = ArgumentCaptor.forClass(Trigger.class);
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), triggers.capture());
        assertThat(triggers.getAllValues().get(0)).isInstanceOf(SolarOffsetTrigger.class);
        assertThat(triggers.getAllValues().get(1)).isInstanceOf(CronTrigger.class);
    }

    @Test
    @DisplayName("a failed database update leaves the live schedule untouched")
    void switch_databaseFailure_leavesLiveScheduleUntouched() {
        SchedulerJobConfigEntity before = cronRow(7, NIGHTLY);
        ScheduledFuture oldFuture = mock(ScheduledFuture.class);
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(before));
        when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn(oldFuture);
        service.initSchedules();
        when(repository.findByJobKey(NIGHTLY)).thenReturn(Optional.of(before));
        when(repository.updateScheduleType(eq(7L), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.switchScheduleType(NIGHTLY, ScheduleType.SOLAR_OFFSET,
                SolarEvent.SUNRISE, 255)).hasMessage("db down");

        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));
        verify(oldFuture, never()).cancel(false);
    }

    @Test
    @DisplayName("if installing fails after the type was stored, the stored type is put back, the "
            + "old future keeps running and the call fails")
    void switch_installFailure_restoresStoredType() {
        SchedulerJobConfigEntity before = cronRow(7, NIGHTLY);
        SchedulerJobConfigEntity after = solarRow(7, NIGHTLY, SolarEvent.SUNRISE, 255);
        ScheduledFuture oldFuture = mock(ScheduledFuture.class);
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(before));
        when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn(oldFuture);
        service.initSchedules();
        when(repository.findByJobKey(NIGHTLY)).thenReturn(Optional.of(before), Optional.of(after));
        when(repository.updateScheduleType(eq(7L), any(), any(), any(), any())).thenReturn(1);
        when(taskScheduler.schedule(any(Runnable.class), any(SolarOffsetTrigger.class)))
                .thenThrow(new IllegalStateException("scheduler rejected"));

        assertThatThrownBy(() -> service.switchScheduleType(NIGHTLY, ScheduleType.SOLAR_OFFSET,
                SolarEvent.SUNRISE, 255)).hasMessage("scheduler rejected");

        verify(repository).updateScheduleType(eq(7L), eq("CRON"), eq(null), eq(null), any(Instant.class));
        verify(oldFuture, never()).cancel(false);
    }

    @Test
    @DisplayName("a PAUSED job has its type stored but nothing is scheduled")
    void switch_pausedJob_storesButDoesNotSchedule() {
        SchedulerJobConfigEntity paused = row(7, NIGHTLY).scheduleType(ScheduleType.CRON)
                .status(SchedulerJobStatus.PAUSED).build();
        SchedulerJobConfigEntity pausedSolar = row(7, NIGHTLY).scheduleType(ScheduleType.SOLAR_OFFSET)
                .solarEvent(SolarEvent.SUNRISE).solarOffsetMinutes(255)
                .status(SchedulerJobStatus.PAUSED).build();
        when(repository.findByJobKey(NIGHTLY)).thenReturn(Optional.of(paused), Optional.of(pausedSolar));
        when(repository.updateScheduleType(eq(7L), eq("SOLAR_OFFSET"), eq("SUNRISE"), eq(255),
                any(Instant.class))).thenReturn(1);

        SchedulerJobConfigEntity result = service.switchScheduleType(NIGHTLY, ScheduleType.SOLAR_OFFSET,
                SolarEvent.SUNRISE, 255);

        assertThat(result.getStatus()).isEqualTo(SchedulerJobStatus.PAUSED);
        verifyNoInteractions(taskScheduler);
    }

    @Test
    @DisplayName("an unknown job key is a NoSuchElementException (404), nothing written")
    void switch_unknownJob_isNotFound() {
        when(repository.findByJobKey("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.switchScheduleType("nope", ScheduleType.CRON, null, null))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ---------------------------------------------------------------------------------------
    // Catch-up on startup (D1)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("catch-up due: one immediate scheduled (not manual) run is queued after the normal "
            + "schedule is installed")
    void catchUp_due_queuesOneImmediateScheduledRun() {
        SchedulerJobConfigEntity solar = solarRow(1, NIGHTLY, SolarEvent.SUNRISE, 255);
        AtomicReference<Boolean> manualFlag = new AtomicReference<>();
        service.registerManualAwareJobTarget(NIGHTLY, manualFlag::set);
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(solar));
        acceptAnySchedule();
        when(catchUp.reasonToRun(solar)).thenReturn(Optional.of("fire passed"));
        when(repository.findByJobKey(NIGHTLY)).thenReturn(Optional.of(solar));
        Instant before = Instant.now();

        service.initSchedules();

        ArgumentCaptor<Runnable> run = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        InOrder order = inOrder(taskScheduler);
        order.verify(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
        order.verify(taskScheduler).schedule(run.capture(), at.capture());
        assertThat(at.getValue()).isBetween(before, Instant.now());
        run.getValue().run();
        assertThat(manualFlag.get()).as("a catch-up run is a scheduled run, not a manual one").isFalse();
    }

    @Test
    @DisplayName("catch-up not due: nothing is queued beyond the normal schedule")
    void catchUp_notDue_queuesNothing() {
        SchedulerJobConfigEntity solar = solarRow(1, NIGHTLY, SolarEvent.SUNRISE, 255);
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(solar));
        acceptAnySchedule();
        when(catchUp.reasonToRun(solar)).thenReturn(Optional.empty());

        service.initSchedules();

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        verify(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    @DisplayName("a PAUSED solar job and a CRON job are never even considered for catch-up")
    void catchUp_pausedAndCronJobs_notConsulted() {
        SchedulerJobConfigEntity paused = row(1, NIGHTLY).scheduleType(ScheduleType.SOLAR_OFFSET)
                .solarEvent(SolarEvent.SUNRISE).solarOffsetMinutes(255)
                .status(SchedulerJobStatus.PAUSED).build();
        SchedulerJobConfigEntity cron = cronRow(2, INTRADAY);
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(paused, cron));
        acceptAnySchedule();

        service.initSchedules();

        verifyNoInteractions(catchUp);
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("an exception in the catch-up check never prevents the normal schedule or the "
            + "other rows being installed")
    void catchUp_failure_doesNotStopScheduling() {
        SchedulerJobConfigEntity solar = solarRow(1, NIGHTLY, SolarEvent.SUNRISE, 255);
        SchedulerJobConfigEntity cron = cronRow(2, INTRADAY);
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(solar, cron));
        acceptAnySchedule();
        when(catchUp.reasonToRun(solar)).thenThrow(new IllegalStateException("pipeline_run unreadable"));

        service.initSchedules();

        ArgumentCaptor<Trigger> triggers = ArgumentCaptor.forClass(Trigger.class);
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), triggers.capture());
        assertThat(triggers.getAllValues().get(0)).isInstanceOf(SolarOffsetTrigger.class);
        assertThat(triggers.getAllValues().get(1)).isInstanceOf(CronTrigger.class);
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("a failing scheduler during catch-up queueing is also swallowed")
    void catchUp_schedulerRejects_isSwallowed() {
        SchedulerJobConfigEntity solar = solarRow(1, NIGHTLY, SolarEvent.SUNRISE, 255);
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(solar));
        acceptAnySchedule();
        when(catchUp.reasonToRun(solar)).thenReturn(Optional.of("due"));
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new IllegalStateException("pool shut"));

        service.initSchedules();

        verify(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    @DisplayName("if restoring the stored type also fails, the original install exception is "
            + "thrown with the restore failure attached as suppressed")
    void switch_restoreAlsoFails_keepsOriginalWithSuppressed() {
        SchedulerJobConfigEntity before = cronRow(7, NIGHTLY);
        SchedulerJobConfigEntity after = solarRow(7, NIGHTLY, SolarEvent.SUNRISE, 255);
        when(repository.findByJobKey(NIGHTLY)).thenReturn(Optional.of(before), Optional.of(after));
        when(repository.updateScheduleType(eq(7L), eq("SOLAR_OFFSET"), eq("SUNRISE"), eq(255),
                any(Instant.class))).thenReturn(1);
        IllegalStateException restoreFailure = new IllegalStateException("db gone");
        when(repository.updateScheduleType(eq(7L), eq("CRON"), eq(null), eq(null), any(Instant.class)))
                .thenThrow(restoreFailure);
        when(taskScheduler.schedule(any(Runnable.class), any(SolarOffsetTrigger.class)))
                .thenThrow(new IllegalStateException("scheduler rejected"));

        assertThatThrownBy(() -> service.switchScheduleType(NIGHTLY, ScheduleType.SOLAR_OFFSET,
                SolarEvent.SUNRISE, 255))
                .hasMessage("scheduler rejected")
                .hasSuppressedException(restoreFailure);
    }
}
