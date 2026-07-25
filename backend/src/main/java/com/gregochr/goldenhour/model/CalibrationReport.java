package com.gregochr.goldenhour.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Forecast accuracy measured against recorded outcomes over a date window.
 *
 * <p>The gate for prompt and sampling-geometry changes. The prompt-regression tests, the sky-rating
 * eval harness and the model-comparison harness are all self-referential — they compare Claude
 * against fixtures, hand-written expectations, or other models. This report is the only thing that
 * compares a forecast against what a photographer actually saw, so it is what a change to the
 * scoring rules has to be justified against.
 *
 * <p>Run it over a fixed window before and after a change and diff the buckets. Because the window
 * is caller-supplied and the aggregation is deterministic, two runs over the same window are
 * directly comparable.
 *
 * @param from            start of the scored window (inclusive)
 * @param to              end of the scored window (inclusive)
 * @param outcomesInRange recorded outcomes in the window that carried a rating
 * @param scoredPairs     forecast/outcome pairs actually scored, after per-horizon dedup
 * @param overall         metrics across every pair
 * @param byDaysAhead     metrics per forecast horizon, ordered T+0 upward
 * @param byModel         metrics per evaluation model
 */
public record CalibrationReport(
        LocalDate from,
        LocalDate to,
        int outcomesInRange,
        int scoredPairs,
        CalibrationBucket overall,
        List<CalibrationBucket> byDaysAhead,
        List<CalibrationBucket> byModel) {

    /**
     * Compact constructor — defensive copies to satisfy SpotBugs EI_EXPOSE_REP.
     *
     * @param from            start of the window
     * @param to              end of the window
     * @param outcomesInRange recorded outcomes in the window
     * @param scoredPairs     pairs scored
     * @param overall         overall metrics
     * @param byDaysAhead     per-horizon metrics
     * @param byModel         per-model metrics
     */
    public CalibrationReport {
        byDaysAhead = byDaysAhead == null ? List.of() : List.copyOf(byDaysAhead);
        byModel = byModel == null ? List.of() : List.copyOf(byModel);
    }
}
