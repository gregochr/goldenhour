package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.service.notification.AdminAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the forecast-cycle watchdog (solar-relative-refresh-plan B2): when it alerts, when it does not,
 * and that it alerts once. Times are 13 October 2026 (BST) at Durham, taken from the same calculator
 * the schedule uses, so no sunrise or sunset is hard-coded.
 */
class ForecastCycleWatchdogTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);
    private static final String NIGHTLY = ForecastCycleJobs.NIGHTLY_JOB;
    private static final String INTRADAY = ForecastCycleJobs.INTRADAY_JOB;
    private static final String EVENING = ForecastCycleJobs.EVENING_JOB;
    private static final Duration EVENING_BUDGET = Duration.ofHours(2);

    /** A clock the test can move. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final SolarTriggerFactory factory = new SolarTriggerFactory(new SolarService(),
            new SolarReferencePoint("54.776", "-1.575"));
    private final Instant sunrise = eventOf(SolarEvent.SUNRISE);
    private final Instant sunset = eventOf(SolarEvent.SUNSET);

    private SchedulerJobConfigRepository configs;
    private PipelineRunRepository runs;
    private ForecastBatchRepository batches;
    private AdminAlertService alerts;
    private DynamicSchedulerService scheduler;
    private MovableClock clock;
    private ForecastCycleWatchdog watchdog;

    private Instant eventOf(SolarEvent event) {
        return factory.create("x", event, 0, "0 0 1 * * *").fireFor(DAY).eventInstant();
    }

    @BeforeEach
    void setUp() {
        configs = mock(SchedulerJobConfigRepository.class);
        runs = mock(PipelineRunRepository.class);
        batches = mock(ForecastBatchRepository.class);
        alerts = mock(AdminAlertService.class);
        scheduler = mock(DynamicSchedulerService.class);
        clock = new MovableClock(Instant.parse("2026-10-13T01:45:00Z"));
        watchdog = new ForecastCycleWatchdog(configs, runs, batches, factory, alerts, scheduler, clock,
                EVENING_BUDGET);
        when(runs.findFirstByCycleTypeAndTriggerTimeBetweenOrderByTriggerTimeDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(runs.findByCycleTypeAndTriggerTimeGreaterThanEqualOrderByTriggerTimeAsc(any(), any()))
                .thenReturn(List.of());
        when(batches.findByPipelineRunId(any())).thenReturn(List.of());
    }

    private static SchedulerJobConfigEntity cronJob(String key, String cron, SchedulerJobStatus status) {
        return SchedulerJobConfigEntity.builder().jobKey(key).displayName(key)
                .scheduleType(ScheduleType.CRON).cronExpression(cron).status(status).build();
    }

    private static SchedulerJobConfigEntity solarJob(String key, SolarEvent event, int offset) {
        return SchedulerJobConfigEntity.builder().jobKey(key).displayName(key)
                .scheduleType(ScheduleType.SOLAR_OFFSET).solarEvent(event).solarOffsetMinutes(offset)
                .cronExpression("0 0 1 * * *").status(SchedulerJobStatus.ACTIVE).build();
    }

    private void nightly(SchedulerJobConfigEntity config) {
        when(configs.findByJobKey(NIGHTLY)).thenReturn(Optional.of(config));
        when(configs.findByJobKey(INTRADAY)).thenReturn(Optional.empty());
    }

    private PipelineRunEntity run(long id, CycleType cycle, Instant trigger, PipelineRunStatus status) {
        PipelineRunEntity run = new PipelineRunEntity(cycle, trigger);
        run.setId(id);
        run.setStatus(status);
        return run;
    }

    private static ForecastBatchEntity batch(BatchType type, BatchStatus status) {
        ForecastBatchEntity b = new ForecastBatchEntity("msgbatch_w", type, 3,
                Instant.parse("2026-10-14T00:00:00Z"));
        b.setStatus(status);
        return b;
    }

    private void verifyAlertedOnce(String jobKey, String problem) {
        verify(alerts, times(1)).sendForecastCycleWatchdogAlert(eq(jobKey), eq(problem), anyString());
    }

    // ---------------------------------------------------------------- did not start

    @Test
    @DisplayName("a CRON job whose fire was 45 minutes ago with no run since alerts once, not twice")
    void cronDidNotStart_alertsOnce() {
        nightly(cronJob(NIGHTLY, "0 0 1 * * *", SchedulerJobStatus.ACTIVE));

        watchdog.check();
        watchdog.check();

        verifyAlertedOnce(NIGHTLY, "did not start");
        verify(alerts).sendForecastCycleWatchdogAlert(eq(NIGHTLY), eq("did not start"),
                contains("02:00 UK time"));
        // the lookup is for a run triggered between the fire (01:00Z) and now
        verify(runs, times(2)).findFirstByCycleTypeAndTriggerTimeBetweenOrderByTriggerTimeDesc(
                CycleType.NIGHTLY, Instant.parse("2026-10-13T01:00:00Z"), clock.instant());
    }

    @Test
    @DisplayName("the message names the job, the fire and the served event in UK local time")
    void didNotStart_messageNamesJobFireAndEvent() {
        nightly(cronJob(NIGHTLY, "0 0 1 * * *", SchedulerJobStatus.ACTIVE));

        watchdog.check();

        verify(alerts).sendForecastCycleWatchdogAlert(eq(NIGHTLY), eq("did not start"),
                contains(NIGHTLY));
        verify(alerts).sendForecastCycleWatchdogAlert(eq(NIGHTLY), eq("did not start"),
                contains("Tue 13 Oct 02:00 UK time"));
        verify(alerts).sendForecastCycleWatchdogAlert(eq(NIGHTLY), eq("did not start"),
                contains("sunrise at Tue 13 Oct"));
    }

    @Test
    @DisplayName("no alert when a run of that cycle was triggered at or after the fire")
    void didNotStart_runExists_noAlert() {
        nightly(cronJob(NIGHTLY, "0 0 1 * * *", SchedulerJobStatus.ACTIVE));
        when(runs.findFirstByCycleTypeAndTriggerTimeBetweenOrderByTriggerTimeDesc(
                eq(CycleType.NIGHTLY), any(), any())).thenReturn(Optional.of(
                run(1, CycleType.NIGHTLY, Instant.parse("2026-10-13T01:00:03Z"),
                        PipelineRunStatus.COMPLETED)));

        watchdog.check();

        verify(alerts, never()).sendForecastCycleWatchdogAlert(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("no alert while the fire is still within 30 minutes, and one just past it")
    void didNotStart_graceBoundary() {
        nightly(cronJob(NIGHTLY, "0 0 1 * * *", SchedulerJobStatus.ACTIVE));

        clock.set(Instant.parse("2026-10-13T01:29:59Z"));
        watchdog.check();
        verify(alerts, never()).sendForecastCycleWatchdogAlert(anyString(), anyString(), anyString());

        clock.set(Instant.parse("2026-10-13T01:30:01Z"));
        watchdog.check();
        verifyAlertedOnce(NIGHTLY, "did not start");
    }

    @Test
    @DisplayName("a PAUSED or disabled job is never alerted about")
    void didNotStart_notActive_noAlert() {
        nightly(cronJob(NIGHTLY, "0 0 1 * * *", SchedulerJobStatus.PAUSED));

        watchdog.check();

        verify(alerts, never()).sendForecastCycleWatchdogAlert(anyString(), anyString(), anyString());
        verify(runs, never()).findFirstByCycleTypeAndTriggerTimeBetweenOrderByTriggerTimeDesc(
                any(), any(), any());
    }

    @Test
    @DisplayName("a fire older than the six-hour ceiling is ignored (an old outage, or a deploy)")
    void didNotStart_fireOlderThanCeiling_noAlert() {
        nightly(cronJob(NIGHTLY, "0 0 1 * * *", SchedulerJobStatus.ACTIVE));
        clock.set(Instant.parse("2026-10-13T07:30:00Z"));

        watchdog.check();

        verify(alerts, never()).sendForecastCycleWatchdogAlert(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a SOLAR_OFFSET job's own fire is used: alerts 31 minutes after it, not 25")
    void solarDidNotStart() {
        nightly(solarJob(NIGHTLY, SolarEvent.SUNRISE, 255));
        Instant fire = sunrise.minus(Duration.ofMinutes(255));

        clock.set(fire.plus(Duration.ofMinutes(25)));
        watchdog.check();
        verify(alerts, never()).sendForecastCycleWatchdogAlert(anyString(), anyString(), anyString());

        clock.set(fire.plus(Duration.ofMinutes(31)));
        watchdog.check();
        verifyAlertedOnce(NIGHTLY, "did not start");
        verify(runs).findFirstByCycleTypeAndTriggerTimeBetweenOrderByTriggerTimeDesc(
                CycleType.NIGHTLY, fire, clock.instant());
    }

    @Test
    @DisplayName("a SOLAR_OFFSET job with no fire yet today (before it) is not late")
    void solarBeforeFire_noAlert() {
        nightly(solarJob(NIGHTLY, SolarEvent.SUNRISE, 255));
        clock.set(sunrise.minus(Duration.ofMinutes(300)));

        watchdog.check();

        verify(alerts, never()).sendForecastCycleWatchdogAlert(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("the afternoon cycle is checked against its own cycle type and sunset")
    void intradayDidNotStart() {
        when(configs.findByJobKey(NIGHTLY)).thenReturn(Optional.empty());
        when(configs.findByJobKey(INTRADAY)).thenReturn(Optional.of(
                cronJob(INTRADAY, "0 0 14 * * *", SchedulerJobStatus.ACTIVE)));
        clock.set(Instant.parse("2026-10-13T14:45:00Z"));

        watchdog.check();

        verifyAlertedOnce(INTRADAY, "did not start");
        verify(alerts).sendForecastCycleWatchdogAlert(eq(INTRADAY), eq("did not start"),
                contains("INTRADAY"));
        verify(alerts).sendForecastCycleWatchdogAlert(eq(INTRADAY), eq("did not start"),
                contains("sunset"));
    }

    @Test
    @DisplayName("a solar job whose solar fire is unusable falls back to its stored cron, as the "
            + "scheduler does")
    void unusableSolarFire_usesStoredCron() {
        // Sunrise at 07:10 UK with a zero offset is after the 06:00 ceiling on a sunrise fire, so the
        // trigger refuses it and the scheduler would run the stored cron (14:00Z) instead.
        SchedulerJobConfigEntity config = solarJob(INTRADAY, SolarEvent.SUNRISE, 0);
        config.setCronExpression("0 0 14 * * *");
        when(configs.findByJobKey(NIGHTLY)).thenReturn(Optional.empty());
        when(configs.findByJobKey(INTRADAY)).thenReturn(Optional.of(config));
        clock.set(Instant.parse("2026-10-13T14:45:00Z"));

        watchdog.check();

        verifyAlertedOnce(INTRADAY, "did not start");
    }

    @Test
    @DisplayName("a FIXED_DELAY job, a blank cron and a cron too frequent to reason about are skipped")
    void unreasonableSchedules_areSkipped() {
        when(configs.findByJobKey(INTRADAY)).thenReturn(Optional.empty());

        SchedulerJobConfigEntity fixed = cronJob(NIGHTLY, null, SchedulerJobStatus.ACTIVE);
        fixed.setScheduleType(ScheduleType.FIXED_DELAY);
        when(configs.findByJobKey(NIGHTLY)).thenReturn(Optional.of(fixed));
        watchdog.check();

        when(configs.findByJobKey(NIGHTLY)).thenReturn(Optional.of(
                cronJob(NIGHTLY, " ", SchedulerJobStatus.ACTIVE)));
        watchdog.check();

        when(configs.findByJobKey(NIGHTLY)).thenReturn(Optional.of(
                cronJob(NIGHTLY, "* * * * * *", SchedulerJobStatus.ACTIVE)));
        watchdog.check();

        verify(alerts, never()).sendForecastCycleWatchdogAlert(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("the latch is bounded: the oldest key is forgotten after 64 others, the newest is not")
    void latchIsBounded() {
        nightly(cronJob(NIGHTLY, "0 0 1 * * *", SchedulerJobStatus.ACTIVE));
        Instant first = Instant.parse("2026-10-13T01:45:00Z");
        for (int day = 0; day < ForecastCycleWatchdog.LATCH_CAPACITY + 1; day++) {
            clock.set(first.plus(Duration.ofDays(day)));
            watchdog.check();
        }
        verify(alerts, times(ForecastCycleWatchdog.LATCH_CAPACITY + 1))
                .sendForecastCycleWatchdogAlert(eq(NIGHTLY), eq("did not start"), anyString());

        clock.set(first.plus(Duration.ofDays(ForecastCycleWatchdog.LATCH_CAPACITY)));
        watchdog.check();
        verify(alerts, times(ForecastCycleWatchdog.LATCH_CAPACITY + 1))
                .sendForecastCycleWatchdogAlert(eq(NIGHTLY), eq("did not start"), anyString());

        clock.set(first);
        watchdog.check();
        verify(alerts, times(ForecastCycleWatchdog.LATCH_CAPACITY + 2))
                .sendForecastCycleWatchdogAlert(eq(NIGHTLY), eq("did not start"), anyString());
    }

    // ---------------------------------------------------------------- did not finish

    private void runStarted(PipelineRunEntity run) {
        when(runs.findFirstByCycleTypeAndTriggerTimeBetweenOrderByTriggerTimeDesc(
                eq(run.getCycleType()), any(), any())).thenReturn(Optional.of(run));
        when(runs.findByCycleTypeAndTriggerTimeGreaterThanEqualOrderByTriggerTimeAsc(
                eq(run.getCycleType()), any())).thenReturn(List.of(run));
    }

    @Test
    @DisplayName("a run still RUNNING 15 minutes before sunrise alerts once, naming its phase")
    void runningNearSunrise_alertsOnce() {
        nightly(cronJob(NIGHTLY, "0 0 1 * * *", SchedulerJobStatus.ACTIVE));
        PipelineRunEntity running = run(9, CycleType.NIGHTLY, Instant.parse("2026-10-13T01:00:02Z"),
                PipelineRunStatus.RUNNING);
        ReflectionTestUtils.setField(running, "currentPhase", PipelinePhase.FORECAST_BATCH_WAIT);
        runStarted(running);
        clock.set(sunrise.minus(Duration.ofMinutes(15)));

        watchdog.check();
        watchdog.check();

        verifyAlertedOnce(NIGHTLY, "did not finish");
        verify(alerts).sendForecastCycleWatchdogAlert(eq(NIGHTLY), eq("did not finish"),
                contains("FORECAST_BATCH_WAIT"));
        verify(runs, times(2)).findByCycleTypeAndTriggerTimeGreaterThanEqualOrderByTriggerTimeAsc(
                CycleType.NIGHTLY, Instant.parse("2026-10-12T23:00:00Z"));
    }

    @Test
    @DisplayName("a finished run with a forecast batch still outstanding alerts, naming the batches")
    void nonTerminalBatchNearSunrise_alerts() {
        nightly(cronJob(NIGHTLY, "0 0 1 * * *", SchedulerJobStatus.ACTIVE));
        PipelineRunEntity done = run(9, CycleType.NIGHTLY, Instant.parse("2026-10-13T01:00:02Z"),
                PipelineRunStatus.FAILED);
        runStarted(done);
        when(batches.findByPipelineRunId(9L)).thenReturn(List.of(
                batch(BatchType.FORECAST, BatchStatus.COMPLETED),
                batch(BatchType.FORECAST, BatchStatus.SUBMITTED)));
        clock.set(sunrise);

        watchdog.check();

        verifyAlertedOnce(NIGHTLY, "did not finish");
        verify(alerts).sendForecastCycleWatchdogAlert(eq(NIGHTLY), eq("did not finish"),
                contains("1 forecast batch(es)"));
    }

    @Test
    @DisplayName("a COMPLETED run with every batch terminal never alerts, even at sunrise")
    void completedRun_noAlert() {
        nightly(cronJob(NIGHTLY, "0 0 1 * * *", SchedulerJobStatus.ACTIVE));
        PipelineRunEntity done = run(9, CycleType.NIGHTLY, Instant.parse("2026-10-13T01:00:02Z"),
                PipelineRunStatus.COMPLETED);
        runStarted(done);
        when(batches.findByPipelineRunId(9L)).thenReturn(List.of(
                batch(BatchType.FORECAST, BatchStatus.COMPLETED),
                batch(BatchType.AURORA, BatchStatus.SUBMITTED)));
        clock.set(sunrise);

        watchdog.check();

        verify(alerts, never()).sendForecastCycleWatchdogAlert(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("the window opens 20 minutes before the event and closes 60 minutes after it")
    void finishWindowBoundaries() {
        nightly(cronJob(NIGHTLY, "0 0 1 * * *", SchedulerJobStatus.ACTIVE));
        runStarted(run(9, CycleType.NIGHTLY, Instant.parse("2026-10-13T01:00:02Z"),
                PipelineRunStatus.RUNNING));

        clock.set(sunrise.minus(Duration.ofMinutes(21)));
        watchdog.check();
        clock.set(sunrise.plus(Duration.ofMinutes(61)));
        watchdog.check();
        verify(alerts, never()).sendForecastCycleWatchdogAlert(anyString(), anyString(), anyString());

        clock.set(sunrise.plus(Duration.ofMinutes(59)));
        watchdog.check();
        verifyAlertedOnce(NIGHTLY, "did not finish");
    }

    @Test
    @DisplayName("the afternoon cycle is measured against sunset")
    void intradayRunningNearSunset() {
        when(configs.findByJobKey(NIGHTLY)).thenReturn(Optional.empty());
        when(configs.findByJobKey(INTRADAY)).thenReturn(Optional.of(
                cronJob(INTRADAY, "0 0 14 * * *", SchedulerJobStatus.ACTIVE)));
        runStarted(run(11, CycleType.INTRADAY, Instant.parse("2026-10-13T14:00:01Z"),
                PipelineRunStatus.RUNNING));
        clock.set(sunset.minus(Duration.ofMinutes(10)));

        watchdog.check();

        verifyAlertedOnce(INTRADAY, "did not finish");
    }

    // ---------------------------------------------------------------- resilience

    @Test
    @DisplayName("a failure checking one job is swallowed and the other job is still checked")
    void exceptionIsSwallowed_otherJobStillChecked() {
        when(configs.findByJobKey(NIGHTLY)).thenThrow(new IllegalStateException("db down"));
        when(configs.findByJobKey(INTRADAY)).thenReturn(Optional.of(
                cronJob(INTRADAY, "0 0 14 * * *", SchedulerJobStatus.ACTIVE)));
        clock.set(Instant.parse("2026-10-13T14:45:00Z"));

        assertThatCode(watchdog::check).doesNotThrowAnyException();

        verifyAlertedOnce(INTRADAY, "did not start");
    }

    @Test
    @DisplayName("an unparseable stored cron is swallowed")
    void badCron_isSwallowed() {
        nightly(cronJob(NIGHTLY, "not a cron", SchedulerJobStatus.ACTIVE));

        assertThatCode(watchdog::check).doesNotThrowAnyException();

        verify(alerts, never()).sendForecastCycleWatchdogAlert(anyString(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- the evening refresh

    private void evening() {
        when(configs.findByJobKey(NIGHTLY)).thenReturn(Optional.empty());
        when(configs.findByJobKey(INTRADAY)).thenReturn(Optional.empty());
        when(configs.findByJobKey(EVENING)).thenReturn(Optional.of(
                cronJob(EVENING, "0 0 20 * * *", SchedulerJobStatus.ACTIVE)));
    }

    private static ForecastBatchEntity eveningBatch(BatchStatus status, String submittedAt) {
        ForecastBatchEntity b = batch(BatchType.FORECAST, status);
        b.setSubmittedAt(Instant.parse(submittedAt));
        return b;
    }

    @Test
    @DisplayName("an evening run inside its budget, with a batch still polling, is on time: no mail")
    void evening_onTime_noAlert() {
        evening();
        runStarted(run(30, CycleType.EVENING, Instant.parse("2026-10-13T20:00:05Z"),
                PipelineRunStatus.RUNNING));
        when(batches.findByPipelineRunId(30L)).thenReturn(List.of(
                eveningBatch(BatchStatus.SUBMITTED, "2026-10-13T20:05:00Z")));
        clock.set(Instant.parse("2026-10-13T21:30:00Z"));

        watchdog.check();

        verify(alerts, never()).sendForecastCycleWatchdogAlert(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("an evening fire 45 minutes ago with no EVENING run alerts once, and never asks for a "
            + "solar event")
    void evening_didNotStart_alertsOnce() {
        evening();
        clock.set(Instant.parse("2026-10-13T20:45:00Z"));

        watchdog.check();
        watchdog.check();

        verifyAlertedOnce(EVENING, "did not start");
        verify(alerts).sendForecastCycleWatchdogAlert(eq(EVENING), eq("did not start"),
                contains("EVENING"));
        verify(runs, times(2)).findFirstByCycleTypeAndTriggerTimeBetweenOrderByTriggerTimeDesc(
                CycleType.EVENING, Instant.parse("2026-10-13T20:00:00Z"), clock.instant());
    }

    private void londonEvening() {
        when(configs.findByJobKey(NIGHTLY)).thenReturn(Optional.empty());
        when(configs.findByJobKey(INTRADAY)).thenReturn(Optional.empty());
        SchedulerJobConfigEntity config = cronJob(EVENING, "0 0 21 * * *", SchedulerJobStatus.ACTIVE);
        config.setTimeZone("Europe/London");
        when(configs.findByJobKey(EVENING)).thenReturn(Optional.of(config));
    }

    @Test
    @DisplayName("a London evening job at 21:00 UK: 21:45 GMT with no run alerts for the 21:00Z fire")
    void eveningLondon_didNotStartInWinter_alerts() {
        londonEvening();
        clock.set(Instant.parse("2026-12-10T21:45:00Z"));

        watchdog.check();

        verifyAlertedOnce(EVENING, "did not start");
        verify(runs).findFirstByCycleTypeAndTriggerTimeBetweenOrderByTriggerTimeDesc(
                CycleType.EVENING, Instant.parse("2026-12-10T21:00:00Z"), clock.instant());
    }

    @Test
    @DisplayName("a London evening job at 21:00 UK: 21:45 BST with no run alerts for the 20:00Z fire")
    void eveningLondon_didNotStartInSummer_alerts() {
        londonEvening();
        clock.set(Instant.parse("2026-07-10T20:45:00Z"));

        watchdog.check();

        verifyAlertedOnce(EVENING, "did not start");
        verify(runs).findFirstByCycleTypeAndTriggerTimeBetweenOrderByTriggerTimeDesc(
                CycleType.EVENING, Instant.parse("2026-07-10T20:00:00Z"), clock.instant());
    }

    @Test
    @DisplayName("a London evening job whose 21:00 UK run started is not alerted, winter or summer")
    void eveningLondon_runStarted_noAlert() {
        londonEvening();
        runStarted(run(40, CycleType.EVENING, Instant.parse("2026-12-10T21:00:05Z"),
                PipelineRunStatus.COMPLETED));
        clock.set(Instant.parse("2026-12-10T21:45:00Z"));

        watchdog.check();

        verify(alerts, never()).sendForecastCycleWatchdogAlert(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a London evening job just before its 21:00 UK fire has no fire to judge yet in winter")
    void eveningLondon_beforeTodaysFire_judgesYesterdaysFire() {
        londonEvening();
        // 20:30Z in December is 20:30 UK: today's 21:00 fire has not happened, so the 20:00Z a UTC
        // reading would have used must not trigger a "did not start" for it.
        clock.set(Instant.parse("2026-12-10T20:30:00Z"));

        watchdog.check();

        verify(runs, never()).findFirstByCycleTypeAndTriggerTimeBetweenOrderByTriggerTimeDesc(
                eq(CycleType.EVENING), eq(Instant.parse("2026-12-10T20:00:00Z")), any());
    }

    @Test
    @DisplayName("an evening run failed by the safety timeout alerts once, quoting the reason")
    void evening_timedOut_alertsOnce() {
        evening();
        PipelineRunEntity failed = run(31, CycleType.EVENING, Instant.parse("2026-10-13T20:00:05Z"),
                PipelineRunStatus.FAILED);
        failed.setFailureReason("Safety timeout: Batch set did not reach terminal status within PT2H");
        runStarted(failed);
        clock.set(Instant.parse("2026-10-13T22:10:00Z"));

        watchdog.check();
        watchdog.check();

        verifyAlertedOnce(EVENING, "did not finish");
        verify(alerts).sendForecastCycleWatchdogAlert(eq(EVENING), eq("did not finish"),
                contains("safety timeout"));
    }

    @Test
    @DisplayName("an evening run failed for any other reason is not the watchdog's alert")
    void evening_otherFailure_noAlert() {
        evening();
        PipelineRunEntity failed = run(32, CycleType.EVENING, Instant.parse("2026-10-13T20:00:05Z"),
                PipelineRunStatus.FAILED);
        failed.setFailureReason("Submit phase failed: boom");
        runStarted(failed);
        clock.set(Instant.parse("2026-10-13T22:10:00Z"));

        watchdog.check();

        verify(alerts, never()).sendForecastCycleWatchdogAlert(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a batch still outside TERMINAL once the budget has passed alerts, measured from the "
            + "first batch submit")
    void evening_batchPollingPastBudget_alerts() {
        evening();
        runStarted(run(33, CycleType.EVENING, Instant.parse("2026-10-13T20:00:05Z"),
                PipelineRunStatus.RUNNING));
        when(batches.findByPipelineRunId(33L)).thenReturn(List.of(
                eveningBatch(BatchStatus.SUBMITTED, "2026-10-13T20:10:00Z"),
                eveningBatch(BatchStatus.COMPLETED, "2026-10-13T20:20:00Z")));

        clock.set(Instant.parse("2026-10-13T22:09:00Z"));
        watchdog.check();
        verify(alerts, never()).sendForecastCycleWatchdogAlert(anyString(), anyString(), anyString());

        clock.set(Instant.parse("2026-10-13T22:11:00Z"));
        watchdog.check();
        watchdog.check();

        verifyAlertedOnce(EVENING, "did not finish");
        verify(alerts).sendForecastCycleWatchdogAlert(eq(EVENING), eq("did not finish"),
                contains("1 forecast batch(es)"));
    }

    @Test
    @DisplayName("a run still RUNNING past its budget with every batch terminal is in its briefing "
            + "build: no mail")
    void evening_runningPastBudgetAllBatchesTerminal_noAlert() {
        evening();
        runStarted(run(34, CycleType.EVENING, Instant.parse("2026-10-13T20:00:05Z"),
                PipelineRunStatus.RUNNING));
        when(batches.findByPipelineRunId(34L)).thenReturn(List.of(
                eveningBatch(BatchStatus.COMPLETED, "2026-10-13T20:10:00Z")));
        clock.set(Instant.parse("2026-10-13T23:00:00Z"));

        watchdog.check();

        verify(alerts, never()).sendForecastCycleWatchdogAlert(anyString(), anyString(), anyString());
    }

    private ForecastCycleWatchdog watchdogWithEveningBudget(Duration budget) {
        return new ForecastCycleWatchdog(configs, runs, batches, factory, alerts, scheduler, clock, budget);
    }

    @Test
    @DisplayName("a summer evening run whose PT3H30M budget ends after UK midnight is still found, "
            + "and alerts once while its batch is polling")
    void evening_batchPollingAfterUkMidnight_alertsOnce() {
        evening();
        Duration budget = Duration.ofMinutes(210);
        PipelineRunEntity run = run(40, CycleType.EVENING, Instant.parse("2026-07-15T20:00:05Z"),
                PipelineRunStatus.RUNNING);
        runStarted(run);
        when(batches.findByPipelineRunId(40L)).thenReturn(List.of(
                eveningBatch(BatchStatus.SUBMITTED, "2026-07-15T20:00:30Z")));
        clock.set(Instant.parse("2026-07-15T23:40:00Z")); // 00:40 BST on the 16th

        ForecastCycleWatchdog wd = watchdogWithEveningBudget(budget);
        wd.check();
        wd.check();

        verifyAlertedOnce(EVENING, "did not finish");
        // lower bound is now - budget - 1h, earlier than the UK day start (15 Jul 23:00Z)
        verify(runs, times(2)).findByCycleTypeAndTriggerTimeGreaterThanEqualOrderByTriggerTimeAsc(
                CycleType.EVENING, Instant.parse("2026-07-15T19:10:00Z"));
    }

    @Test
    @DisplayName("a timeout-FAILED evening run seen after UK midnight alerts once")
    void evening_timedOutSeenAfterUkMidnight_alertsOnce() {
        evening();
        PipelineRunEntity failed = run(41, CycleType.EVENING, Instant.parse("2026-07-15T20:00:05Z"),
                PipelineRunStatus.FAILED);
        failed.setFailureReason("Safety timeout: Batch set did not reach terminal status within PT3H30M");
        runStarted(failed);
        clock.set(Instant.parse("2026-07-15T23:45:00Z"));

        ForecastCycleWatchdog wd = watchdogWithEveningBudget(Duration.ofMinutes(210));
        wd.check();
        wd.check();

        verifyAlertedOnce(EVENING, "did not finish");
    }

    @Test
    @DisplayName("the evening look-back never reaches a run from two evenings ago")
    void evening_lookBackExcludesTwoEveningsAgo() {
        evening();
        clock.set(Instant.parse("2026-07-16T12:00:00Z"));

        watchdogWithEveningBudget(Duration.ofMinutes(210)).check();

        // now - 3h30 - 1h = 07:30Z on the 16th (the UK day start, 15 Jul 23:00Z, is earlier here),
        // so the 14 Jul 20:00Z run is outside the query either way
        verify(runs).findByCycleTypeAndTriggerTimeGreaterThanEqualOrderByTriggerTimeAsc(
                CycleType.EVENING, Instant.parse("2026-07-15T23:00:00Z"));
    }

    @Test
    @DisplayName("with no batch submitted the budget runs from the trigger time")
    void evening_noBatch_budgetFromTrigger() {
        evening();
        runStarted(run(35, CycleType.EVENING, Instant.parse("2026-10-13T20:00:05Z"),
                PipelineRunStatus.RUNNING));
        clock.set(Instant.parse("2026-10-13T23:00:00Z"));

        watchdog.check();

        verify(alerts, never()).sendForecastCycleWatchdogAlert(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("registerJob registers the check under the seeded job key")
    void registersUnderJobKey() {
        watchdog.registerJob();

        verify(scheduler).registerJobTarget(eq(ForecastCycleWatchdog.JOB_KEY), any(Runnable.class));
    }
}
