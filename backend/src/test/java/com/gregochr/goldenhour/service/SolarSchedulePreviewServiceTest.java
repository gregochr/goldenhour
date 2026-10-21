package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the solar preview: its figures are the trigger's own and, at Durham, A5's.
 */
class SolarSchedulePreviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

    private final SolarTriggerFactory factory = new SolarTriggerFactory(new SolarService(),
            new SolarReferencePoint("54.776", "-1.575"));
    private SchedulerJobConfigRepository configs;
    private PipelineRunRepository runs;
    private SolarSchedulePreviewService service;

    @BeforeEach
    void setUp() {
        configs = mock(SchedulerJobConfigRepository.class);
        runs = mock(PipelineRunRepository.class);
        service = new SolarSchedulePreviewService(configs, runs, factory,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(configs.findByJobKey(ForecastCycleJobs.NIGHTLY_JOB)).thenReturn(Optional.of(
                cronRow(ForecastCycleJobs.NIGHTLY_JOB)));
        when(configs.findByJobKey(ForecastCycleJobs.INTRADAY_JOB)).thenReturn(Optional.of(
                cronRow(ForecastCycleJobs.INTRADAY_JOB)));
    }

    private static SchedulerJobConfigEntity cronRow(String key) {
        return SchedulerJobConfigEntity.builder().jobKey(key).displayName(key)
                .scheduleType(ScheduleType.CRON).cronExpression("0 0 1 * * *")
                .status(SchedulerJobStatus.ACTIVE).build();
    }

    private static PipelineRunEntity run(Instant trigger, long seconds) {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, trigger);
        run.setStatus(PipelineRunStatus.COMPLETED);
        run.setCompletedAt(trigger.plusSeconds(seconds));
        return run;
    }

    @Test
    @DisplayName("a job that is not yet solar is shown under the plan's proposed rule, and the "
            + "year extremes reproduce plan A5 for both cycles")
    void notYetSolar_showsProposedRuleAndA5Extremes() {
        SolarSchedulePreviewService.Preview preview = service.preview();

        assertThat(preview.referencePoint().lat()).isEqualTo(54.776);
        assertThat(preview.referencePoint().lon()).isEqualTo(-1.575);
        assertThat(preview.generatedAt()).isEqualTo(NOW);
        assertThat(preview.jobs()).extracting(SolarSchedulePreviewService.JobPreview::jobKey)
                .containsExactly(ForecastCycleJobs.NIGHTLY_JOB, ForecastCycleJobs.INTRADAY_JOB);

        SolarSchedulePreviewService.JobPreview morning = preview.jobs().get(0);
        assertThat(morning.ruleSource()).isEqualTo("PROPOSED");
        assertThat(morning.solarEvent()).isEqualTo(SolarEvent.SUNRISE);
        assertThat(morning.offsetMinutes()).isEqualTo(255);
        assertThat(morning.cycleType()).isEqualTo("NIGHTLY");
        assertThat(morning.scheduleType()).isEqualTo("CRON");
        // Plan A5 quotes 00:13 / 04:17 / 11:39 / 17:50 (computed for 54.9N 1.9W). At the Durham
        // reference point the same rules give 00:13 / 04:15 / 11:39 / 17:48: two of the four agree
        // to the minute and the other two are 2 minutes earlier (a more easterly, more southerly
        // point has earlier sunrises and sunsets).
        assertThat(morning.earliest().fireLocal()).isEqualTo("00:13");
        assertThat(morning.earliest().date()).isBetween(LocalDate.of(2027, 6, 15), LocalDate.of(2027, 6, 21));
        assertThat(morning.latest().fireLocal()).isEqualTo("04:15");
        assertThat(morning.latest().date()).isBetween(LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 31));

        SolarSchedulePreviewService.JobPreview afternoon = preview.jobs().get(1);
        assertThat(afternoon.ruleSource()).isEqualTo("PROPOSED");
        assertThat(afternoon.solarEvent()).isEqualTo(SolarEvent.SUNSET);
        assertThat(afternoon.offsetMinutes()).isEqualTo(240);
        assertThat(afternoon.earliest().fireLocal()).isEqualTo("11:39");
        assertThat(afternoon.earliest().date()).isBetween(LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 18));
        assertThat(afternoon.latest().fireLocal()).isEqualTo("17:48");
        assertThat(afternoon.latest().date()).isBetween(LocalDate.of(2027, 6, 20), LocalDate.of(2027, 6, 28));
    }

    @Test
    @DisplayName("today's and tomorrow's figures are the trigger's own, for the UK dates of the clock")
    void todayAndTomorrow_matchTheTrigger() {
        SolarOffsetTrigger trigger = factory.create(ForecastCycleJobs.NIGHTLY_JOB, SolarEvent.SUNRISE,
                255, "0 0 1 * * *");
        SolarOffsetTrigger.Fire today = trigger.fireFor(LocalDate.of(2026, 10, 13));
        SolarOffsetTrigger.Fire tomorrow = trigger.fireFor(LocalDate.of(2026, 10, 14));

        SolarSchedulePreviewService.JobPreview morning = service.preview().jobs().get(0);

        assertThat(morning.today().date()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(morning.today().eventInstant()).isEqualTo(today.eventInstant());
        assertThat(morning.today().fireInstant()).isEqualTo(today.fire());
        assertThat(morning.today().clamped()).isFalse();
        assertThat(morning.today().fallback()).isFalse();
        assertThat(morning.tomorrow().date()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(morning.tomorrow().fireInstant()).isEqualTo(tomorrow.fire());
    }

    @Test
    @DisplayName("a job already switched to SOLAR_OFFSET is shown under its stored rule")
    void solarRow_showsStoredRule() {
        SchedulerJobConfigEntity solar = SchedulerJobConfigEntity.builder()
                .jobKey(ForecastCycleJobs.INTRADAY_JOB).displayName("x")
                .scheduleType(ScheduleType.SOLAR_OFFSET).solarEvent(SolarEvent.SUNSET)
                .solarOffsetMinutes(100).cronExpression("0 0 14 * * *")
                .status(SchedulerJobStatus.ACTIVE).build();
        when(configs.findByJobKey(ForecastCycleJobs.INTRADAY_JOB)).thenReturn(Optional.of(solar));

        SolarSchedulePreviewService.JobPreview afternoon = service.preview().jobs().get(1);

        assertThat(afternoon.ruleSource()).isEqualTo("STORED");
        assertThat(afternoon.offsetMinutes()).isEqualTo(100);
        assertThat(afternoon.scheduleType()).isEqualTo("SOLAR_OFFSET");
        assertThat(afternoon.storedCron()).isEqualTo("0 0 14 * * *");
        assertThat(afternoon.today().eventInstant().minusSeconds(100 * 60L))
                .isEqualTo(afternoon.today().fireInstant());
    }

    @Test
    @DisplayName("the median duration is over the sample: odd count -> middle, even -> mean of the "
            + "middle two, none -> null")
    void medianDuration() {
        Instant t = Instant.parse("2026-10-01T01:00:00Z");
        when(runs.findTop14ByCycleTypeAndStatusOrderByTriggerTimeDesc(CycleType.NIGHTLY,
                PipelineRunStatus.COMPLETED)).thenReturn(List.of(run(t, 600), run(t, 60), run(t, 120)));
        when(runs.findTop14ByCycleTypeAndStatusOrderByTriggerTimeDesc(CycleType.INTRADAY,
                PipelineRunStatus.COMPLETED)).thenReturn(List.of());

        SolarSchedulePreviewService.Preview odd = service.preview();

        assertThat(odd.jobs().get(0).medianDurationSeconds()).isEqualTo(120L);
        assertThat(odd.jobs().get(1).medianDurationSeconds()).isNull();

        when(runs.findTop14ByCycleTypeAndStatusOrderByTriggerTimeDesc(CycleType.NIGHTLY,
                PipelineRunStatus.COMPLETED)).thenReturn(
                        List.of(run(t, 100), run(t, 300), run(t, 200), run(t, 1000)));
        assertThat(service.preview().jobs().get(0).medianDurationSeconds()).isEqualTo(250L);
    }

    @Test
    @DisplayName("a missing row still previews under the proposed rule, with no stored type or status")
    void missingRow_stillPreviews() {
        when(configs.findByJobKey(ForecastCycleJobs.NIGHTLY_JOB)).thenReturn(Optional.empty());

        SolarSchedulePreviewService.JobPreview morning = service.preview().jobs().get(0);

        assertThat(morning.scheduleType()).isNull();
        assertThat(morning.status()).isNull();
        assertThat(morning.ruleSource()).isEqualTo("PROPOSED");
        assertThat(LocalTime.parse(morning.latest().fireLocal())).isAfter(LocalTime.of(4, 0));
    }
}
