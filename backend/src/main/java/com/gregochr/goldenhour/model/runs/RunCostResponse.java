package com.gregochr.goldenhour.model.runs;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The Runs "Cost trends" view: Claude spend for the last N full UK days against the N before them, by day and by
 * run type, with the verdict served as structured fields (the client joins them into a sentence).
 *
 * <p>Today is excluded because it is incomplete. A cost that is not known (a batch still open) is never counted as
 * zero: the day, type or total that contains one says {@code costComplete: false}.
 *
 * @param rangeDays N, the length of each of the two periods (in all time, the days served)
 * @param firstDay  the first day of the prior period (in all time, the first day of the history)
 * @param lastDay   the last day of the last period (yesterday)
 * @param allTime   true when the whole Runs history was asked for: one period, no prior
 * @param days      one entry per UK day of the two periods, ascending
 * @param types     one entry per run type that ran, biggest rise in pounds first
 * @param totals    the two periods summed
 * @param verdict   the answer to "have my costs snowballed?"
 */
public record RunCostResponse(
        int rangeDays,
        LocalDate firstDay,
        LocalDate lastDay,
        boolean allTime,
        List<CostDay> days,
        List<CostType> types,
        CostTotals totals,
        CostVerdict verdict) {

    /**
     * One UK day's spend.
     *
     * @param date         the day
     * @param byType       pounds per display type name (every type is present, zero when it spent nothing)
     * @param totalGbp     the day's total in pounds
     * @param costComplete false when a run that started that day has a cost that is not known yet
     */
    public record CostDay(LocalDate date, Map<String, BigDecimal> byType, BigDecimal totalGbp,
            boolean costComplete) {
    }

    /**
     * One type's spend in the two periods and what moved it.
     *
     * @param type                the display type
     * @param label               the label to show
     * @param lastGbp             pounds in the last period
     * @param priorGbp            pounds in the prior period
     * @param deltaGbp            last minus prior
     * @param deltaPercent        the change as a whole percent, null when the prior period spent nothing
     * @param lastRuns            runs in the last period
     * @param priorRuns           runs in the prior period
     * @param runsChangePercent   the change in run count, null when there were no prior runs
     * @param perRunChangePercent the change in cost per run, null when either period has no cost per run
     * @param avgLocationsLast    Ask ready answers only: average locations per run in the last period, else null
     * @param avgLocationsPrior   Ask ready answers only: average locations per run in the prior period, else null
     * @param costComplete        false when a run of this type has a cost that is not known yet
     */
    public record CostType(RunDeepType type, String label, BigDecimal lastGbp, BigDecimal priorGbp,
            BigDecimal deltaGbp, Integer deltaPercent, int lastRuns, int priorRuns, Integer runsChangePercent,
            Integer perRunChangePercent, Integer avgLocationsLast, Integer avgLocationsPrior,
            boolean costComplete) {
    }

    /**
     * The two periods summed.
     *
     * @param lastGbp      pounds in the last period
     * @param priorGbp     pounds in the prior period
     * @param deltaGbp     last minus prior
     * @param deltaPercent whole percent change, null when the prior period spent nothing
     * @param lastUsd      the last period in dollars (the runs' own recorded dollar costs)
     * @param fxRate       the pounds-per-dollar rate stored for yesterday (never fetched in the request)
     * @param perDayLast   pounds a day in the last period
     * @param perDayPrior  pounds a day in the prior period
     * @param costComplete false when any run in the two periods has a cost that is not known yet
     */
    public record CostTotals(BigDecimal lastGbp, BigDecimal priorGbp, BigDecimal deltaGbp, Integer deltaPercent,
            BigDecimal lastUsd, BigDecimal fxRate, BigDecimal perDayLast, BigDecimal perDayPrior,
            boolean costComplete) {
    }

    /** Which way the headline points. */
    public enum Direction {
        /** Spend is up by 10% or more. */
        UP,
        /** Spend is down by 10% or more. */
        DOWN,
        /** Spend moved by less than 10%, or there is nothing to compare with. */
        FLAT
    }

    /**
     * The Ask ready answers locations comparison.
     *
     * @param last  average locations per run in the last period
     * @param prior average locations per run in the prior period
     */
    public record LocationsPair(int last, int prior) {
    }

    /**
     * The sky eval share sentence's parts, served only when it is more than 4% of the last period's spend.
     *
     * @param sharePercent  its share of the last period's spend, whole percent
     * @param gbp           its pounds in the last period
     * @param changePercent its change on the prior period, null when it spent nothing then
     */
    public record SkyEvalShare(int sharePercent, BigDecimal gbp, Integer changePercent) {
    }

    /**
     * The verdict as structured fields.
     *
     * @param direction      UP, DOWN or FLAT
     * @param percent        the total change as a whole percent (signed), null when the prior period spent nothing
     * @param topType        the type with the biggest change in pounds, or null when nothing ran
     * @param topLabel       its label
     * @param topDeltaGbp    its change in pounds
     * @param totalDeltaGbp  the total change in pounds
     * @param runsAsOften    true when the top type's run count moved by less than 10%
     * @param perRunMultiple the top type's cost per run now as a multiple of before (one decimal), or null
     * @param askLocations   the locations pair, only when the top type is Ask ready answers
     * @param skyEval        the sky eval share parts, or null when it is 4% or less of spend
     */
    public record CostVerdict(Direction direction, Integer percent, RunDeepType topType, String topLabel,
            BigDecimal topDeltaGbp, BigDecimal totalDeltaGbp, boolean runsAsOften, BigDecimal perRunMultiple,
            LocationsPair askLocations, SkyEvalShare skyEval) {
    }
}
