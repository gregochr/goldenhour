package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.model.runs.RunGroup;
import com.gregochr.goldenhour.model.runs.RunKind;
import com.gregochr.goldenhour.model.runs.RunListItem;
import com.gregochr.goldenhour.model.runs.RunListStatus;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.service.BatchSummaryDeriver;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ForecastCycleJobs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Service tests (H2, no Docker) for the Scheduler's per-job runs: exactly five jobs are attributable, each to its
 * own source; every other job has none; items read by the Runs list's rules.
 */
@DataJpaTest
@Import({AdminRunsService.class, SchedulerRunsService.class, SchedulerRunsServiceTest.ClockConfig.class})
class SchedulerRunsServiceTest {

    /** Saturday 31 October 2026, noon UTC. */
    private static final Instant NOON = Instant.parse("2026-10-31T12:00:00Z");

    /** Supplies the fixed application {@link Clock}. */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOON, ZoneOffset.UTC);
        }
    }

    @Autowired
    private SchedulerRunsService service;

    @Autowired
    private TestEntityManager em;

    @MockitoBean
    private BatchSummaryDeriver deriver;

    @MockitoBean
    private DynamicSchedulerService scheduler;

    @MockitoBean
    private SchedulerJobConfigRepository jobConfigs;

    private PipelineRunEntity cycle(CycleType type, Instant trigger, PipelineRunStatus status) {
        PipelineRunEntity run = new PipelineRunEntity(type, trigger);
        run.setStatus(status);
        if (status != PipelineRunStatus.RUNNING) {
            run.setCompletedAt(trigger.plusSeconds(600));
        }
        return em.persist(run);
    }

    private JobRunEntity job(RunType type, LocalDateTime startedAt, boolean manual, boolean completed,
            int succeeded, int failed) {
        return em.persist(JobRunEntity.builder().runType(type).startedAt(startedAt)
                .completedAt(completed ? startedAt.plusMinutes(5) : null)
                .durationMs(completed ? 300_000L : null)
                .succeeded(succeeded).failed(failed).locationsProcessed(succeeded + failed)
                .triggeredManually(manual).build());
    }

    private void batch(String anthropicId, BatchType type, Long jobRunId) {
        ForecastBatchEntity batch = new ForecastBatchEntity(anthropicId, type, 10, NOON.plusSeconds(86_400));
        batch.setJobRunId(jobRunId);
        em.persist(batch);
    }

    private void commit() {
        em.flush();
        em.clear();
    }

    private static List<Long> ids(List<RunListItem> items) {
        return items.stream().map(RunListItem::id).toList();
    }

    @Test
    @DisplayName("each cycle job maps to the pipeline runs of its own cycle type, the evening one included")
    void cycleJobsMapToTheirOwnType() {
        PipelineRunEntity nightly = cycle(CycleType.NIGHTLY, NOON.minusSeconds(3_000), PipelineRunStatus.COMPLETED);
        PipelineRunEntity intraday = cycle(CycleType.INTRADAY, NOON.minusSeconds(2_000), PipelineRunStatus.COMPLETED);
        PipelineRunEntity evening = cycle(CycleType.EVENING, NOON.minusSeconds(1_000), PipelineRunStatus.DEGRADED);
        commit();

        for (ForecastCycleJobs.Job job : ForecastCycleJobs.allCycleJobs()) {
            assertThat(SchedulerRunsService.groupOf(job.key())).contains(RunGroup.CYCLES);
        }
        assertThat(ids(service.recentRuns(ForecastCycleJobs.NIGHTLY_JOB, 3))).containsExactly(nightly.getId());
        assertThat(ids(service.recentRuns(ForecastCycleJobs.INTRADAY_JOB, 3))).containsExactly(intraday.getId());
        List<RunListItem> eveningRuns = service.recentRuns(ForecastCycleJobs.EVENING_JOB, 3);
        assertThat(ids(eveningRuns)).containsExactly(evening.getId());
        assertThat(eveningRuns.get(0).kind()).isEqualTo(RunKind.CYCLE);
        assertThat(eveningRuns.get(0).name()).isEqualTo("Evening sunrise refresh");
        assertThat(eveningRuns.get(0).status()).isEqualTo(RunListStatus.DEGRADED);
    }

    @Test
    @DisplayName("recent runs are newest first and no more than the limit")
    void limitAndOrder() {
        PipelineRunEntity oldest = cycle(CycleType.NIGHTLY, NOON.minusSeconds(40_000), PipelineRunStatus.COMPLETED);
        PipelineRunEntity middle = cycle(CycleType.NIGHTLY, NOON.minusSeconds(30_000), PipelineRunStatus.COMPLETED);
        PipelineRunEntity newest = cycle(CycleType.NIGHTLY, NOON.minusSeconds(20_000), PipelineRunStatus.COMPLETED);
        PipelineRunEntity newer = cycle(CycleType.NIGHTLY, NOON.minusSeconds(10_000), PipelineRunStatus.COMPLETED);
        commit();

        assertThat(ids(service.recentRuns(ForecastCycleJobs.NIGHTLY_JOB, 3)))
                .containsExactly(newer.getId(), newest.getId(), middle.getId());
        assertThat(ids(service.recentRuns(ForecastCycleJobs.NIGHTLY_JOB, 1))).containsExactly(newer.getId());
        assertThat(oldest.getId()).isNotIn(ids(service.recentRuns(ForecastCycleJobs.NIGHTLY_JOB, 3)));
    }

    @Test
    @DisplayName("tide_refresh maps to scheduled TIDE runs only, never a manual one or another run type")
    void tideRefreshIsScheduledTideRunsOnly() {
        JobRunEntity scheduled = job(RunType.TIDE, LocalDateTime.of(2026, 10, 31, 6, 0), false, true, 12, 0);
        job(RunType.TIDE, LocalDateTime.of(2026, 10, 31, 9, 0), true, true, 12, 0);
        job(RunType.WEATHER, LocalDateTime.of(2026, 10, 31, 10, 0), false, true, 3, 0);
        commit();

        List<RunListItem> runs = service.recentRuns(SchedulerRunsService.TIDE_JOB, 3);

        assertThat(ids(runs)).containsExactly(scheduled.getId());
        assertThat(runs.get(0).kind()).isEqualTo(RunKind.JOB);
        assertThat(runs.get(0).group()).isEqualTo(RunGroup.TIDE);
        assertThat(runs.get(0).status()).isEqualTo(RunListStatus.DONE);
        assertThat(SchedulerRunsService.groupOf(SchedulerRunsService.TIDE_JOB)).contains(RunGroup.TIDE);
    }

    @Test
    @DisplayName("aurora_batch_evaluation maps to SCHEDULED_BATCH runs whose batch is AURORA, linked or by notes")
    void auroraBatchEvaluationIsAuroraBatchRunsOnly() {
        JobRunEntity linked = job(RunType.SCHEDULED_BATCH, LocalDateTime.of(2026, 10, 31, 7, 0), false, true, 8, 0);
        JobRunEntity byNotes = job(RunType.SCHEDULED_BATCH, LocalDateTime.of(2026, 10, 31, 8, 0), false, true, 8, 0);
        byNotes.setNotes("Anthropic batch: msgbatch_aurora_unlinked");
        JobRunEntity forecast = job(RunType.SCHEDULED_BATCH, LocalDateTime.of(2026, 10, 31, 9, 0), false, true, 8, 0);
        job(RunType.AURORA_EVALUATION, LocalDateTime.of(2026, 10, 31, 10, 0), false, true, 8, 0);
        batch("msgbatch_aurora", BatchType.AURORA, linked.getId());
        batch("msgbatch_aurora_unlinked", BatchType.AURORA, null);
        batch("msgbatch_forecast", BatchType.FORECAST, forecast.getId());
        commit();

        List<RunListItem> runs = service.recentRuns(SchedulerRunsService.AURORA_BATCH_JOB, 3);

        assertThat(ids(runs)).containsExactly(byNotes.getId(), linked.getId());
        assertThat(runs).allSatisfy(run -> {
            assertThat(run.group()).isEqualTo(RunGroup.AURORA);
            assertThat(run.name()).isEqualTo("Aurora batch");
        });
        assertThat(SchedulerRunsService.groupOf(SchedulerRunsService.AURORA_BATCH_JOB)).contains(RunGroup.AURORA);
    }

    @Test
    @DisplayName("every other job has no attributable runs, whatever the tables hold")
    void otherJobsHaveNone() {
        cycle(CycleType.NIGHTLY, NOON.minusSeconds(1_000), PipelineRunStatus.COMPLETED);
        job(RunType.TIDE, LocalDateTime.of(2026, 10, 31, 6, 0), false, true, 12, 0);
        job(RunType.AURORA_EVALUATION, LocalDateTime.of(2026, 10, 31, 6, 0), false, true, 12, 0);
        job(RunType.BRIEFING, LocalDateTime.of(2026, 10, 31, 6, 0), false, true, 12, 0);
        commit();

        for (String key : List.of("aurora_polling", "briefing_refresh", "cloud_verification_backfill",
                "light_pollution_enrichment", "no_such_job")) {
            assertThat(SchedulerRunsService.groupOf(key)).isEmpty();
            assertThat(service.recentRuns(key, 3)).isEmpty();
        }
        assertThat(service.lastRuns(List.of("aurora_polling", "briefing_refresh"))).isEmpty();
    }

    @Test
    @DisplayName("lastRuns serves one item per attributable job with a run, and omits the rest")
    void lastRunsPerJob() {
        PipelineRunEntity nightly = cycle(CycleType.NIGHTLY, NOON.minusSeconds(3_000), PipelineRunStatus.COMPLETED);
        JobRunEntity tide = job(RunType.TIDE, LocalDateTime.of(2026, 10, 31, 6, 0), false, true, 12, 0);
        commit();

        Map<String, RunListItem> last = service.lastRuns(List.of(ForecastCycleJobs.NIGHTLY_JOB,
                ForecastCycleJobs.INTRADAY_JOB, SchedulerRunsService.TIDE_JOB, SchedulerRunsService.AURORA_BATCH_JOB,
                "aurora_polling"));

        assertThat(last).containsOnlyKeys(ForecastCycleJobs.NIGHTLY_JOB, SchedulerRunsService.TIDE_JOB);
        assertThat(last.get(ForecastCycleJobs.NIGHTLY_JOB).id()).isEqualTo(nightly.getId());
        assertThat(last.get(SchedulerRunsService.TIDE_JOB).id()).isEqualTo(tide.getId());
    }

    @Test
    @DisplayName("a run's status reads by the Runs list's rules: partial, a stale run, an aborted run, a running cycle")
    void lastRunStatusFollowsTheRunsListRules() {
        job(RunType.TIDE, LocalDateTime.of(2026, 10, 31, 6, 0), false, true, 9, 1);
        commit();
        assertThat(service.recentRuns(SchedulerRunsService.TIDE_JOB, 1).get(0).status())
                .isEqualTo(RunListStatus.PARTIAL);
        assertThat(service.recentRuns(SchedulerRunsService.TIDE_JOB, 1).get(0).note()).isEqualTo("1 requests errored");

        // Older than the six hour ceiling and never closed: Failed, "Did not finish".
        job(RunType.TIDE, LocalDateTime.of(2026, 10, 31, 11, 0), false, false, 0, 0);
        LocalDateTime sevenHoursAgo = LocalDateTime.of(2026, 10, 31, 12, 0).minusHours(7);
        JobRunEntity stale = job(RunType.TIDE, sevenHoursAgo, false, false, 0, 0);
        commit();
        List<RunListItem> afterStale = service.recentRuns(SchedulerRunsService.TIDE_JOB, 3);
        assertThat(afterStale.get(0).status()).isEqualTo(RunListStatus.RUNNING);
        RunListItem staleItem = afterStale.stream().filter(i -> i.id().equals(stale.getId())).findFirst().orElseThrow();
        assertThat(staleItem.status()).isEqualTo(RunListStatus.FAILED);
        assertThat(staleItem.note()).isEqualTo("Did not finish");

        cycle(CycleType.INTRADAY, NOON.minusSeconds(500), PipelineRunStatus.RUNNING);
        commit();
        assertThat(service.recentRuns(ForecastCycleJobs.INTRADAY_JOB, 1).get(0).status())
                .isEqualTo(RunListStatus.RUNNING);
    }

    @Test
    @DisplayName("recentRunsForJob: an unknown job is empty, a known unmapped job is an empty list")
    void unknownJobVersusUnmappedJob() {
        when(jobConfigs.findByJobKey("no_such_job")).thenReturn(Optional.empty());
        when(jobConfigs.findByJobKey("aurora_polling"))
                .thenReturn(Optional.of(SchedulerJobConfigEntity.builder().jobKey("aurora_polling").build()));
        when(jobConfigs.findByJobKey(SchedulerRunsService.TIDE_JOB)).thenReturn(Optional.of(
                SchedulerJobConfigEntity.builder().jobKey(SchedulerRunsService.TIDE_JOB).build()));
        JobRunEntity tide = job(RunType.TIDE, LocalDateTime.of(2026, 10, 31, 6, 0), false, true, 12, 0);
        commit();

        assertThat(service.recentRunsForJob("no_such_job", 3)).isEmpty();
        assertThat(service.recentRunsForJob("aurora_polling", 3)).contains(List.of());
        assertThat(service.recentRunsForJob(SchedulerRunsService.TIDE_JOB, 3).orElseThrow())
                .extracting(RunListItem::id).containsExactly(tide.getId());
    }
}
