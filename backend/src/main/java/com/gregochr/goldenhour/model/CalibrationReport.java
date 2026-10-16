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
 * @param outcomesInRange recorded outcomes and readers' ratings in the window — every row in both
 *                        tables, "didn't see it" ratings included
 * @param scoredPairs     forecast/outcome pairs actually scored, after per-horizon dedup (one per
 *                        reader's rating, so this counts pairs, not slots)
 * @param overall         metrics across every pair
 * @param byDaysAhead     metrics per forecast horizon, ordered T+0 upward
 * @param byModel         metrics per evaluation model
 * @param bucketsByBuild  metrics per release ({@code forecast_evaluation.app_build}, the build that
 *                        scored the forecast); rows written before it was recorded read
 *                        {@code UNKNOWN}
 * @param bucketsByPromptHash metrics per system-prompt fingerprint
 *                        ({@code forecast_evaluation.prompt_hash}); rows with none recorded read
 *                        {@code UNKNOWN}
 */
public record CalibrationReport(
        LocalDate from,
        LocalDate to,
        int outcomesInRange,
        int scoredPairs,
        CalibrationBucket overall,
        List<CalibrationBucket> byDaysAhead,
        List<CalibrationBucket> byModel,
        List<CalibrationBucket> bucketsByBuild,
        List<CalibrationBucket> bucketsByPromptHash) {

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
     * @param bucketsByBuild  per-release metrics
     * @param bucketsByPromptHash per-prompt metrics
     */
    public CalibrationReport {
        byDaysAhead = byDaysAhead == null ? List.of() : List.copyOf(byDaysAhead);
        byModel = byModel == null ? List.of() : List.copyOf(byModel);
        bucketsByBuild = bucketsByBuild == null ? List.of() : List.copyOf(bucketsByBuild);
        bucketsByPromptHash = bucketsByPromptHash == null ? List.of() : List.copyOf(bucketsByPromptHash);
    }
}
