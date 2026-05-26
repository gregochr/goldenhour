package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Full detail of one pipeline run for the Pipeline Run detail view.
 *
 * <p>The detail composes: the run summary (status, current phase, waiting_on, duration),
 * the phase timeline (FORECAST_BATCH_SUBMIT → FORECAST_BATCH_WAIT → BRIEFING with per-phase
 * timings), and the cycle's forecast_batch rows so the user can drill into the existing
 * disposition breakdown (linked via each batch's {@code jobRunId}).
 *
 * @param run     run summary (status, duration, etc.)
 * @param phases  the run's phase rows in execution order
 * @param batches the forecast_batch rows tagged with this cycle id
 */
public record PipelineRunDetail(
        PipelineRunSummary run,
        List<PipelinePhaseSummary> phases,
        List<PipelineRunBatch> batches) {
}
