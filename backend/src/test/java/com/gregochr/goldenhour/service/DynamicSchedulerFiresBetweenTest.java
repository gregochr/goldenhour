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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DynamicSchedulerService#firesBetween}: the fires the Runs Day view lists as still to come, by
 * the scheduler's own cron and solar arithmetic.
 */
@ExtendWith(MockitoExtension.class)
class DynamicSchedulerFiresBetweenTest {

    private static final Instant FROM = Instant.parse("2026-11-02T09:26:00Z");
    private static final Instant TO = Instant.parse("2026-11-03T00:00:00Z");

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
                auroraProperties, solarTriggers, solarCatchUp, Clock.fixed(FROM, ZoneOffset.UTC));
    }

    private static SchedulerJobConfigEntity.SchedulerJobConfigEntityBuilder job(ScheduleType type) {
        return SchedulerJobConfigEntity.builder().jobKey("job").displayName("Job").scheduleType(type)
                .status(SchedulerJobStatus.ACTIVE).updatedAt(FROM);
    }

    @Test
    @DisplayName("a cron lists every fire after now and before the end of the day, in the job's own zone")
    void cronInItsZone() {
        // 15:30 London (GMT in November) every day, and twice a day at 06:00 and 18:00 UTC.
        SchedulerJobConfigEntity london = job(ScheduleType.CRON).cronExpression("0 30 15 * * *")
                .timeZone("Europe/London").build();
        SchedulerJobConfigEntity twice = job(ScheduleType.CRON).cronExpression("0 0 6,18 * * *").build();

        assertThat(service().firesBetween(london, FROM, TO)).containsExactly(Instant.parse("2026-11-02T15:30:00Z"));
        assertThat(service().firesBetween(twice, FROM, TO)).containsExactly(Instant.parse("2026-11-02T18:00:00Z"));
    }

    @Test
    @DisplayName("a cron zone is honoured as the clocks go back: 21:00 London is 20:00 UTC, then 21:00 UTC")
    void cronZoneInSummerTime() {
        SchedulerJobConfigEntity evening = job(ScheduleType.CRON).cronExpression("0 0 21 * * *")
                .timeZone("Europe/London").build();

        List<Instant> fires = service().firesBetween(evening, Instant.parse("2026-10-24T18:00:00Z"),
                Instant.parse("2026-10-26T00:00:00Z"));

        assertThat(fires).containsExactly(Instant.parse("2026-10-24T20:00:00Z"),
                Instant.parse("2026-10-25T21:00:00Z"));
    }

    @Test
    @DisplayName("a frequent cron is bounded")
    void frequentCronBounded() {
        SchedulerJobConfigEntity everySecond = job(ScheduleType.CRON).cronExpression("* * * * * *").build();

        assertThat(service().firesBetween(everySecond, FROM, TO)).hasSize(DynamicSchedulerService.MAX_FIRES_LISTED);
    }

    @Test
    @DisplayName("firesIgnoringStatus lists a paused or off-in-config job's slots; firesBetween lists none")
    void ignoringStatus() {
        SchedulerJobConfigEntity paused = job(ScheduleType.CRON).cronExpression("0 0 6,18 * * *")
                .status(SchedulerJobStatus.PAUSED).build();
        SchedulerJobConfigEntity off = job(ScheduleType.CRON).cronExpression("0 0 6,18 * * *")
                .status(SchedulerJobStatus.DISABLED_BY_CONFIG).build();

        assertThat(service().firesBetween(paused, FROM, TO)).isEmpty();
        assertThat(service().firesIgnoringStatus(paused, FROM, TO)).containsExactly(
                new DynamicSchedulerService.ScheduledFire(TO.minus(Duration.ofHours(6)), false));
        assertThat(service().firesIgnoringStatus(off, FROM, TO)).hasSize(1);
    }

    @Test
    @DisplayName("a solar job lists the trigger's next fires; none that are not before the end of the day")
    void solarFires() {
        SolarOffsetTrigger trigger = new SolarOffsetTrigger("job", SolarEvent.SUNSET, 250, null,
                (event, date) -> LocalDateTime.of(date.getYear(), date.getMonth(), date.getDayOfMonth(), 16, 49));
        when(solarTriggers.create(eq("job"), eq(SolarEvent.SUNSET), eq(250), eq("0 0 12 * * *"))).thenReturn(trigger);
        SchedulerJobConfigEntity solar = job(ScheduleType.SOLAR_OFFSET).solarEvent(SolarEvent.SUNSET)
                .solarOffsetMinutes(250).cronExpression("0 0 12 * * *").build();

        List<Instant> fires = service().firesBetween(solar, FROM, TO);

        assertThat(fires).containsExactly(Instant.parse("2026-11-02T12:39:00Z"));
        assertThat(service().firesBetween(solar, Instant.parse("2026-11-02T12:40:00Z"), TO)).isEmpty();
    }

    @Test
    @DisplayName("an unusable solar day falls back to the stored cron, as the scheduler does")
    void solarFallsBackToCron() {
        SolarOffsetTrigger broken = new SolarOffsetTrigger("job", SolarEvent.SUNSET, 250, "0 0 12 * * *",
                (event, date) -> {
                    throw new IllegalStateException("no sun");
                });
        when(solarTriggers.create(eq("job"), eq(SolarEvent.SUNSET), eq(250), eq("0 0 12 * * *")))
                .thenReturn(broken);
        SchedulerJobConfigEntity solar = job(ScheduleType.SOLAR_OFFSET).solarEvent(SolarEvent.SUNSET)
                .solarOffsetMinutes(250).cronExpression("0 0 12 * * *").build();

        assertThat(service().firesBetween(solar, Instant.parse("2026-11-02T01:00:00Z"), TO))
                .containsExactly(Instant.parse("2026-11-02T12:00:00Z"));
    }

    @Test
    @DisplayName("paused, fixed-delay, incomplete and unparseable jobs list nothing")
    void nothingToList() {
        assertThat(service().firesBetween(job(ScheduleType.CRON).cronExpression("0 30 15 * * *")
                .status(SchedulerJobStatus.PAUSED).build(), FROM, TO)).isEmpty();
        assertThat(service().firesBetween(job(ScheduleType.FIXED_DELAY).fixedDelayMs(60_000L).build(), FROM, TO))
                .isEmpty();
        assertThat(service().firesBetween(job(ScheduleType.CRON).build(), FROM, TO)).isEmpty();
        assertThat(service().firesBetween(job(ScheduleType.CRON).cronExpression("not a cron").build(), FROM, TO))
                .isEmpty();
        assertThat(service().firesBetween(job(ScheduleType.CRON).cronExpression("0 0 21 * * *")
                .timeZone("Mars/Olympus").build(), FROM, TO)).isEmpty();
        assertThat(service().firesBetween(job(ScheduleType.SOLAR_OFFSET).build(), FROM, TO)).isEmpty();
    }
}
