package com.gregochr.goldenhour.model;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * @param mistProne       metrics over the pairs whose forecast carried Claude's LIKELY valley-mist
 *                        band (valley-mist-plan M3), or {@code null} when the window holds none.
 *                        <b>Its {@code missedOpportunities} is the number the whole valley-mist plan
 *                        exists to drive to zero</b>: the forecast said stay home (2 stars or fewer)
 *                        on a morning the reader rated 4 or 5. Pairs are the same pairs as
 *                        {@code overall}, so a mist-prone pair is counted in both
 * @param mistProneSeenCounts what those pairs' readers said they saw: {@code seen} tag name to count,
 *                        with {@code UNTAGGED} for a rating given with no tag; the counts sum
 *                        to {@code mistProne.sampleCount()}.
 *                        Empty when {@code mistProne} is {@code null}. {@code NOT_SEEN} never
 *                        appears: a "didn't see it" rating is not a pair
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
        List<CalibrationBucket> bucketsByPromptHash,
        CalibrationBucket mistProne,
        Map<String, Integer> mistProneSeenCounts) {

    /**
     * Builds a report with no mist-prone bucket - the shape every report had before valley mist.
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
    public CalibrationReport(LocalDate from, LocalDate to, int outcomesInRange, int scoredPairs,
            CalibrationBucket overall, List<CalibrationBucket> byDaysAhead,
            List<CalibrationBucket> byModel, List<CalibrationBucket> bucketsByBuild,
            List<CalibrationBucket> bucketsByPromptHash) {
        this(from, to, outcomesInRange, scoredPairs, overall, byDaysAhead, byModel, bucketsByBuild,
                bucketsByPromptHash, null, Map.of());
    }

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
     * @param mistProne       the mist-prone bucket, or null
     * @param mistProneSeenCounts the mist-prone pairs' tags
     */
    public CalibrationReport {
        byDaysAhead = byDaysAhead == null ? List.of() : List.copyOf(byDaysAhead);
        byModel = byModel == null ? List.of() : List.copyOf(byModel);
        bucketsByBuild = bucketsByBuild == null ? List.of() : List.copyOf(bucketsByBuild);
        bucketsByPromptHash = bucketsByPromptHash == null ? List.of() : List.copyOf(bucketsByPromptHash);
        // Order is part of the report (enum order, then UNTAGGED): Map.copyOf would randomise it per JVM.
        mistProneSeenCounts = mistProneSeenCounts == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(mistProneSeenCounts));
    }
}
