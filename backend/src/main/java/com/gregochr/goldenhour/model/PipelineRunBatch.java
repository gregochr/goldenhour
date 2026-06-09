package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;

import java.time.Instant;

/**
 * One forecast_batch row associated with a pipeline run, surfaced in the Pipeline Run
 * detail view. The detail view links FORECAST_BATCH_SUBMIT to these rows so the user
 * sees which batches the cycle produced and what each one resulted in.
 *
 * @param id              forecast_batch id
 * @param jobRunId        the linked job_run id (used to deep-link to the disposition breakdown)
 * @param anthropicBatchId Anthropic-assigned id (for SSH-free traceability)
 * @param status          SUBMITTED / COMPLETED / FAILED / EXPIRED / CANCELLED
 * @param requestCount    total requests in this batch
 * @param succeededCount  number of successful results written (null until processed)
 * @param erroredCount    number of errored results (null until processed)
 * @param submittedAt     submission timestamp
 * @param endedAt         when the batch reached a terminal status; null while SUBMITTED
 * @param retry           {@code true} if this is a RETRY_FAILED-phase batch (re-submitting
 *                        the precursor batches' transient failures); the view flags it
 *                        distinctly and ties it to its precursor(s) via the shared cycle
 */
public record PipelineRunBatch(
        Long id,
        Long jobRunId,
        String anthropicBatchId,
        BatchStatus status,
        int requestCount,
        Integer succeededCount,
        Integer erroredCount,
        Instant submittedAt,
        Instant endedAt,
        boolean retry) {
}
