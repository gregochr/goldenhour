package com.gregochr.goldenhour.model.runs;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The Day view's summary strip. Today fills the "today" group, a past day the "past" group; the other group is null.
 *
 * @param finished       today: runs that have finished (done, partial or failed)
 * @param running        today: runs in progress
 * @param stillToRun     today: scheduler fires still to come before midnight, chained children included
 * @param runs           past: rows on the day
 * @param cycles         past: how many of them are cycles
 * @param jobs           past: how many are not (jobs and sky evals)
 * @param needAttention  rows that are partial or failed
 * @param partial        how many are partial
 * @param failed         how many are failed
 * @param attentionNames today: the names of the rows that need attention
 * @param spendGbp       the spend so far in pounds; runs with an unknown cost add nothing (see spendComplete)
 * @param spendComplete  false when a run on the day has an unknown or still-accruing cost
 * @param projectedGbp   today: {@code spendGbp} plus the estimates of the rows still to run
 * @param avg30Gbp       the average daily spend over the 30 UK days before the day, or null when there is no history
 * @param vsAvgPercent   past: the day's spend against that average, in percent, or null when there is no average
 * @param nextUp         today: the next scheduled row, or null when none is left
 * @param slowestCycle   past: the day's longest cycle, or null when it had none
 */
public record RunDaySummary(
        Integer finished,
        Integer running,
        Integer stillToRun,
        Integer runs,
        Integer cycles,
        Integer jobs,
        int needAttention,
        int partial,
        int failed,
        List<String> attentionNames,
        BigDecimal spendGbp,
        boolean spendComplete,
        BigDecimal projectedGbp,
        BigDecimal avg30Gbp,
        Integer vsAvgPercent,
        NextUp nextUp,
        SlowestCycle slowestCycle) {

    /**
     * The next scheduled row.
     *
     * @param name      its display name
     * @param at        when it starts
     * @param estimated whether that time is an estimate
     */
    public record NextUp(String name, Instant at, boolean estimated) {
    }

    /**
     * The day's longest cycle against its usual.
     *
     * @param name            the cycle's name
     * @param durationSeconds how long it took
     * @param medianSeconds   the median of the type's previous 30 cycles, or null when there is no history
     */
    public record SlowestCycle(String name, long durationSeconds, Long medianSeconds) {
    }
}
