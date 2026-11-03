package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.service.notification.AdminAlertService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins {@link ForecastCycleWatchdog#deadlines} and {@link SolarTriggerFactory#eventInstant}: the windows the Scheduler
 * day view draws are the numbers the watchdog compares with the clock, whatever a job's schedule type.
 */
class ForecastCycleWatchdogDeadlinesTest {

    private static final LocalDate DAY = LocalDate.of(2027, 3, 5);
    private static final Duration EVENING_BUDGET = Duration.ofMinutes(90);

    private final SolarTriggerFactory factory = new SolarTriggerFactory(new SolarService(),
            new SolarReferencePoint("54.776", "-1.575"));

    private ForecastCycleWatchdog watchdog(SolarTriggerFactory solar) {
        return new ForecastCycleWatchdog(mock(SchedulerJobConfigRepository.class), mock(PipelineRunRepository.class),
                mock(ForecastBatchRepository.class), solar, mock(AdminAlertService.class),
                mock(DynamicSchedulerService.class), Clock.fixed(Instant.parse("2027-03-05T12:00:00Z"),
                        ZoneOffset.UTC), EVENING_BUDGET);
    }

    @Test
    @DisplayName("the nightly cycle's window brackets today's sunrise: opens 20 minutes before, closes 60 after")
    void nightlyWindowBracketsSunrise() {
        Instant sunrise = factory.eventInstant(SolarEvent.SUNRISE, DAY);

        Optional<ForecastCycleWatchdog.Deadlines> deadlines = watchdog(factory).deadlines(
                ForecastCycleJobs.NIGHTLY_JOB, DAY);

        assertThat(sunrise).isNotNull();
        assertThat(deadlines).contains(new ForecastCycleWatchdog.Deadlines(ForecastCycleJobs.NIGHTLY_JOB,
                SolarEvent.SUNRISE, sunrise, sunrise.minus(Duration.ofMinutes(20)),
                sunrise.plus(Duration.ofMinutes(60)), 30, null));
    }

    @Test
    @DisplayName("the intraday cycle's window brackets today's sunset")
    void intradayWindowBracketsSunset() {
        Instant sunset = factory.eventInstant(SolarEvent.SUNSET, DAY);

        Optional<ForecastCycleWatchdog.Deadlines> deadlines = watchdog(factory).deadlines(
                ForecastCycleJobs.INTRADAY_JOB, DAY);

        assertThat(deadlines).contains(new ForecastCycleWatchdog.Deadlines(ForecastCycleJobs.INTRADAY_JOB,
                SolarEvent.SUNSET, sunset, sunset.minus(Duration.ofMinutes(20)),
                sunset.plus(Duration.ofMinutes(60)), 30, null));
    }

    @Test
    @DisplayName("the evening refresh serves no event: it has a start grace and the configured budget instead")
    void eveningHasBudgetNotWindow() {
        Optional<ForecastCycleWatchdog.Deadlines> deadlines = watchdog(factory).deadlines(
                ForecastCycleJobs.EVENING_JOB, DAY);

        assertThat(deadlines).contains(new ForecastCycleWatchdog.Deadlines(ForecastCycleJobs.EVENING_JOB, null,
                null, null, null, 30, 90L));
    }

    @Test
    @DisplayName("a job that is not a forecast cycle has no deadlines")
    void otherJobHasNone() {
        assertThat(watchdog(factory).deadlines("tide_refresh", DAY)).isEmpty();
    }

    @Test
    @DisplayName("a day on which the event does not occur has a start grace but no window")
    void noEventNoWindow() {
        SolarTriggerFactory polar = new SolarTriggerFactory(new SolarService(),
                new SolarReferencePoint("89.9", "0"));
        LocalDate midsummer = LocalDate.of(2027, 6, 21);

        Optional<ForecastCycleWatchdog.Deadlines> deadlines = watchdog(polar).deadlines(
                ForecastCycleJobs.NIGHTLY_JOB, midsummer);

        assertThat(polar.eventInstant(SolarEvent.SUNRISE, midsummer)).isNull();
        assertThat(deadlines).contains(new ForecastCycleWatchdog.Deadlines(ForecastCycleJobs.NIGHTLY_JOB,
                SolarEvent.SUNRISE, null, null, null, 30, null));
    }

    @Test
    @DisplayName("eventInstant is null when the calculator throws or returns nothing")
    void eventInstantNullOnCalculatorFailure() {
        SolarService throwing = mock(SolarService.class);
        when(throwing.sunriseUtc(54.776, -1.575, DAY)).thenThrow(new IllegalStateException("no sun"));
        when(throwing.sunsetUtc(54.776, -1.575, DAY)).thenReturn((LocalDateTime) null);
        SolarTriggerFactory broken = new SolarTriggerFactory(throwing, new SolarReferencePoint("54.776", "-1.575"));

        assertThat(broken.eventInstant(SolarEvent.SUNRISE, DAY)).isNull();
        assertThat(broken.eventInstant(SolarEvent.SUNSET, DAY)).isNull();
    }
}
