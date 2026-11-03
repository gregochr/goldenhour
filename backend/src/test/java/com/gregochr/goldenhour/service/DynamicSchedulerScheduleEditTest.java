package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.repository.SchedulerFireLogRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the schedule-editing surface added in S3b: a cron zone change on {@code updateSchedule}, the
 * reusable validation entry point, the fire arithmetic the Scheduler day view shares, and the clock fix in
 * {@code calculateNextFireTime}.
 */
@ExtendWith(MockitoExtension.class)
class DynamicSchedulerScheduleEditTest {

    private static final Instant NOW = Instant.parse("2027-03-05T12:00:00Z");
    private static final String KEY = "evening_sunrise_refresh";
    private static final String LONDON = "Europe/London";

    @Mock
    private SchedulerJobConfigRepository repository;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private AuroraProperties auroraProperties;

    @Mock
    private SolarTriggerFactory solarTriggers;

    @Mock
    private SolarCatchUpPolicy solarCatchUp;

    private DynamicSchedulerService service() {
        return new DynamicSchedulerService(repository, mock(SchedulerFireLogRepository.class), taskScheduler,
                auroraProperties, solarTriggers, solarCatchUp, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SchedulerJobConfigEntity.SchedulerJobConfigEntityBuilder job(ScheduleType type) {
        return SchedulerJobConfigEntity.builder().id(1L).jobKey(KEY).displayName(KEY).scheduleType(type)
                .status(SchedulerJobStatus.ACTIVE).updatedAt(NOW);
    }

    private static SchedulerJobConfigEntity cronJob(String cron, String timeZone) {
        return job(ScheduleType.CRON).cronExpression(cron).timeZone(timeZone).build();
    }

    /**
     * Matches the cron trigger the service should install, pinning its zone by when it next fires. The runnable is
     * the service's own wrapper, which a test cannot name, so that argument stays {@code any(Runnable.class)}.
     */
    private static Trigger cronTrigger(String expression, String probeNow, String expectedNext) {
        return argThat(t -> t instanceof CronTrigger c && c.getExpression().equals(expression)
                && Instant.parse(expectedNext).equals(c.nextExecution(
                        new SimpleTriggerContext(Clock.fixed(Instant.parse(probeNow), ZoneOffset.UTC)))));
    }

    // -------------------------------------------------------------------------
    // updateSchedule with a zone change
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a zone change is one scoped write of cron + zone (no whole-entity save), then the trigger is "
            + "reinstalled in the new zone")
    void zoneChange_isOneScopedWrite() {
        SchedulerJobConfigEntity before = cronJob("0 0 21 * * *", null);
        SchedulerJobConfigEntity after = cronJob("0 30 6 * * *", LONDON);
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(before), Optional.of(after));
        Instant start = Instant.now();
        when(repository.updateScheduleAndTimeZone(eq(1L), eq("0 30 6 * * *"), isNull(), eq(LONDON),
                argThat(t -> !t.isBefore(start) && !t.isAfter(Instant.now().plusSeconds(5))))).thenReturn(1);
        when(taskScheduler.schedule(any(Runnable.class),
                cronTrigger("0 30 6 * * *", "2027-07-01T00:00:00Z", "2027-07-01T05:30:00Z")))
                .thenReturn(mock(ScheduledFuture.class));
        DynamicSchedulerService service = service();
        service.registerJobTarget(KEY, () -> { });

        SchedulerJobConfigEntity result = service.updateSchedule(KEY, "0 30 6 * * *", null,
                new DynamicSchedulerService.ZoneChange(LONDON));

        assertThat(result).isSameAs(after);
        assertThat(result.getTimeZone()).isEqualTo(LONDON);
        verify(repository, never()).save(before);
        verify(repository).updateScheduleChangedAt(KEY, NOW);
    }

    @Test
    @DisplayName("a zone change to UTC (null, blank or 'UTC') stores a null zone")
    void zoneChange_toUtcStoresNull() {
        SchedulerJobConfigEntity before = cronJob("0 0 21 * * *", LONDON);
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(before), Optional.of(cronJob("0 0 21 * * *", null)));
        Instant start = Instant.now();
        when(repository.updateScheduleAndTimeZone(eq(1L), eq("0 0 21 * * *"), isNull(), isNull(),
                argThat(t -> !t.isBefore(start) && !t.isAfter(Instant.now().plusSeconds(5))))).thenReturn(1);
        when(taskScheduler.schedule(any(Runnable.class),
                cronTrigger("0 0 21 * * *", "2027-07-01T00:00:00Z", "2027-07-01T21:00:00Z")))
                .thenReturn(mock(ScheduledFuture.class));
        DynamicSchedulerService service = service();
        service.registerJobTarget(KEY, () -> { });

        SchedulerJobConfigEntity result = service.updateSchedule(KEY, null, null,
                new DynamicSchedulerService.ZoneChange("UTC"));

        assertThat(result.getTimeZone()).isNull();
    }

    @Test
    @DisplayName("omitting the zone keeps the stored one: the whole-entity save runs and the zone is never written")
    void noZoneChange_keepsTheZone() {
        SchedulerJobConfigEntity config = cronJob("0 0 21 * * *", LONDON);
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(config));
        when(taskScheduler.schedule(any(Runnable.class),
                cronTrigger("0 30 22 * * *", "2027-07-01T00:00:00Z", "2027-07-01T21:30:00Z")))
                .thenReturn(mock(ScheduledFuture.class));
        DynamicSchedulerService service = service();
        service.registerJobTarget(KEY, () -> { });

        service.updateSchedule(KEY, "0 30 22 * * *", null, null);

        assertThat(config.getTimeZone()).isEqualTo(LONDON);
        assertThat(config.getCronExpression()).isEqualTo("0 30 22 * * *");
        verify(repository).save(config);
        verify(repository, never()).updateScheduleAndTimeZone(eq(1L), eq("0 30 22 * * *"), isNull(), eq(LONDON),
                argThat(t -> true));
    }

    @Test
    @DisplayName("a zone other than Europe/London or UTC is refused and nothing is written or rescheduled")
    void invalidZone_refused() {
        SchedulerJobConfigEntity config = cronJob("0 0 21 * * *", null);
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(config));
        DynamicSchedulerService service = service();

        assertThatThrownBy(() -> service.updateSchedule(KEY, null, null,
                new DynamicSchedulerService.ZoneChange("Asia/Tokyo")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("timeZone must be Europe/London or UTC, got 'Asia/Tokyo'");

        verify(repository, never()).save(config);
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    @DisplayName("a zone given for a job that is not CRON is refused")
    void zoneOnNonCronJob_refused() {
        SchedulerJobConfigEntity config = job(ScheduleType.FIXED_DELAY).fixedDelayMs(60_000L).build();
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service().updateSchedule(KEY, null, null,
                new DynamicSchedulerService.ZoneChange(LONDON)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A time zone can only be set on a CRON job");
    }

    @Test
    @DisplayName("a scoped write that touches no row fails the call and installs nothing")
    void zoneChange_noRowUpdated_failsWithoutInstalling() {
        SchedulerJobConfigEntity config = cronJob("0 0 21 * * *", null);
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(config));
        when(repository.updateScheduleAndTimeZone(eq(1L), eq("0 0 21 * * *"), isNull(), eq(LONDON),
                argThat(t -> !t.isAfter(Instant.now().plusSeconds(5))))).thenReturn(0);

        assertThatThrownBy(() -> service().updateSchedule(KEY, null, null,
                new DynamicSchedulerService.ZoneChange(LONDON)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Job '" + KEY + "' schedule was not updated");

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    @DisplayName("a zone change on a paused job is stored but not scheduled")
    void zoneChange_pausedJobIsNotInstalled() {
        SchedulerJobConfigEntity paused = job(ScheduleType.CRON).cronExpression("0 0 21 * * *")
                .status(SchedulerJobStatus.PAUSED).build();
        when(repository.findByJobKey(KEY)).thenReturn(Optional.of(paused), Optional.of(paused));
        when(repository.updateScheduleAndTimeZone(eq(1L), eq("0 0 21 * * *"), isNull(), eq(LONDON),
                argThat(t -> !t.isAfter(Instant.now().plusSeconds(5))))).thenReturn(1);

        service().updateSchedule(KEY, null, null, new DynamicSchedulerService.ZoneChange(LONDON));

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
    }

    // -------------------------------------------------------------------------
    // Validation entry points
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("normaliseScheduleZone accepts Europe/London (trimmed) and UTC in any spelling of 'none'")
    void normaliseScheduleZone_acceptedValues() {
        assertThat(DynamicSchedulerService.normaliseScheduleZone(" Europe/London ")).isEqualTo(LONDON);
        assertThat(DynamicSchedulerService.normaliseScheduleZone(null)).isNull();
        assertThat(DynamicSchedulerService.normaliseScheduleZone("  ")).isNull();
        assertThat(DynamicSchedulerService.normaliseScheduleZone("UTC")).isNull();
        assertThatThrownBy(() -> DynamicSchedulerService.normaliseScheduleZone("America/New_York"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("requireSolarAllowed passes the two forecast cycles and refuses every other job")
    void requireSolarAllowed_onlyTheCycles() {
        DynamicSchedulerService.requireSolarAllowed(ForecastCycleJobs.NIGHTLY_JOB);
        DynamicSchedulerService.requireSolarAllowed(ForecastCycleJobs.INTRADAY_JOB);
        assertThatThrownBy(() -> DynamicSchedulerService.requireSolarAllowed("tide_refresh"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Job 'tide_refresh' may not be switched to SOLAR_OFFSET; only the two forecast cycle "
                        + "jobs may");
    }

    @Test
    @DisplayName("validateSchedule applies updateSchedule's messages and schedules nothing")
    void validateSchedule_sameMessagesAndNoSideEffects() {
        DynamicSchedulerService service = service();

        service.validateSchedule(KEY, ScheduleType.CRON, "0 15 6 * * *", null, null, null, LONDON);
        assertThatThrownBy(() -> service.validateSchedule(KEY, ScheduleType.CRON, "nonsense", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid cron expression: nonsense");
        assertThatThrownBy(() -> service.validateSchedule(KEY, ScheduleType.FIXED_DELAY, null, 5_000L, null, null,
                null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Fixed delay must be at least 10000ms, got: 5000");
        assertThatThrownBy(() -> service.validateSchedule(KEY, ScheduleType.CRON, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("CRON job has no cron expression");
        assertThatThrownBy(() -> service.validateSchedule(KEY, ScheduleType.SOLAR_OFFSET, "0 0 12 * * *", null,
                SolarEvent.SUNSET, 721, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("solar offset must be 0..720 minutes, got 721");
        assertThatThrownBy(() -> service.validateSchedule(KEY, ScheduleType.SOLAR_OFFSET, null, null,
                SolarEvent.SUNSET, 240, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SOLAR_OFFSET job needs a stored cron expression as its fallback");

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
        verify(repository, never()).save(any(SchedulerJobConfigEntity.class));
    }

    // -------------------------------------------------------------------------
    // Fire arithmetic shared with the day view
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a fixed-delay job that has never run is next due one initial delay after the injected clock")
    void fixedDelayNextFire_usesTheInjectedClock() {
        SchedulerJobConfigEntity defaultDelay = job(ScheduleType.FIXED_DELAY).fixedDelayMs(60_000L).build();
        SchedulerJobConfigEntity initialDelay = job(ScheduleType.FIXED_DELAY).fixedDelayMs(60_000L)
                .initialDelayMs(5_000L).build();
        SchedulerJobConfigEntity afterRun = job(ScheduleType.FIXED_DELAY).fixedDelayMs(60_000L)
                .lastCompletionTime(NOW.minusSeconds(10)).build();

        assertThat(service().calculateNextFireTime(defaultDelay)).isEqualTo(NOW.plusSeconds(60));
        assertThat(service().calculateNextFireTime(initialDelay)).isEqualTo(NOW.plusSeconds(5));
        assertThat(service().calculateNextFireTime(afterRun)).isEqualTo(NOW.plusSeconds(50));
    }

    @Test
    @DisplayName("firesWithFallback flags exactly the days a solar job falls back to its stored cron")
    void firesWithFallback_flagsFallbackDays() {
        // The calculator fails for 6 March (no sun that day) and works for every other date.
        SolarOffsetTrigger trigger = new SolarOffsetTrigger(KEY, SolarEvent.SUNSET, 240, "0 0 12 * * *",
                (event, date) -> {
                    if (date.getDayOfMonth() == 6) {
                        throw new IllegalStateException("no sun");
                    }
                    return LocalDateTime.of(date.getYear(), date.getMonth(), date.getDayOfMonth(), 17, 0);
                });
        when(solarTriggers.create(KEY, SolarEvent.SUNSET, 240, "0 0 12 * * *")).thenReturn(trigger);
        SchedulerJobConfigEntity solar = job(ScheduleType.SOLAR_OFFSET).solarEvent(SolarEvent.SUNSET)
                .solarOffsetMinutes(240).cronExpression("0 0 12 * * *").build();

        List<DynamicSchedulerService.ScheduledFire> fires = service().firesWithFallback(solar,
                Instant.parse("2027-03-05T00:00:00Z"), Instant.parse("2027-03-06T23:59:00Z"));

        assertThat(fires).containsExactly(
                new DynamicSchedulerService.ScheduledFire(Instant.parse("2027-03-05T13:00:00Z"), false),
                new DynamicSchedulerService.ScheduledFire(Instant.parse("2027-03-06T12:00:00Z"), true));
    }

    @Test
    @DisplayName("a paused job has no fires with fallback, but its repeating status is unaffected by pausing")
    void pausedJob_noFiresButStillRepeating() {
        SchedulerJobConfigEntity pausedPoller = job(ScheduleType.CRON).cronExpression("0 */5 * * * *")
                .status(SchedulerJobStatus.PAUSED).build();
        Instant from = Instant.parse("2027-03-05T00:00:00Z");
        Instant to = Instant.parse("2027-03-06T00:00:00Z");

        assertThat(service().firesWithFallback(pausedPoller, from, to)).isEmpty();
        assertThat(service().isRepeating(pausedPoller, from, to)).isTrue();
    }

    @Test
    @DisplayName("isRepeating: fixed delay always; a cron only above six fires in the window")
    void isRepeating_rule() {
        Instant from = Instant.parse("2027-03-05T00:00:00Z");
        Instant to = Instant.parse("2027-03-06T00:00:00Z");
        DynamicSchedulerService service = service();

        assertThat(service.isRepeating(job(ScheduleType.FIXED_DELAY).fixedDelayMs(60_000L).build(), from, to))
                .isTrue();
        assertThat(service.isRepeating(cronJob("0 0 1-6 * * *", null), from, to)).as("six fires").isFalse();
        assertThat(service.isRepeating(cronJob("0 0 1-7 * * *", null), from, to)).as("seven fires").isTrue();
        assertThat(service.isRepeating(cronJob("0 30 6 * * *", null), from, to)).isFalse();
        assertThat(service.isRepeating(job(ScheduleType.SOLAR_OFFSET).build(), from, to)).isFalse();
    }

    @Test
    @DisplayName("exceedsRepeatingThreshold is true only above the named constant")
    void exceedsRepeatingThreshold_boundary() {
        assertThat(DynamicSchedulerService.exceedsRepeatingThreshold(
                DynamicSchedulerService.REPEATING_FIRES_PER_DAY)).isFalse();
        assertThat(DynamicSchedulerService.exceedsRepeatingThreshold(
                DynamicSchedulerService.REPEATING_FIRES_PER_DAY + 1)).isTrue();
    }
}
