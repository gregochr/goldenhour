package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.runs.NoteTone;
import com.gregochr.goldenhour.model.runs.RunDeepKind;
import com.gregochr.goldenhour.model.runs.RunDeepStatus;
import com.gregochr.goldenhour.model.runs.RunDeepType;

import java.time.Instant;

/**
 * One finished or running row as the shared loader builds it, before the Day view adds its statistics and the
 * Calendar view sums it. Cycles have their batch runs folded in; an unknown cost is {@code null}, never zero.
 *
 * @param id              {@code cycle-N}, {@code job-N} or {@code eval-N}
 * @param kind            what backs the row
 * @param type            the display type
 * @param key             the population its statistics come from
 * @param runType         the stored run type, for a job; null otherwise
 * @param name            the display name
 * @param subline         the one-line context, or null
 * @param startedAt       when it started
 * @param endedAt         when it ended, or null while running
 * @param durationSeconds elapsed seconds, null while running or when the run has no meaningful duration
 * @param costMicro       the cost in micro-dollars, or null when unknown
 * @param costComplete    false when the cost is unknown or still accruing
 * @param costGbp         the cost in pounds, converted run by run with each run's own rate (zero when unknown)
 * @param status          the served status
 * @param note            the one-line note, or null
 * @param tone            the note's tone
 * @param parentCycleId   the cycle a briefing or ask-ready run belongs to, or null
 * @param cycleId         the pipeline run id, for a cycle
 * @param jobRunId        the job run id, for a job
 * @param batches         cycles: Anthropic batches submitted
 * @param requests        cycles: requests across them
 * @param answersReadyAt  cycles: when the linked ask-ready run ended, or null
 * @param locations       jobs: locations the run processed, or null (cycles and sky evals have none)
 * @param manual          true when an admin started the run; always false for cycles, which record no such flag
 */
record DeepRun(
        String id,
        RunDeepKind kind,
        RunDeepType type,
        StatKey key,
        RunType runType,
        String name,
        String subline,
        Instant startedAt,
        Instant endedAt,
        Long durationSeconds,
        Long costMicro,
        boolean costComplete,
        double costGbp,
        RunDeepStatus status,
        String note,
        NoteTone tone,
        Long parentCycleId,
        Long cycleId,
        Long jobRunId,
        Integer batches,
        Integer requests,
        Instant answersReadyAt,
        Integer locations,
        boolean manual) {

    /**
     * Whether the row finished (it is not running).
     *
     * @return true when the status is DONE, PARTIAL or FAILED
     */
    boolean finished() {
        return status == RunDeepStatus.DONE || status == RunDeepStatus.PARTIAL || status == RunDeepStatus.FAILED;
    }
}
