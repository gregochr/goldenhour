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
 * @param weatherPrefetch the weather pre-fetch outcome for this pass; {@link WeatherPrefetchReport#none()}
 *                        when never reached
 */
public record RegionFilteredBatchTasks(
        List<EvaluationTask.Forecast> inland,
        List<EvaluationTask.Forecast> coastal,
        WeatherPrefetchReport weatherPrefetch) {

    /**
     * Canonical constructor; a missing report becomes {@link WeatherPrefetchReport#none()}.
     */
    public RegionFilteredBatchTasks {
        weatherPrefetch = weatherPrefetch == null ? WeatherPrefetchReport.none() : weatherPrefetch;
    }

    /**
     * Constructs tasks with no weather pre-fetch report.
     *
     * @param inland  inland tasks
     * @param coastal coastal tasks
     */
    public RegionFilteredBatchTasks(List<EvaluationTask.Forecast> inland,
            List<EvaluationTask.Forecast> coastal) {
        this(inland, coastal, WeatherPrefetchReport.none());
    }

    /**
     * @return the all-empty value.
     */
    public static RegionFilteredBatchTasks empty() {
        return new RegionFilteredBatchTasks(List.of(), List.of());
    }

    /**
     * @param report the pre-fetch report that explains the abort
     * @return the all-empty value carrying the weather pre-fetch report
     */
    public static RegionFilteredBatchTasks aborted(WeatherPrefetchReport report) {
        return new RegionFilteredBatchTasks(List.of(), List.of(), report);
    }

    /**
     * @return {@code true} if both buckets are empty.
     */
    public boolean isEmpty() {
        return inland.isEmpty() && coastal.isEmpty();
    }
}
