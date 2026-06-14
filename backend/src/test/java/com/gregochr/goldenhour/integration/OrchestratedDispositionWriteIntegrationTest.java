package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastRunDispositionEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CandidateDisposition;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.ForecastRunDispositionRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.batch.ForecastTaskCollector;
import com.gregochr.goldenhour.service.batch.NightlyCandidateCollectionStrategy;
import com.gregochr.goldenhour.service.batch.NightlyEligibilityPolicy;
import com.gregochr.goldenhour.service.batch.ScheduledBatchTasks;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import com.gregochr.goldenhour.service.pipeline.PipelineOrchestrator;
import com.gregochr.goldenhour.service.pipeline.PipelineRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.gregochr.goldenhour.integration.AnthropicWireMockFixtures.RequestCounts;
import static com.gregochr.goldenhour.integration.AnthropicWireMockFixtures.stubBatchCreate;
import static com.gregochr.goldenhour.integration.AnthropicWireMockFixtures.stubBatchRetrieve;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Real-path integration test proving the orchestrated nightly cycle writes
 * {@code forecast_run_disposition} rows — the regression test for the bug where
 * the disposition persist was silently skipped on every live cycle.
 *
 * <p><b>The saga.</b> V101 introduced the disposition write but the seam dropped
 * the jobRunId (fixed in #111). #111's unit tests mocked the seam, so they
 * stayed green while prod still wrote nothing — because the persist lived after
 * an {@code if (tasks.isEmpty()) return;} guard in {@code doSubmitForecastBatch}.
 * On the all-cached / all-skipped cycle (no bucket submitted) that guard
 * returned before the persist, discarding the collector's dispositions. The
 * {@code [DISPOSITION] Persisting} smoke-log never appeared in prod because that
 * line was never reached.
 *
 * <p><b>Why this test would have caught it.</b> It drives the REAL
 * {@link PipelineOrchestrator} cycle — orchestrator → submit phase →
 * {@code ScheduledBatchEvaluationService.submitForecastBatchForPipelineRun} →
 * {@code doSubmitForecastBatch} → real {@code ForecastDispositionService} → real
 * Postgres — and asserts rows land. Only two collaborators are stubbed, and
 * NEITHER is the submission seam: {@link ForecastTaskCollector} supplies
 * controlled cycle input (its disposition-building is unit-tested elsewhere),
 * and {@link BriefingService} is a no-op so the briefing phase does no real
 * work. Everything from the submission seam down — EvaluationService,
 * BatchSubmissionService, JobRunService, ForecastDispositionService, the
 * repositories — is the real wiring.
 */
class OrchestratedDispositionWriteIntegrationTest extends IntegrationTestBase {

    @Autowired
    private PipelineOrchestrator orchestrator;

    @Autowired
    private com.gregochr.goldenhour.service.batch.ScheduledBatchEvaluationService
            scheduledBatchEvaluationService;

    @Autowired
    private PipelineRunService pipelineRunService;

    @Autowired
    private ForecastRunDispositionRepository dispositionRepository;

    @Autowired
    private JobRunRepository jobRunRepository;

    @Autowired
    private ForecastBatchRepository forecastBatchRepository;

    @Autowired
    private ApiCallLogRepository apiCallLogRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private PipelineRunRepository pipelineRunRepository;

    /**
     * Controlled cycle input. Mocking the collector is NOT mocking the
     * submission seam — it supplies the tasks+dispositions a real collector
     * would, letting the test exercise the real submit→persist→DB path.
     */
    @MockitoBean
    private ForecastTaskCollector forecastTaskCollector;

    /** No-op so the orchestrator's BRIEFING phase does no real Open-Meteo work. */
    @MockitoBean
    private BriefingService briefingService;

    @AfterEach
    void clearDataBetweenTests() {
        dispositionRepository.deleteAll();
        apiCallLogRepository.deleteAll();
        forecastBatchRepository.deleteAll();
        jobRunRepository.deleteAll();
        pipelineRunRepository.deleteAll();
        locationRepository.deleteAll();
        regionRepository.deleteAll();
    }

    @Test
    @DisplayName("Orchestrated all-cached cycle (zero buckets submitted) still writes "
            + "disposition rows against a disposition-anchor run — the live-bug repro")
    void orchestratedCycle_allCachedNoBuckets_writesDispositionsAgainstAnchorRun() {
        // The exact prod scenario: every candidate is cache-fresh, so the
        // collector produces SKIPPED_CACHED dispositions but ZERO bucket tasks.
        // The old `if (tasks.isEmpty()) return;` discarded these. There is no
        // batch job_run, so the persist must fall back to a disposition-anchor run.
        LocalDate date = LocalDate.now().plusDays(1);
        List<CandidateDisposition> dispositions = List.of(
                new CandidateDisposition(null, "Cached Loc A", date,
                        TargetType.SUNRISE, 1, DispositionCategory.SKIPPED_CACHED,
                        "Fresh cached evaluation within 36h (SETTLED)"),
                new CandidateDisposition(null, "Cached Loc B", date,
                        TargetType.SUNSET, 1, DispositionCategory.SKIPPED_CACHED,
                        "Fresh cached evaluation within 36h (SETTLED)"),
                new CandidateDisposition(null, "Tide Loc", date,
                        TargetType.SUNRISE, 1, DispositionCategory.SKIPPED_HARD_CONSTRAINT,
                        "Tide mismatch"));
        // The orchestrated nightly cycle passes the nightly strategy + policy
        // singletons down to the collector; stub on those exact instances (not
        // any()) so a future change that routes a different strategy/policy
        // through the nightly path fails this test loudly instead of silently
        // matching.
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(), List.of(), List.of(), List.of(), List.of(), dispositions));

        // Drive the REAL orchestrator cycle synchronously. Zero batches → the
        // wait phase sees an empty batch set (terminal immediately) → briefing
        // (no-op) → run completes. No Anthropic interaction at all.
        PipelineRunEntity run = pipelineRunService.startRun(CycleType.NIGHTLY);
        orchestrator.runCycleSynchronously(run);

        // The assertion the whole saga was missing: rows actually in the table.
        List<ForecastRunDispositionEntity> rows = dispositionRepository.findAll();
        assertThat(rows)
                .as("All-cached orchestrated cycle must still persist disposition rows")
                .hasSize(dispositions.size());

        // They must be anchored to a real, queryable job_run (the disposition
        // anchor run) so the Job Run detail UI can surface them.
        Long anchorJobRunId = rows.get(0).getJobRunId();
        assertThat(anchorJobRunId).isNotNull();
        assertThat(rows).allSatisfy(r ->
                assertThat(r.getJobRunId()).isEqualTo(anchorJobRunId));
        assertThat(jobRunRepository.findById(anchorJobRunId))
                .as("Anchor job_run must exist and be queryable for the UI")
                .hasValueSatisfying(jr -> assertThat(jr.getNotes())
                        .contains("Disposition-only cycle"));

        // Reconciliation by category — the on-screen totals depend on this.
        assertThat(rows).filteredOn(r -> "SKIPPED_CACHED".equals(r.getDisposition()))
                .hasSize(2);
        assertThat(rows).filteredOn(r -> "SKIPPED_HARD_CONSTRAINT".equals(r.getDisposition()))
                .hasSize(1);
    }

    @Test
    @DisplayName("Orchestrated cycle with a submitted bucket writes disposition rows "
            + "against the real batch job_run created by the live submission seam")
    void orchestratedSubmit_withBucket_writesDispositionsAgainstBatchJobRun() {
        // A normal cycle: one inland task is bucketed and submitted, alongside a
        // skipped candidate. The dispositions must anchor to the batch job_run
        // that the REAL EvaluationServiceImpl → BatchSubmissionService → JobRunService
        // seam creates — the seam that #111 fixed and that this test exercises
        // for real (no mock on the handle).
        LocationEntity loc = seedLocation("Castlerigg", 54.6029, -3.0980);
        LocalDate date = LocalDate.now().plusDays(1);
        EvaluationTask.Forecast task = new EvaluationTask.Forecast(
                loc, date, TargetType.SUNRISE, EvaluationModel.HAIKU,
                TestAtmosphericData.builder()
                        .locationName(loc.getName())
                        .solarEventTime(date.atTime(5, 30))
                        .targetType(TargetType.SUNRISE)
                        .build(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        List<CandidateDisposition> dispositions = List.of(
                new CandidateDisposition(loc.getId(), loc.getName(), date,
                        TargetType.SUNRISE, 1, DispositionCategory.EVALUATED, null),
                new CandidateDisposition(99L, "Triaged Loc", date,
                        TargetType.SUNSET, 1, DispositionCategory.SKIPPED_TRIAGED,
                        "Solar horizon low cloud 94% — sun blocked"));
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(task), List.of(), List.of(), List.of(), List.of(), dispositions));

        String batchId = "msgbatch_orchestrated";
        WIRE_MOCK.stubFor(stubBatchCreate(batchId));
        WIRE_MOCK.stubFor(stubBatchRetrieve(batchId, "in_progress",
                new RequestCounts(1, 0, 0, 0, 0)));

        // Exercise the orchestrated submit entry point directly (the exact method
        // the orchestrator's submit phase calls). We don't drive the full
        // runCycleSynchronously here because the wait phase would block on the
        // batch reaching terminal status (no polling service runs in this test);
        // the disposition persist happens entirely within this submit call,
        // before the wait phase, so this is the faithful disposition path.
        PipelineRunEntity run = pipelineRunService.startRun(CycleType.NIGHTLY);
        scheduledBatchEvaluationService.submitForecastBatchForPipelineRun(run.getId());

        // Dispositions written and anchored to the batch's real job_run.
        List<ForecastRunDispositionEntity> rows = dispositionRepository.findAll();
        assertThat(rows).hasSize(2);
        Long jobRunId = rows.get(0).getJobRunId();
        assertThat(jobRunId).isNotNull();
        // The anchor must be the BATCH job_run (note carries the Anthropic batch id),
        // not a disposition-only anchor — a real submission happened.
        assertThat(jobRunRepository.findById(jobRunId))
                .hasValueSatisfying(jr -> assertThat(jr.getNotes()).contains(batchId));
        // And it ties back to the forecast_batch row tagged with this pipeline run.
        assertThat(forecastBatchRepository.findByPipelineRunId(run.getId()))
                .as("Batch must be tagged with the pipeline run id")
                .hasSize(1);
    }

    @Test
    @DisplayName("Intraday cycle persists SKIPPED_NO_REFRESH_NEEDED rows through the real "
            + "submit → ForecastDispositionService → DB path (the new disposition value lands)")
    void orchestratedIntradaySubmit_settledSkip_writesNoRefreshNeededRows() {
        // The intraday acceptance bar: a settled decision-window candidate is
        // skipped and recorded as SKIPPED_NO_REFRESH_NEEDED. This test proves the
        // VALUE round-trips through the real persistence path (VARCHAR column,
        // disposition-anchor run for a zero-bucket cycle) — not a mocked seam.
        LocalDate date = LocalDate.now().plusDays(1);
        List<CandidateDisposition> dispositions = List.of(
                new CandidateDisposition(null, "Settled Loc A", date,
                        TargetType.SUNSET, 0, DispositionCategory.SKIPPED_NO_REFRESH_NEEDED,
                        "settled — no intraday refresh needed"),
                new CandidateDisposition(null, "Unsettled Loc B", date.plusDays(1),
                        TargetType.SUNRISE, 1, DispositionCategory.EVALUATED, null));
        // Intraday passes its own policy + ephemeral=true; the candidate strategy
        // is a fresh per-cycle instance, so match it with any().
        when(forecastTaskCollector.collectScheduledBatches(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(
                        com.gregochr.goldenhour.service.batch.IntradayEligibilityPolicy.INSTANCE),
                org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(), List.of(), List.of(), List.of(), List.of(), dispositions));

        // Exercise the real orchestrated submit entry directly with intraday's
        // inputs (the exact 5-arg method the orchestrator's submit phase calls for
        // an INTRADAY cycle). No buckets → disposition-anchor run → persist.
        PipelineRunEntity run = pipelineRunService.startRun(CycleType.INTRADAY);
        scheduledBatchEvaluationService.submitForecastBatchForPipelineRun(
                run.getId(),
                new com.gregochr.goldenhour.service.batch.IntradayCandidateCollectionStrategy(
                        java.time.Clock.systemUTC()),
                com.gregochr.goldenhour.service.batch.IntradayEligibilityPolicy.INSTANCE,
                true,
                s -> { });

        List<ForecastRunDispositionEntity> rows = dispositionRepository.findAll();
        assertThat(rows).hasSize(2);
        assertThat(rows)
                .filteredOn(r -> "SKIPPED_NO_REFRESH_NEEDED".equals(r.getDisposition()))
                .as("The new intraday disposition value must persist to the DB")
                .hasSize(1)
                .allSatisfy(r -> assertThat(r.getDetail()).contains("settled"));
        assertThat(rows).filteredOn(r -> "EVALUATED".equals(r.getDisposition())).hasSize(1);
    }

    private LocationEntity seedLocation(String name, double lat, double lon) {
        RegionEntity region = regionRepository.save(RegionEntity.builder()
                .name("Test Region " + name)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());
        return locationRepository.save(LocationEntity.builder()
                .name(name)
                .lat(lat)
                .lon(lon)
                .region(region)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());
    }
}
