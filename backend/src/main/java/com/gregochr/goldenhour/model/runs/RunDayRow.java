package com.gregochr.goldenhour.model.runs;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the Day view: a cycle, a top-level job run, a sky-rating eval, or a scheduler fire still to come.
 *
 * @param id               cycle-N, job-N, eval-N, or sched-JOBKEY-EPOCH (with a child suffix) for a scheduled row
 * @param kind             what backs the row
 * @param type             the display type
 * @param typeLabel        the type's label
 * @param name             the display name
 * @param subline          the one-line context (a cycle's trigger, "Runs after Afternoon refresh"), or null
 * @param startedAt        when it started (or is expected to)
 * @param endedAt          when it ended, or null while running or still to come
 * @param estimated        true when {@code startedAt} is an estimate (a chained child of a scheduled cycle)
 * @param durationSeconds  elapsed seconds; for a scheduled row the median of the type's last 30 runs; null when unknown
 *                         or when the run has no meaningful duration
 * @param costMicroDollars spend in micro-dollars (for a scheduled row, the median); null when unknown (B7)
 * @param costComplete     false when the cost is unknown or still accruing ("so far")
 * @param costGbp          the cost in pounds to the penny, converted with the run's own rate; null when unknown
 * @param costUsd          the cost in dollars to the cent; null when unknown
 * @param status           the served status
 * @param note             the one-line note, or null
 * @param noteTone         the note's tone, null when there is no note
 * @param slow             true when the duration was above the 90th percentile of the type's previous 30 runs
 * @param parentCycleId    for a briefing or ask-ready run that belongs to a cycle, that cycle's id
 * @param cycleId          the pipeline run id, for a cycle
 * @param jobRunId         the job run id, for a job
 * @param facts            the expanded row's inline facts
 */
public record RunDayRow(
        String id,
        RunDeepKind kind,
        RunDeepType type,
        String typeLabel,
        String name,
        String subline,
        Instant startedAt,
        Instant endedAt,
        boolean estimated,
        Long durationSeconds,
        Long costMicroDollars,
        boolean costComplete,
        BigDecimal costGbp,
        BigDecimal costUsd,
        RunDeepStatus status,
        String note,
        NoteTone noteTone,
        boolean slow,
        Long parentCycleId,
        Long cycleId,
        Long jobRunId,
        RunFacts facts) {
}
