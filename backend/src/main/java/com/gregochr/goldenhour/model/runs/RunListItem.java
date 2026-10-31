package com.gregochr.goldenhour.model.runs;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.BatchSummary;

import java.time.Instant;

/**
 * One row of the merged Runs list: a pipeline run (cycle) or a top-level job run.
 *
 * <p>Result and scope fields that do not apply to the row's {@link #kind} are null.
 *
 * @param kind               CYCLE or JOB
 * @param id                 the pipeline run id or the job run id (a cycle and a job can share a number)
 * @param group              the served group
 * @param name               the served display name
 * @param startedAt          when the run started
 * @param endedAt            when it ended, or null while running
 * @param durationSeconds    elapsed seconds, null while running and always null for Ask runs
 * @param status             the served status
 * @param note               the one-line note, or null
 * @param noteTone           the note's tone, null when there is no note
 * @param costMicroDollars   spend in micro-dollars (a cycle's is its batches' spend only)
 * @param costGbpRate        GBP per USD at the run, or null when unknown
 * @param batchesDone        cycles: batches in a terminal state
 * @param batchesTotal       cycles: batches submitted, retries included
 * @param succeeded          job runs: succeeded count
 * @param failed             job runs: failed count
 * @param locationsProcessed job runs: locations processed
 * @param cycleType          cycles: NIGHTLY, INTRADAY or EVENING
 * @param batchCount         cycles: batch count (same as {@code batchesTotal})
 * @param runType            job runs: the run type
 * @param triggeredManually  job runs: whether an admin started it
 * @param batchSummary       job runs: the batch scope (scheduled batches on the returned page only)
 */
public record RunListItem(
        RunKind kind,
        Long id,
        RunGroup group,
        String name,
        Instant startedAt,
        Instant endedAt,
        Long durationSeconds,
        RunListStatus status,
        String note,
        NoteTone noteTone,
        Long costMicroDollars,
        Double costGbpRate,
        Integer batchesDone,
        Integer batchesTotal,
        Integer succeeded,
        Integer failed,
        Integer locationsProcessed,
        CycleType cycleType,
        Integer batchCount,
        RunType runType,
        Boolean triggeredManually,
        BatchSummary batchSummary
) {

    /**
     * Copies this item with the batch scope attached.
     *
     * @param summary the batch scope, or null
     * @return the copy
     */
    public RunListItem withBatchSummary(BatchSummary summary) {
        return new RunListItem(kind, id, group, name, startedAt, endedAt, durationSeconds, status, note,
                noteTone, costMicroDollars, costGbpRate, batchesDone, batchesTotal, succeeded, failed,
                locationsProcessed, cycleType, batchCount, runType, triggeredManually, summary);
    }
}
