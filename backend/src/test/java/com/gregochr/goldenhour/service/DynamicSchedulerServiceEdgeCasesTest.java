package com.gregochr.goldenhour.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerFireLogEntity;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.entity.SchedulerFireTrigger;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.repository.SchedulerFireLogRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Edge cases of {@link DynamicSchedulerService}: schedules that never fire or cannot be built, a job with no
 * registered target, the audit trail of a manual run, fire-log summaries, and config-gated status that is
 * already where the flag wants it.
 */
@ExtendWith(MockitoExtension.class)
class DynamicSchedulerServiceEdgeCasesTest {

    private static final Instant NOW = Instant.parse("2027-03-05T12:00:00Z");
    private static final String KEY = "tide_refresh";

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

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private DynamicSchedulerService service;

    @BeforeEach
    void setUp() {
        service = new DynamicSchedulerService(repository, fireLog, taskScheduler, auroraProperties,
                solarTriggers, solarCatchUp, Clock.fixed(NOW, ZoneOffset.UTC));
        logs.start();
        ((Logger) LoggerFactory.getLogger(DynamicSchedulerService.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(DynamicSchedulerService.class)).detachAppender(logs);
        SecurityContextHolder.clearContext();
    }

    private static SchedulerJobConfigEntity.SchedulerJobConfigEntityBuilder job(ScheduleType type) {
        return SchedulerJobConfigEntity.builder().id(1L).jobKey(KEY).displayName(KEY).scheduleType(type)
                .status(SchedulerJobStatus.ACTIVE).updatedAt(NOW);
    }

    private boolean logged(String fragment) {
        return logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains(fragment));
    }

    // ── Orphan sweep ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the orphan sweep stays quiet when no fire was left running, and says how many when some were")
    void sweepOrphanedFires_logsOnlyWhenSomethingWasSwept() {
        when(fireLog.markOrphans(NOW, DynamicSchedulerService.ORPHAN_SUMMARY)).thenReturn(0);
        service.sweepOrphanedFires();
        assertThat(logged("left running by the previous process")).isFalse();

        when(fireLog.markOrphans(NOW, DynamicSchedulerService.ORPHAN_SUMMARY)).thenReturn(3);
        service.sweepOrphanedFires();
        assertThat(logged("Marked 3 scheduler fire(s) left running")).isTrue();
    }

    // ── Next fire and fire listing ───────────────────────────────────────────────

    @Test
    @DisplayName("a cron that never comes round, a CRON row with no cron, and a FIXED_DELAY row with no delay have "
            + "no next fire")
    void calculateNextFireTime_unfireableSchedules_areNull() {
        // 30 February never comes: the expression parses but has no next fire.
        assertThat(service.calculateNextFireTime(job(ScheduleType.CRON).cronExpression("0 0 0 30 2 *").build()))
                .isNull();
        assertThat(service.calculateNextFireTime(job(ScheduleType.CRON).cronExpression(null).build())).isNull();
        assertThat(service.calculateNextFireTime(job(ScheduleType.FIXED_DELAY).fixedDelayMs(null).build()))
                .isNull();
    }

    @Test
    @DisplayName("a solar row whose trigger cannot be built has no next fire, not an exception")
    void calculateNextFireTime_solarTriggerRejected_isNull() {
        when(solarTriggers.create(KEY, SolarEvent.SUNSET, 240, "0 0 12 * * *"))
                .thenThrow(new IllegalArgumentException("bad offset"));
        SchedulerJobConfigEntity solar = job(ScheduleType.SOLAR_OFFSET).solarEvent(SolarEvent.SUNSET)
                .solarOffsetMinutes(240).cronExpression("0 0 12 * * *").build();

        assertThat(service.calculateNextFireTime(solar)).isNull();
    }

    @Test
    @DisplayName("a cron that never comes round lists no fires")
    void firesBetween_neverFiringCron_isEmpty() {
        SchedulerJobConfigEntity never = job(ScheduleType.CRON).cronExpression("0 0 0 30 2 *").build();

        assertThat(service.firesBetween(never, NOW, NOW.plus(Duration.ofDays(2)))).isEmpty();
    }

    @Test
    @DisplayName("firesLaterTodayUk is false for a job with no future fire")
    void firesLaterTodayUk_noFutureFire_isFalse() {
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(
                job(ScheduleType.CRON).cronExpression("0 0 0 30 2 *").build()));

        assertThat(service.firesLaterTodayUk(KEY)).isFalse();
        assertThat(logged("has no future fire time")).isTrue();
    }

    // ── Building and installing schedules ────────────────────────────────────────

    @Test
    @DisplayName("a paused CRON job with a blank cron is not resumed: status left, nothing saved or scheduled")
    void resume_blankCron_isNotResumed() {
        SchedulerJobConfigEntity paused = job(ScheduleType.CRON).cronExpression("  ")
                .status(SchedulerJobStatus.PAUSED).build();
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(paused));

        SchedulerJobConfigEntity result = service.resume(KEY);

        assertThat(result.getStatus()).isEqualTo(SchedulerJobStatus.PAUSED);
        verify(repository, never()).save(paused);
        verifyNoInteractions(taskScheduler);
    }

    @Test
    @DisplayName("a paused solar job with no stored fallback cron is not resumed")
    void resume_solarJobWithoutFallbackCron_isNotResumed() {
        SchedulerJobConfigEntity paused = job(ScheduleType.SOLAR_OFFSET).solarEvent(SolarEvent.SUNRISE)
                .solarOffsetMinutes(255).cronExpression("").status(SchedulerJobStatus.PAUSED).build();
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(paused));

        SchedulerJobConfigEntity result = service.resume(KEY);

        assertThat(result.getStatus()).isEqualTo(SchedulerJobStatus.PAUSED);
        verify(repository, never()).save(paused);
        verifyNoInteractions(taskScheduler);
    }

    @Test
    @DisplayName("correcting a solar job's fallback cron validates and stores it, and reinstalls the trigger")
    void updateSchedule_solarJobFallbackCron_isCorrected() {
        SchedulerJobConfigEntity solar = job(ScheduleType.SOLAR_OFFSET).solarEvent(SolarEvent.SUNRISE)
                .solarOffsetMinutes(255).cronExpression("0 0 1 * * *").build();
        SolarOffsetTrigger trigger = new SolarOffsetTrigger(KEY, SolarEvent.SUNRISE, 255, "0 0 3 * * *",
                (event, date) -> LocalDateTime.of(date.getYear(), date.getMonth(), date.getDayOfMonth(), 6, 0));
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(solar));
        when(solarTriggers.create(KEY, SolarEvent.SUNRISE, 255, "0 0 3 * * *")).thenReturn(trigger);
        when(taskScheduler.schedule(org.mockito.ArgumentMatchers.any(Runnable.class), eq(trigger)))
                .thenReturn(mock(ScheduledFuture.class));
        service.registerJobTarget(KEY, () -> { });

        SchedulerJobConfigEntity result = service.updateSchedule(KEY, "0 0 3 * * *", null, null);

        assertThat(result.getCronExpression()).isEqualTo("0 0 3 * * *");
        verify(repository).save(solar);
    }

    @Test
    @DisplayName("a schedule edit that changes nothing on a FIXED_DELAY job keeps its stored delay")
    void updateSchedule_fixedDelayWithoutNewDelay_keepsStoredDelay() {
        SchedulerJobConfigEntity delayed = job(ScheduleType.FIXED_DELAY).fixedDelayMs(60_000L).build();
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(delayed));
        when(taskScheduler.scheduleWithFixedDelay(org.mockito.ArgumentMatchers.any(Runnable.class),
                eq(Duration.ofMillis(60_000L)))).thenReturn(mock(ScheduledFuture.class));
        service.registerJobTarget(KEY, () -> { });

        SchedulerJobConfigEntity result = service.updateSchedule(KEY, null, null, null);

        assertThat(result.getFixedDelayMs()).isEqualTo(60_000L);
    }

    @Test
    @DisplayName("an edit to an ACTIVE job with no registered target is stored but schedules nothing")
    void updateSchedule_noRegisteredTarget_storesButSchedulesNothing() {
        SchedulerJobConfigEntity cron = job(ScheduleType.CRON).cronExpression("0 0 1 * * *").build();
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(cron));

        SchedulerJobConfigEntity result = service.updateSchedule(KEY, "0 0 2 * * *", null, null);

        assertThat(result.getCronExpression()).isEqualTo("0 0 2 * * *");
        verify(repository).save(cron);
        verifyNoInteractions(taskScheduler);
        assertThat(logged("No registered target for job 'tide_refresh'")).isTrue();
    }

    @Test
    @DisplayName("validateSchedule rejects a non-positive fixed delay")
    void validateSchedule_nonPositiveDelay_isRejected() {
        assertThatThrownBy(() -> service.validateSchedule(
                KEY, ScheduleType.FIXED_DELAY, null, 0L, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Fire log and audit trail ─────────────────────────────────────────────────

    private Runnable manualRunOf(Runnable body) {
        SchedulerJobConfigEntity cron = job(ScheduleType.CRON).cronExpression("0 0 1 * * *").build();
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(cron));
        service.registerJobTarget(KEY, body);
        service.triggerNow(KEY);
        ArgumentCaptor<Runnable> run = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> scheduledAt = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler).schedule(run.capture(), scheduledAt.capture());
        assertThat(Duration.between(scheduledAt.getValue(), Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
        return run.getValue();
    }

    private void stubFireLogRow(long id) {
        when(fireLog.save(org.mockito.ArgumentMatchers.argThat((SchedulerFireLogEntity row) -> row != null
                && KEY.equals(row.getJobKey()) && row.getStatus() == SchedulerFireStatus.RUNNING
                && row.getTriggerKind() == SchedulerFireTrigger.MANUAL)))
                .thenAnswer(inv -> withId(inv.getArgument(0), id));
    }

    private static SchedulerFireLogEntity withId(SchedulerFireLogEntity row, long id) {
        row.setId(id);
        return row;
    }

    @Test
    @DisplayName("a fire's reported summary is stored cut to 200 characters")
    void fireSummary_longSummary_isTruncated() {
        stubFireLogRow(9L);
        Runnable run = manualRunOf(() -> SchedulerFireOutcome.report(SchedulerFireStatus.SUCCEEDED, "x".repeat(260)));

        run.run();

        verify(fireLog).complete(eq(9L), eq(NOW), eq(SchedulerFireStatus.SUCCEEDED), eq("x".repeat(200)),
                isNull());
    }

    @Test
    @DisplayName("a fire that reports a blank summary stores no summary at all")
    void fireSummary_blankSummary_isStoredAsNull() {
        stubFireLogRow(10L);
        Runnable run = manualRunOf(() -> SchedulerFireOutcome.report(SchedulerFireStatus.PARTIAL, "   "));

        run.run();

        verify(fireLog).complete(eq(10L), eq(NOW), eq(SchedulerFireStatus.PARTIAL), isNull(), isNull());
    }

    @Test
    @DisplayName("a manual run's log line names the authenticated caller; with nobody signed in it says 'unknown'")
    void triggerNow_logsWhoRanIt() {
        SchedulerJobConfigEntity cron = job(ScheduleType.CRON).cronExpression("0 0 1 * * *").build();
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(cron));
        service.registerJobTarget(KEY, () -> { });

        service.triggerNow(KEY);
        assertThat(logged("Triggered immediate run for job 'tide_refresh' by unknown")).isTrue();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("chris", "n/a", List.of()));
        service.triggerNow(KEY);
        assertThat(logged("Triggered immediate run for job 'tide_refresh' by chris")).isTrue();
    }

    // ── Config-gated status already in step with its flag ────────────────────────

    @Test
    @DisplayName("a job already disabled by config stays untouched while its flag is still off")
    void initSchedules_alreadyDisabledAndFlagStillOff_isUntouched() {
        when(auroraProperties.isEnabled()).thenReturn(false);
        SchedulerJobConfigEntity aurora = job(ScheduleType.FIXED_DELAY).jobKey("aurora_polling")
                .fixedDelayMs(300_000L).status(SchedulerJobStatus.DISABLED_BY_CONFIG)
                .configSource("aurora.enabled").build();
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(aurora));

        service.initSchedules();

        assertThat(aurora.getStatus()).isEqualTo(SchedulerJobStatus.DISABLED_BY_CONFIG);
        verify(repository, never()).save(aurora);
    }

    @Test
    @DisplayName("an ACTIVE job whose flag is on is scheduled as it was and not re-saved")
    void initSchedules_activeAndFlagOn_isNotResaved() {
        when(auroraProperties.isEnabled()).thenReturn(true);
        SchedulerJobConfigEntity aurora = job(ScheduleType.FIXED_DELAY).jobKey("aurora_polling")
                .fixedDelayMs(300_000L).status(SchedulerJobStatus.ACTIVE)
                .configSource("aurora.enabled").build();
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(aurora));
        when(taskScheduler.scheduleWithFixedDelay(org.mockito.ArgumentMatchers.any(Runnable.class),
                eq(Duration.ofMillis(300_000L)))).thenReturn(mock(ScheduledFuture.class));
        service.registerJobTarget("aurora_polling", () -> { });

        service.initSchedules();

        assertThat(aurora.getStatus()).isEqualTo(SchedulerJobStatus.ACTIVE);
        verify(repository, never()).save(aurora);
    }
}
