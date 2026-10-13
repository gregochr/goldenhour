package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the D1 catch-up rule: a solar job that missed today's fire runs once on startup only while
 * useful lead remains and only if its cycle has not run since the fire.
 */
class SolarCatchUpPolicyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);

    private final SolarTriggerFactory factory = new SolarTriggerFactory(new SolarService(),
            new SolarReferencePoint("54.776", "-1.575"));
    private PipelineRunRepository runs;

    @BeforeEach
    void setUp() {
        runs = mock(PipelineRunRepository.class);
    }

    private SolarCatchUpPolicy policyAt(Instant now) {
        return new SolarCatchUpPolicy(factory, runs, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static SchedulerJobConfigEntity solarJob(String key, SolarEvent event, int offset) {
        return SchedulerJobConfigEntity.builder().jobKey(key).displayName(key)
                .scheduleType(ScheduleType.SOLAR_OFFSET).solarEvent(event).solarOffsetMinutes(offset)
                .cronExpression("0 0 1 * * *").status(SchedulerJobStatus.ACTIVE).build();
    }

    private SolarOffsetTrigger.Fire fireOf(SchedulerJobConfigEntity job) {
        return factory.create(job.getJobKey(), job.getSolarEvent(), job.getSolarOffsetMinutes(),
                job.getCronExpression()).fireFor(DAY);
    }

    @Test
    @DisplayName("morning job: fire passed, no run since, before 05:00 UK -> catch up, and the "
            + "run lookup used the cycle type and the fire instant")
    void morning_insideWindow_catchesUp() {
        SchedulerJobConfigEntity job = solarJob(SolarForecastJobs.NIGHTLY_JOB, SolarEvent.SUNRISE, 255);
        Instant fire = fireOf(job).fire();
        Instant now = fire.plus(Duration.ofMinutes(30));
        assertThat(now.atZone(SolarOffsetTrigger.LONDON).getHour()).as("test premise").isLessThan(5);
        when(runs.countRunsPastSubmission(CycleType.NIGHTLY, fire, PipelinePhase.PRE_SUBMISSION))
                .thenReturn(0L);

        Optional<String> reason = policyAt(now).reasonToRun(job);

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("sunrise", "NIGHTLY");
    }

    @Test
    @DisplayName("morning job: 04:59 UK is inside the window, 05:00 UK is not")
    void morning_windowClosesAtFiveUk() {
        SchedulerJobConfigEntity job = solarJob(SolarForecastJobs.NIGHTLY_JOB, SolarEvent.SUNRISE, 255);
        // 13 Oct is BST: 04:59 UK = 03:59Z and 05:00 UK = 04:00Z.
        Instant justInside = Instant.parse("2026-10-13T03:59:00Z");
        Instant closed = Instant.parse("2026-10-13T04:00:00Z");

        assertThat(policyAt(justInside).reasonToRun(job)).isPresent();
        assertThat(policyAt(closed).reasonToRun(job)).isEmpty();
    }

    @Test
    @DisplayName("morning job: before today's fire there is nothing to catch up, and no run lookup is made")
    void morning_beforeFire_doesNothing() {
        SchedulerJobConfigEntity job = solarJob(SolarForecastJobs.NIGHTLY_JOB, SolarEvent.SUNRISE, 255);
        Instant now = fireOf(job).fire().minusSeconds(60);

        assertThat(policyAt(now).reasonToRun(job)).isEmpty();

        verifyNoInteractions(runs);
    }

    @Test
    @DisplayName("a cycle that already has a run at or after the fire is never caught up")
    void alreadyRun_doesNothing() {
        SchedulerJobConfigEntity job = solarJob(SolarForecastJobs.NIGHTLY_JOB, SolarEvent.SUNRISE, 255);
        Instant fire = fireOf(job).fire();
        when(runs.countRunsPastSubmission(CycleType.NIGHTLY, fire, PipelinePhase.PRE_SUBMISSION))
                .thenReturn(1L);

        assertThat(policyAt(fire.plus(Duration.ofMinutes(30))).reasonToRun(job)).isEmpty();

        verify(runs).countRunsPastSubmission(CycleType.NIGHTLY, fire, PipelinePhase.PRE_SUBMISSION);
    }

    @Test
    @DisplayName("afternoon job: catches up while at least 90 minutes remain before sunset, not after")
    void afternoon_windowClosesNinetyMinutesBeforeSunset() {
        SchedulerJobConfigEntity job = solarJob(SolarForecastJobs.INTRADAY_JOB, SolarEvent.SUNSET, 240);
        SolarOffsetTrigger.Fire fire = fireOf(job);
        Instant exactlyNinety = fire.eventInstant().minus(Duration.ofMinutes(90));
        Instant eightyNine = fire.eventInstant().minus(Duration.ofMinutes(89));
        when(runs.countRunsPastSubmission(CycleType.INTRADAY, fire.fire(), PipelinePhase.PRE_SUBMISSION))
                .thenReturn(0L);

        assertThat(policyAt(exactlyNinety).reasonToRun(job)).isPresent();
        assertThat(policyAt(eightyNine).reasonToRun(job)).isEmpty();
    }

    @Test
    @DisplayName("a morning catch-up window of the afternoon job's kind does not apply: a sunset job "
            + "ignores the 05:00 rule and a sunrise job ignores the sunset rule")
    void windowsAreSpecificToTheEvent() {
        SchedulerJobConfigEntity afternoon = solarJob(SolarForecastJobs.INTRADAY_JOB, SolarEvent.SUNSET, 240);
        SolarOffsetTrigger.Fire fire = fireOf(afternoon);
        // Well after 05:00 UK, but 2 h before sunset: the afternoon job is inside its window.
        Instant now = fire.eventInstant().minus(Duration.ofHours(2));
        assertThat(now.atZone(SolarOffsetTrigger.LONDON).getHour()).as("test premise").isGreaterThan(5);

        assertThat(policyAt(now).reasonToRun(afternoon)).isPresent();
    }

    @Test
    @DisplayName("a CRON job, an incomplete solar row and a job outside the forecast pair are never caught up")
    void ineligibleRows_doNothing() {
        Instant now = Instant.parse("2026-10-13T03:00:00Z");
        SchedulerJobConfigEntity cron = SchedulerJobConfigEntity.builder()
                .jobKey(SolarForecastJobs.NIGHTLY_JOB).scheduleType(ScheduleType.CRON)
                .cronExpression("0 0 1 * * *").build();
        SchedulerJobConfigEntity incomplete = SchedulerJobConfigEntity.builder()
                .jobKey(SolarForecastJobs.NIGHTLY_JOB).scheduleType(ScheduleType.SOLAR_OFFSET)
                .cronExpression("0 0 1 * * *").build();
        SchedulerJobConfigEntity other = solarJob("tide_refresh", SolarEvent.SUNRISE, 255);

        assertThat(policyAt(now).reasonToRun(cron)).isEmpty();
        assertThat(policyAt(now).reasonToRun(incomplete)).isEmpty();
        assertThat(policyAt(now).reasonToRun(other)).isEmpty();
        verifyNoInteractions(runs);
    }

    @Test
    @DisplayName("a day on which the solar fire is unusable is not caught up")
    void unusableFire_doesNothing() {
        SolarTriggerFactory broken = mock(SolarTriggerFactory.class);
        when(broken.create(SolarForecastJobs.NIGHTLY_JOB, SolarEvent.SUNRISE, 255, "0 0 1 * * *"))
                .thenReturn(new SolarOffsetTrigger(SolarForecastJobs.NIGHTLY_JOB, SolarEvent.SUNRISE,
                        255, "0 0 1 * * *", (event, date) -> date.atStartOfDay()));
        SolarCatchUpPolicy policy = new SolarCatchUpPolicy(broken, runs,
                Clock.fixed(Instant.parse("2026-10-13T03:00:00Z"), ZoneOffset.UTC));

        assertThat(policy.reasonToRun(solarJob(SolarForecastJobs.NIGHTLY_JOB, SolarEvent.SUNRISE, 255)))
                .isEmpty();
        verifyNoInteractions(runs);
    }
}
