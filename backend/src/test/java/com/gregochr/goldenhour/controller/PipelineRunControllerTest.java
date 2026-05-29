package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelinePhaseStatus;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunPhaseEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for the Pipeline Run admin observability endpoints.
 */
class PipelineRunControllerTest extends AbstractControllerTest {

    private static final Instant T0 = Instant.parse("2026-05-26T01:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    private PipelineRunEntity completedRun() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, T0);
        run.setId(42L);
        run.setStatus(PipelineRunStatus.COMPLETED);
        run.setCompletedAt(T0.plusSeconds(900));
        return run;
    }

    private PipelineRunEntity waitingRun() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, T0);
        run.setId(43L);
        run.setStatus(PipelineRunStatus.RUNNING);
        run.setCurrentPhase(PipelinePhase.FORECAST_BATCH_WAIT);
        run.setWaitingOn("forecast batch set (2 of 4 complete)");
        return run;
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/admin/pipeline-runs returns the recent runs")
    void list_returns_recent_runs() throws Exception {
        when(pipelineRunService.findRecent())
                .thenReturn(List.of(waitingRun(), completedRun()));

        mockMvc.perform(get("/api/admin/pipeline-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(43))
                .andExpect(jsonPath("$[0].status").value("RUNNING"))
                .andExpect(jsonPath("$[0].currentPhase").value("FORECAST_BATCH_WAIT"))
                .andExpect(jsonPath("$[0].waitingOn")
                        .value("forecast batch set (2 of 4 complete)"))
                .andExpect(jsonPath("$[1].id").value(42))
                .andExpect(jsonPath("$[1].status").value("COMPLETED"))
                .andExpect(jsonPath("$[1].durationSeconds").value(900));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/admin/pipeline-runs/{id} returns phases + batches")
    void detail_returns_phases_and_batches() throws Exception {
        PipelineRunEntity run = completedRun();
        PipelineRunPhaseEntity submitPhase = new PipelineRunPhaseEntity(
                42L, PipelinePhase.FORECAST_BATCH_SUBMIT, 1, T0);
        submitPhase.setStatus(PipelinePhaseStatus.COMPLETED);
        submitPhase.setCompletedAt(T0.plusSeconds(60));

        PipelineRunPhaseEntity waitPhase = new PipelineRunPhaseEntity(
                42L, PipelinePhase.FORECAST_BATCH_WAIT, 2, T0.plusSeconds(60));
        waitPhase.setStatus(PipelinePhaseStatus.COMPLETED);
        waitPhase.setCompletedAt(T0.plusSeconds(600));
        waitPhase.setDetail("3 of 3 batches reached a terminal status");

        ForecastBatchEntity batch = new ForecastBatchEntity(
                "msgbatch_abc", BatchType.FORECAST, 50, T0.plusSeconds(86400));
        batch.setStatus(BatchStatus.COMPLETED);
        batch.setSucceededCount(48);
        batch.setErroredCount(2);
        batch.setJobRunId(101L);
        batch.setPipelineRunId(42L);
        // Use reflection-safe approach for id since it's not in the constructor.
        // PipelineRunControllerTest only checks ids that JPA would assign — fake via mock.

        when(pipelineRunService.findById(42L)).thenReturn(Optional.of(run));
        when(pipelineRunService.findPhases(42L)).thenReturn(List.of(submitPhase, waitPhase));
        when(batchRepository.findByPipelineRunId(42L)).thenReturn(List.of(batch));

        mockMvc.perform(get("/api/admin/pipeline-runs/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.id").value(42))
                .andExpect(jsonPath("$.run.status").value("COMPLETED"))
                .andExpect(jsonPath("$.phases.length()").value(2))
                .andExpect(jsonPath("$.phases[0].phase").value("FORECAST_BATCH_SUBMIT"))
                .andExpect(jsonPath("$.phases[0].durationSeconds").value(60))
                .andExpect(jsonPath("$.phases[1].phase").value("FORECAST_BATCH_WAIT"))
                .andExpect(jsonPath("$.phases[1].durationSeconds").value(540))
                .andExpect(jsonPath("$.phases[1].detail")
                        .value("3 of 3 batches reached a terminal status"))
                .andExpect(jsonPath("$.batches.length()").value(1))
                .andExpect(jsonPath("$.batches[0].anthropicBatchId").value("msgbatch_abc"))
                .andExpect(jsonPath("$.batches[0].jobRunId").value(101))
                .andExpect(jsonPath("$.batches[0].status").value("COMPLETED"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/admin/pipeline-runs/{id} surfaces the intraday-vs-nightly comparison")
    void detail_includes_cross_run_comparison_for_intraday() {
        PipelineRunEntity intraday = new PipelineRunEntity(CycleType.INTRADAY, T0.plusSeconds(46800));
        intraday.setId(50L);
        intraday.setStatus(PipelineRunStatus.COMPLETED);

        // Plan A changed region + rating, Plan B unchanged — the value signal.
        com.gregochr.goldenhour.model.PipelineRunPickComparison comparison =
                new com.gregochr.goldenhour.model.PipelineRunPickComparison(
                        42L, T0,
                        List.of(
                                new com.gregochr.goldenhour.model.PipelineRunPickComparison.PickDiff(
                                        1, true, List.of("REGION", "RATING"),
                                        new com.gregochr.goldenhour.model.PipelineRunPickComparison
                                                .PickView("Coast tonight", "North Yorkshire Coast",
                                                java.time.LocalDate.of(2026, 5, 26), "sunset",
                                                "HIGH", 4.2),
                                        new com.gregochr.goldenhour.model.PipelineRunPickComparison
                                                .PickView("Hills tonight", "Northumberland",
                                                java.time.LocalDate.of(2026, 5, 26), "sunset",
                                                "MEDIUM", 3.1)),
                                new com.gregochr.goldenhour.model.PipelineRunPickComparison.PickDiff(
                                        2, false, List.of(),
                                        new com.gregochr.goldenhour.model.PipelineRunPickComparison
                                                .PickView("Lakes tomorrow", "Lake District",
                                                java.time.LocalDate.of(2026, 5, 27), "sunrise",
                                                "MEDIUM", 3.5),
                                        new com.gregochr.goldenhour.model.PipelineRunPickComparison
                                                .PickView("Lakes tomorrow", "Lake District",
                                                java.time.LocalDate.of(2026, 5, 27), "sunrise",
                                                "MEDIUM", 3.5))));

        when(pipelineRunService.findById(50L)).thenReturn(Optional.of(intraday));
        when(pipelineRunService.findPhases(50L)).thenReturn(List.of());
        when(batchRepository.findByPipelineRunId(50L)).thenReturn(List.of());
        when(pipelineRunComparisonService.compareToSameDayNightly(intraday))
                .thenReturn(comparison);

        try {
            mockMvc.perform(get("/api/admin/pipeline-runs/50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.run.cycleType").value("INTRADAY"))
                    .andExpect(jsonPath("$.comparison.baselineRunId").value(42))
                    .andExpect(jsonPath("$.comparison.diffs.length()").value(2))
                    .andExpect(jsonPath("$.comparison.diffs[0].rank").value(1))
                    .andExpect(jsonPath("$.comparison.diffs[0].changed").value(true))
                    .andExpect(jsonPath("$.comparison.diffs[0].changedDimensions[0]")
                            .value("REGION"))
                    .andExpect(jsonPath("$.comparison.diffs[0].intraday.region")
                            .value("North Yorkshire Coast"))
                    .andExpect(jsonPath("$.comparison.diffs[0].nightly.region")
                            .value("Northumberland"))
                    .andExpect(jsonPath("$.comparison.diffs[1].changed").value(false));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/admin/pipeline-runs/{id} returns 404 for unknown id")
    void detail_returns_404_for_unknown_id() throws Exception {
        when(pipelineRunService.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/admin/pipeline-runs/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "PRO_USER")
    @DisplayName("PRO_USER cannot access pipeline-runs endpoints (ADMIN-only)")
    void pro_user_gets_403() throws Exception {
        mockMvc.perform(get("/api/admin/pipeline-runs"))
                .andExpect(status().isForbidden());
    }
}
