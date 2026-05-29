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
 * <p>For an INTRADAY run, {@code comparison} carries the cross-run diff against
 * the same day's NIGHTLY baseline — the "did Plan A or Plan B change since this
 * morning?" signal. It is {@code null} for nightly runs and for intraday runs
 * with no same-day baseline.
 *
 * @param run        run summary (status, duration, etc.)
 * @param phases     the run's phase rows in execution order
 * @param batches    the forecast_batch rows tagged with this cycle id
 * @param comparison intraday-vs-nightly best-bet comparison, or {@code null}
 */
public record PipelineRunDetail(
        PipelineRunSummary run,
        List<PipelinePhaseSummary> phases,
        List<PipelineRunBatch> batches,
        PipelineRunPickComparison comparison) {
}
