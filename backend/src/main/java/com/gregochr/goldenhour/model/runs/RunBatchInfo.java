package com.gregochr.goldenhour.model.runs;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;

/**
 * The Anthropic batch behind a job run, as the job-run panel shows it.
 *
 * @param anthropicBatchId the Anthropic batch id
 * @param batchType        FORECAST or AURORA
 * @param status           the batch status
 * @param pipelineRunId    the cycle that submitted the batch, or null for a hand-started one
 */
public record RunBatchInfo(
        String anthropicBatchId,
        ForecastBatchEntity.BatchType batchType,
        ForecastBatchEntity.BatchStatus status,
        Long pipelineRunId
) {

    /**
     * Builds the info from a batch row.
     *
     * @param batch the batch row
     * @return the info
     */
    public static RunBatchInfo from(ForecastBatchEntity batch) {
        return new RunBatchInfo(batch.getAnthropicBatchId(), batch.getBatchType(), batch.getStatus(),
                batch.getPipelineRunId());
    }
}
