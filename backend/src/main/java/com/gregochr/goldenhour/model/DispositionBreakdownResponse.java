package com.gregochr.goldenhour.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Response body for {@code GET /api/metrics/disposition-breakdown?jobRunId=X}.
 *
 * <p>Backs the "Disposition Breakdown" section of the Job Run detail UI: the
 * summary line reconciles {@code totalCount} = sum of {@code countsByDisposition}
 * values, and the drill-down list lets the user click any category to see the
 * per-location detail.
 *
 * <p>Returned with empty counts and entries when the job run has no
 * disposition rows — that happens for the cycle's 2nd/3rd/4th bucket job runs
 * (dispositions live only on the first job run created in a cycle) or for
 * non-batch job runs entirely.
 *
 * @param jobRunId             the queried job run id
 * @param totalCount           total number of disposition rows for this run
 * @param countsByDisposition  per-disposition counts; keys are the stored
 *                             VARCHAR values (e.g. {@code "EVALUATED"})
 * @param entries              every disposition row, ordered for stable UI
 *                             rendering — sorted by disposition then location name
 */
public record DispositionBreakdownResponse(
        Long jobRunId,
        long totalCount,
        Map<String, Long> countsByDisposition,
        List<DispositionEntry> entries) {

    /**
     * Single disposition row exposed to the UI. Mirrors the persisted entity
     * fields but with strongly-typed dates and a String disposition (matching
     * the forward-compatible storage approach — unknown future categories
     * pass through unchanged).
     *
     * @param locationId      resolved location id, or null for past-date /
     *                        cached / unknown-location skips
     * @param locationName    location name (always populated)
     * @param evaluationDate  briefing slot date
     * @param eventType       SUNRISE or SUNSET
     * @param daysAhead       days from today (Europe/London) to evaluationDate;
     *                        negative for past-date skips
     * @param disposition     one of {@link com.gregochr.goldenhour.entity.DispositionCategory}'s
     *                        name() values, or an unknown forward-compat value
     * @param detail          human-readable reason for skip dispositions; null
     *                        for EVALUATED
     */
    public record DispositionEntry(
            Long locationId,
            String locationName,
            LocalDate evaluationDate,
            String eventType,
            Integer daysAhead,
            String disposition,
            String detail) {
    }
}
