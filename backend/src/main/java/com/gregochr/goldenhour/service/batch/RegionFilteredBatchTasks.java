package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.service.evaluation.EvaluationTask;

import java.util.List;

/**
 * Result of {@link ForecastTaskCollector#collectRegionFilteredBatches(java.util.List)}.
 *
 * <p>Region-filtered admin batches use the {@code BATCH_NEAR_TERM} model for every
 * task (no near/far split), so this result has only inland and coastal buckets.
 *
 * <p>An empty value is returned when the briefing has no candidates after filtering
 * or weather pre-fetch returns nothing; partial-prefetch is tolerated (no ratio
 * threshold for the admin path, mirroring the legacy behaviour).
 *
 * @param inland  inland tasks
 * @param coastal coastal tasks
 */
public record RegionFilteredBatchTasks(
        List<EvaluationTask.Forecast> inland,
        List<EvaluationTask.Forecast> coastal) {

    /**
     * @return the all-empty value.
     */
    public static RegionFilteredBatchTasks empty() {
        return new RegionFilteredBatchTasks(List.of(), List.of());
    }

    /**
     * @return {@code true} if both buckets are empty.
     */
    public boolean isEmpty() {
        return inland.isEmpty() && coastal.isEmpty();
    }
}
