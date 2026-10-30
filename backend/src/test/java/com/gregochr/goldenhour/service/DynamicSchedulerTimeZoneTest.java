package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the per-job time zone of {@link DynamicSchedulerService} (V203): a CRON job with a
 * {@code time_zone} is evaluated in that zone, a job without one stays in UTC.
 */
@ExtendWith(MockitoExtension.class)
class DynamicSchedulerTimeZoneTest {

    private static final String EVENING = "evening_sunrise_refresh";
    private static final String LONDON = "Europe/London";
    private static final String EVENING_CRON = "0 0 21 * * *";

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

    private DynamicSchedulerService serviceAt(String instant) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new DynamicSchedulerService(repository, taskScheduler, auroraProperties,
                solarTriggers, solarCatchUp, clock);
    }

    private static SchedulerJobConfigEntity cronJob(String key, String cron, String timeZone) {
        return SchedulerJobConfigEntity.builder()
                .id(1L)
                .jobKey(key)
                .displayName(key)
                .scheduleType(ScheduleType.CRON)
                .cronExpression(cron)
                .timeZone(timeZone)
                .status(SchedulerJobStatus.ACTIVE)
                .build();
    }

    /**
     * Matches the cron trigger the service should install: the expected expression, firing at
     * {@code expectedNext} when asked at {@code probeNow}. {@link CronTrigger} exposes no zone getter, so the
     * zone is pinned by that fire time (pick a probe date where the zone shifts the instant). The runnable
     * is the service's own wrapper, which a test cannot name, so it stays {@code any(Runnable.class)}.
     */
    private static Trigger cronTrigger(String expression, String probeNow, String expectedNext) {
        return argThat(t -> t instanceof CronTrigger c
                && c.getExpression().equals(expression)
                && Instant.parse(expectedNext).equals(nextFire(c, probeNow)));
    }

    private Trigger scheduledTrigger(DynamicSchedulerService service, SchedulerJobConfigEntity config,
            String probeNow, String expectedNext) {
        when(taskScheduler.schedule(any(Runnable.class),
                cronTrigger(config.getCronExpression(), probeNow, expectedNext)))
                .thenReturn(mock(ScheduledFuture.class));
        service.registerJobTarget(config.getJobKey(), () -> { });
        service.scheduleJob(config);
        ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
        verify(taskScheduler).schedule(any(Runnable.class), captor.capture());
        return captor.getValue();
    }

    private static Instant nextFire(Trigger trigger, String now) {
        Clock clock = Clock.fixed(Instant.parse(now), ZoneOffset.UTC);
        return trigger.nextExecution(new SimpleTriggerContext(clock));
    }

    @Test
    @DisplayName("a null-zone CRON job still fires in UTC")
    void nullZoneJobFiresInUtc() {
        DynamicSchedulerService service = serviceAt("2026-07-01T12:00:00Z");
        Trigger trigger = scheduledTrigger(service, cronJob("tide_refresh", "0 0 20 * * *", null),
                "2026-07-01T12:00:00Z", "2026-07-01T20:00:00Z");

        assertThat(trigger).isInstanceOf(CronTrigger.class);
        assertThat(nextFire(trigger, "2026-07-01T12:00:00Z"))
                .isEqualTo(Instant.parse("2026-07-01T20:00:00Z"));
        assertThat(nextFire(trigger, "2026-12-01T12:00:00Z"))
                .isEqualTo(Instant.parse("2026-12-01T20:00:00Z"));
    }

    @Test
    @DisplayName("a Europe/London job's installed trigger fires at 21:00 UK: 21:00Z winter, 20:00Z summer")
    void londonJobTriggerFollowsUkClock() {
        DynamicSchedulerService service = serviceAt("2026-10-30T12:00:00Z");
        Trigger trigger = scheduledTrigger(service, cronJob(EVENING, EVENING_CRON, LONDON),
                "2026-07-01T12:00:00Z", "2026-07-01T20:00:00Z");

        assertThat(nextFire(trigger, "2026-10-30T12:00:00Z"))
                .isEqualTo(Instant.parse("2026-10-30T21:00:00Z"));
        assertThat(nextFire(trigger, "2026-07-01T12:00:00Z"))
                .isEqualTo(Instant.parse("2026-07-01T20:00:00Z"));
        // Across the autumn change (25 Oct 2026): the last BST evening is 20:00Z, the next is 21:00Z.
        assertThat(nextFire(trigger, "2026-10-24T21:30:00Z"))
                .isEqualTo(Instant.parse("2026-10-25T21:00:00Z"));
        assertThat(nextFire(trigger, "2026-10-24T12:00:00Z"))
                .isEqualTo(Instant.parse("2026-10-24T20:00:00Z"));
    }

    @Test
    @DisplayName("calculateNextFireTime uses the injected clock and the job's zone")
    void calculateNextFireTimeUsesZoneAndClock() {
        SchedulerJobConfigEntity config = cronJob(EVENING, EVENING_CRON, LONDON);

        assertThat(serviceAt("2026-10-30T12:00:00Z").calculateNextFireTime(config))
                .isEqualTo(Instant.parse("2026-10-30T21:00:00Z"));
        assertThat(serviceAt("2026-07-01T12:00:00Z").calculateNextFireTime(config))
                .isEqualTo(Instant.parse("2026-07-01T20:00:00Z"));
        assertThat(serviceAt("2026-10-24T21:30:00Z").calculateNextFireTime(config))
                .isEqualTo(Instant.parse("2026-10-25T21:00:00Z"));
    }

    @Test
    @DisplayName("calculateNextFireTime for a null-zone job is still UTC")
    void calculateNextFireTimeNullZoneIsUtc() {
        SchedulerJobConfigEntity config = cronJob("tide_refresh", "0 0 20 * * *", null);

        assertThat(serviceAt("2026-07-01T12:00:00Z").calculateNextFireTime(config))
                .isEqualTo(Instant.parse("2026-07-01T20:00:00Z"));
    }

    @Test
    @DisplayName("an unknown zone leaves that row unscheduled, the other still installed, no next fire")
    void invalidZoneLeavesRowUnscheduled() {
        DynamicSchedulerService service = serviceAt("2026-10-30T12:00:00Z");
        SchedulerJobConfigEntity bad = cronJob(EVENING, EVENING_CRON, "Mars/Base");
        SchedulerJobConfigEntity good = cronJob("tide_refresh", "0 0 2 * * MON", null);
        good.setId(2L);
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(bad, good));
        when(taskScheduler.schedule(any(Runnable.class),
                cronTrigger("0 0 2 * * MON", "2026-07-01T12:00:00Z", "2026-07-06T02:00:00Z")))
                .thenReturn(mock(ScheduledFuture.class));
        service.registerJobTarget(EVENING, () -> { });
        service.registerJobTarget("tide_refresh", () -> { });

        service.initSchedules();

        ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), captor.capture());
        assertThat(captor.getAllValues()).hasSize(1);
        assertThat(((CronTrigger) captor.getValue()).getExpression()).isEqualTo("0 0 2 * * MON");
        assertThat(service.calculateNextFireTime(bad)).isNull();
    }

    @Test
    @DisplayName("the zone resolver maps null and blank to UTC and rethrows a bad id as IllegalArgumentException")
    void cronZoneResolver() {
        assertThat(DynamicSchedulerService.cronZone((String) null)).isEqualTo(ZoneOffset.UTC);
        assertThat(DynamicSchedulerService.cronZone("  ")).isEqualTo(ZoneOffset.UTC);
        assertThat(DynamicSchedulerService.cronZone(LONDON)).isEqualTo(ZoneId.of(LONDON));
        assertThatThrownBy(() -> DynamicSchedulerService.cronZone("Mars/Base"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown time zone");
    }

    @Test
    @DisplayName("updateSchedule on a London job keeps the zone: the new cron is read in London")
    void updateScheduleKeepsZone() {
        DynamicSchedulerService service = serviceAt("2026-10-30T12:00:00Z");
        SchedulerJobConfigEntity config = cronJob(EVENING, EVENING_CRON, LONDON);
        when(repository.findByJobKey(EVENING)).thenReturn(Optional.of(config));
        when(taskScheduler.schedule(any(Runnable.class),
                cronTrigger("0 30 22 * * *", "2026-07-01T12:00:00Z", "2026-07-01T21:30:00Z")))
                .thenReturn(mock(ScheduledFuture.class));
        service.registerJobTarget(EVENING, () -> { });

        service.updateSchedule(EVENING, "0 30 22 * * *", null);

        assertThat(config.getTimeZone()).isEqualTo(LONDON);
        ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
        verify(taskScheduler).schedule(any(Runnable.class), captor.capture());
        assertThat(nextFire(captor.getValue(), "2026-07-01T12:00:00Z"))
                .isEqualTo(Instant.parse("2026-07-01T21:30:00Z"));
    }

    @Test
    @DisplayName("updateSchedule on a bad-zone job is refused and the live schedule is untouched")
    void updateScheduleBadZoneRefused() {
        DynamicSchedulerService service = serviceAt("2026-10-30T12:00:00Z");
        SchedulerJobConfigEntity config = cronJob(EVENING, EVENING_CRON, "Mars/Base");
        when(repository.findByJobKey(EVENING)).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.updateSchedule(EVENING, "0 30 22 * * *", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(config.getCronExpression()).isEqualTo(EVENING_CRON);
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
        verify(repository, never()).save(any());
    }
}
