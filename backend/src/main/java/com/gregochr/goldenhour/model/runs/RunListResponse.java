package com.gregochr.goldenhour.model.runs;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The merged Runs list: one page of rows plus the range-scoped aggregates.
 *
 * @param items           this page, newest first
 * @param nextCursor      the opaque cursor for the next page, or null at the end
 * @param total           rows matching range, kind and attention, independent of the cursor
 * @param summary         the summary figures
 * @param kindCounts      rows per group (and {@code ALL}) in range, ignoring kind and attention
 * @param attentionCount  rows needing attention in range, ignoring kind
 * @param runningCount    cycles running plus job runs running, ignoring every filter and stale runs
 * @param liveCycleId     the newest running cycle, or null
 * @param nextOvernightAt the next fire of the nightly cycle job, or null when the scheduler has none
 * @param truncated       true when the job runs in range exceeded the scan cap
 */
public record RunListResponse(
        List<RunListItem> items,
        String nextCursor,
        int total,
        RunListSummary summary,
        Map<String, Integer> kindCounts,
        int attentionCount,
        int runningCount,
        Long liveCycleId,
        Instant nextOvernightAt,
        boolean truncated
) {
}
