package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.service.evaluation.EvaluationTask;

import java.util.List;

/**
 * Result of {@link ForecastTaskCollector#collectScheduledBatches()} — the four
 * mutually-exclusive buckets that the scheduled forecast batch submits as
 * separate Anthropic Batch API requests.
 *
 * <p>Bucket dimensions:
 * <ul>
 *   <li><b>near</b> vs <b>far</b> — {@code daysAhead <= NEAR_TERM_MAX_DAYS} routes
 *       to near; the rest to far. Near-term and far-term batches use independent
 *       model selections ({@code BATCH_NEAR_TERM} vs {@code BATCH_FAR_TERM}).</li>
 *   <li><b>inland</b> vs <b>coastal</b> — coastal locations have non-null tide
 *       data on the {@link com.gregochr.goldenhour.model.AtmosphericData} record
 *       and use a different prompt builder.</li>
 * </ul>
 *
 * <p>An "empty" result (every bucket empty) is returned when the prefetch gate
 * trips, the briefing has no candidates, or every candidate is filtered out by
 * triage / stability. The caller's contract is to iterate non-empty buckets and
 * submit each separately; an all-empty value is a no-op.
 *
 * @param nearInland  near-term inland tasks
 * @param nearCoastal near-term coastal tasks
 * @param farInland   far-term inland tasks
 * @param farCoastal  far-term coastal tasks
 */
public record ScheduledBatchTasks(
        List<EvaluationTask.Forecast> nearInland,
        List<EvaluationTask.Forecast> nearCoastal,
        List<EvaluationTask.Forecast> farInland,
        List<EvaluationTask.Forecast> farCoastal) {

    /**
     * @return the all-empty value (used when collection aborts or yields nothing).
     */
    public static ScheduledBatchTasks empty() {
        return new ScheduledBatchTasks(List.of(), List.of(), List.of(), List.of());
    }

    /**
     * @return {@code true} if every bucket is empty.
     */
    public boolean isEmpty() {
        return nearInland.isEmpty() && nearCoastal.isEmpty()
                && farInland.isEmpty() && farCoastal.isEmpty();
    }

    /**
     * @return total task count across all four buckets.
     */
    public int totalSize() {
        return nearInland.size() + nearCoastal.size()
                + farInland.size() + farCoastal.size();
    }
}
