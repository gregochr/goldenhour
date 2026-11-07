package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Edge cases of {@link SolarSchedulePreviewService}: a SOLAR_OFFSET row missing its rule, days whose solar
 * calculation yields nothing (reported as fallbacks) and pipeline runs without timings.
 */
class SolarSchedulePreviewServiceEdgeTest {

    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private static final String CRON = "0 0 1 * * *";

    private SchedulerJobConfigRepository configs;
    private PipelineRunRepository runs;
    private SolarSchedulePreviewService service;

    @BeforeEach
    void setUp() {
        configs = mock(SchedulerJobConfigRepository.class);
        runs = mock(PipelineRunRepository.class);
        // A solar calculator that never answers: every day's fire is unusable.
        SolarTriggerFactory factory = new SolarTriggerFactory(mock(SolarService.class),
                new SolarReferencePoint("54.776", "-1.575"));
        service = new SolarSchedulePreviewService(configs, runs, factory, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SchedulerJobConfigEntity row(String key, ScheduleType type, SolarEvent event, Integer offset) {
        return SchedulerJobConfigEntity.builder().jobKey(key).displayName(key).scheduleType(type)
                .solarEvent(event).solarOffsetMinutes(offset).cronExpression(CRON)
                .status(SchedulerJobStatus.ACTIVE).build();
    }

    private void stubRows(SchedulerJobConfigEntity nightly, SchedulerJobConfigEntity intraday) {
        when(configs.findByJobKey(ForecastCycleJobs.NIGHTLY_JOB)).thenReturn(Optional.of(nightly));
        when(configs.findByJobKey(ForecastCycleJobs.INTRADAY_JOB)).thenReturn(Optional.of(intraday));
    }

    @Test
    @DisplayName("a SOLAR_OFFSET row missing its event or offset is shown under the proposed rule")
    void incompleteSolarRow_showsProposedRule() {
        stubRows(row(ForecastCycleJobs.NIGHTLY_JOB, ScheduleType.SOLAR_OFFSET, SolarEvent.SUNRISE, null),
                row(ForecastCycleJobs.INTRADAY_JOB, ScheduleType.SOLAR_OFFSET, null, 100));

        SolarSchedulePreviewService.Preview preview = service.preview();

        SolarSchedulePreviewService.JobPreview nightly = preview.jobs().get(0);
        SolarSchedulePreviewService.JobPreview intraday = preview.jobs().get(1);
        assertThat(nightly.ruleSource()).isEqualTo("PROPOSED");
        assertThat(nightly.solarEvent()).isEqualTo(SolarEvent.SUNRISE);
        assertThat(nightly.offsetMinutes()).isEqualTo(255);
        assertThat(intraday.ruleSource()).isEqualTo("PROPOSED");
        assertThat(intraday.solarEvent()).isEqualTo(SolarEvent.SUNSET);
        assertThat(intraday.offsetMinutes()).isEqualTo(240);
    }

    @Test
    @DisplayName("days with no solar time are reported as fallbacks with the reason, and the year has no extremes")
    void unusableDays_reportedAsFallbacks() {
        stubRows(row(ForecastCycleJobs.NIGHTLY_JOB, ScheduleType.CRON, null, null),
                row(ForecastCycleJobs.INTRADAY_JOB, ScheduleType.CRON, null, null));

        SolarSchedulePreviewService.JobPreview nightly = service.preview().jobs().get(0);

        assertThat(nightly.today().fallback()).isTrue();
        assertThat(nightly.today().fireInstant()).isNull();
        assertThat(nightly.today().fallbackReason()).isEqualTo("the solar calculation returned no time");
        assertThat(nightly.tomorrow().fallback()).isTrue();
        assertThat(nightly.earliest()).isNull();
        assertThat(nightly.latest()).isNull();
    }

    @Test
    @DisplayName("the median ignores completed runs that lack a trigger time or a completion time")
    void medianDuration_skipsRunsWithoutTimings() {
        Instant trigger = Instant.parse("2026-10-12T01:00:00Z");
        PipelineRunEntity first = new PipelineRunEntity(CycleType.NIGHTLY, trigger);
        first.setStatus(PipelineRunStatus.COMPLETED);
        first.setCompletedAt(trigger.plusSeconds(600));
        PipelineRunEntity second = new PipelineRunEntity(CycleType.NIGHTLY, trigger.minusSeconds(86_400));
        second.setStatus(PipelineRunStatus.COMPLETED);
        second.setCompletedAt(trigger.minusSeconds(86_400).plusSeconds(1_000));
        PipelineRunEntity noCompletion = new PipelineRunEntity(CycleType.NIGHTLY, trigger.minusSeconds(172_800));
        noCompletion.setStatus(PipelineRunStatus.COMPLETED);
        PipelineRunEntity noTrigger = new PipelineRunEntity(CycleType.NIGHTLY, null);
        noTrigger.setStatus(PipelineRunStatus.COMPLETED);
        noTrigger.setCompletedAt(trigger);
        when(runs.findTop14ByCycleTypeAndStatusOrderByTriggerTimeDesc(CycleType.NIGHTLY,
                PipelineRunStatus.COMPLETED)).thenReturn(List.of(first, second, noCompletion, noTrigger));

        assertThat(service.medianDurationSeconds(CycleType.NIGHTLY)).isEqualTo(800L);
    }

    @Test
    @DisplayName("an odd number of timed runs gives the middle duration, none gives null")
    void medianDuration_oddAndEmpty() {
        Instant trigger = Instant.parse("2026-10-12T01:00:00Z");
        PipelineRunEntity only = new PipelineRunEntity(CycleType.INTRADAY, trigger);
        only.setStatus(PipelineRunStatus.COMPLETED);
        only.setCompletedAt(trigger.plusSeconds(300));
        when(runs.findTop14ByCycleTypeAndStatusOrderByTriggerTimeDesc(CycleType.INTRADAY,
                PipelineRunStatus.COMPLETED)).thenReturn(List.of(only));

        assertThat(service.medianDurationSeconds(CycleType.INTRADAY)).isEqualTo(300L);
        assertThat(service.medianDurationSeconds(CycleType.NIGHTLY)).isNull();
    }
}
