package com.gregochr.goldenhour.model.runs;

/**
 * The four summary figures above the Runs list. They follow the range and the kind chip (but not the
 * attention chip) and never move as more pages load.
 *
 * @param runs                   rows in range and kind
 * @param cycles                 how many of them are cycles
 * @param other                  how many are job runs
 * @param needAttention          rows that are DEGRADED, PARTIAL or FAILED
 * @param degraded               DEGRADED rows
 * @param partial                PARTIAL rows
 * @param failed                 FAILED rows
 * @param spendMicroDollars      spend across those rows, each job run counted once
 * @param spendGbpRate           GBP per USD from the newest spending row, or null
 * @param medianOvernightSeconds median duration of finished overnight cycles in range, or null
 * @param medianOvernightSample  how many overnight cycles the median covers
 */
public record RunListSummary(
        int runs,
        int cycles,
        int other,
        int needAttention,
        int degraded,
        int partial,
        int failed,
        long spendMicroDollars,
        Double spendGbpRate,
        Long medianOvernightSeconds,
        int medianOvernightSample
) {
}
