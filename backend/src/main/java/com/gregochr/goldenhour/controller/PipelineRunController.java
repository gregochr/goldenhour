package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunPhaseEntity;
import com.gregochr.goldenhour.model.PipelinePhaseSummary;
import com.gregochr.goldenhour.model.PipelineRunBatch;
import com.gregochr.goldenhour.model.PipelineRunDetail;
import com.gregochr.goldenhour.model.PipelineRunPickComparison;
import com.gregochr.goldenhour.model.PipelineRunSummary;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.pipeline.PipelineRunComparisonService;
import com.gregochr.goldenhour.service.pipeline.PipelineRunService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Read-only API surfacing pipeline run state to the admin observability UX.
 *
 * <p>The two endpoints power the Pipeline Runs sub-tab under Manage → Operations
 * (added in commit 3 of the orchestrator landing). The list endpoint feeds the
 * recent-runs table; the detail endpoint composes the run summary + phase timeline
 * + batch list so the user can drill into the existing disposition breakdown via
 * each batch's {@code jobRunId}.
 *
 * <p>ADMIN-only — pipeline observability is operations work, not a user feature.
 */
@RestController
@RequestMapping("/api/admin/pipeline-runs")
@PreAuthorize("hasRole('ADMIN')")
public class PipelineRunController {

    private final PipelineRunService pipelineRunService;
    private final ForecastBatchRepository forecastBatchRepository;
    private final PipelineRunComparisonService comparisonService;

    /**
     * Constructs the controller.
     *
     * @param pipelineRunService      pipeline run persistence layer
     * @param forecastBatchRepository queried for the cycle's batch list
     * @param comparisonService       builds the intraday-vs-nightly pick comparison
     */
    public PipelineRunController(PipelineRunService pipelineRunService,
            ForecastBatchRepository forecastBatchRepository,
            PipelineRunComparisonService comparisonService) {
        this.pipelineRunService = pipelineRunService;
        this.forecastBatchRepository = forecastBatchRepository;
        this.comparisonService = comparisonService;
    }

    /**
     * Returns the most recent pipeline runs, newest first.
     *
     * @return up to 50 recent run summaries
     */
    @GetMapping
    public List<PipelineRunSummary> recent() {
        return pipelineRunService.findRecent().stream()
                .map(PipelineRunController::toSummary)
                .toList();
    }

    /**
     * Returns full detail (run + phases + batches) for a single pipeline run.
     *
     * @param id pipeline run id
     * @return detail payload, or 404 if no such run exists
     */
    @GetMapping("/{id}")
    public ResponseEntity<PipelineRunDetail> detail(@PathVariable Long id) {
        PipelineRunEntity run = pipelineRunService.findById(id).orElse(null);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }

        List<PipelinePhaseSummary> phases = pipelineRunService.findPhases(id).stream()
                .map(PipelineRunController::toPhase)
                .toList();
        List<PipelineRunBatch> batches = forecastBatchRepository.findByPipelineRunId(id).stream()
                .map(PipelineRunController::toBatch)
                .toList();
        PipelineRunPickComparison comparison =
                comparisonService.compareToSameDayNightly(run);

        return ResponseEntity.ok(
                new PipelineRunDetail(toSummary(run), phases, batches, comparison));
    }

    private static PipelineRunSummary toSummary(PipelineRunEntity run) {
        Long durationSeconds = durationSeconds(run.getTriggerTime(), run.getCompletedAt());
        return new PipelineRunSummary(
                run.getId(),
                run.getCycleType(),
                run.getStatus(),
                run.getCurrentPhase(),
                run.getWaitingOn(),
                run.getTriggerTime(),
                run.getCompletedAt(),
                durationSeconds,
                run.getFailureReason());
    }

    private static PipelinePhaseSummary toPhase(PipelineRunPhaseEntity phase) {
        Long durationSeconds = durationSeconds(phase.getStartedAt(), phase.getCompletedAt());
        return new PipelinePhaseSummary(
                phase.getPhase(),
                phase.getSequenceOrder(),
                phase.getStatus(),
                phase.getStartedAt(),
                phase.getCompletedAt(),
                durationSeconds,
                phase.getDetail());
    }

    private static PipelineRunBatch toBatch(ForecastBatchEntity batch) {
        return new PipelineRunBatch(
                batch.getId(),
                batch.getJobRunId(),
                batch.getAnthropicBatchId(),
                batch.getStatus(),
                batch.getRequestCount(),
                batch.getSucceededCount(),
                batch.getErroredCount(),
                batch.getSubmittedAt(),
                batch.getEndedAt());
    }

    private static Long durationSeconds(Instant start, Instant end) {
        if (start == null || end == null) {
            return null;
        }
        return Duration.between(start, end).getSeconds();
    }
}
