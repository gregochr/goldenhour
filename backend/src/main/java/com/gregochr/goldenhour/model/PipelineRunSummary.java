package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelineRunStatus;

import java.time.Instant;

/**
 * One-row summary of a {@link com.gregochr.goldenhour.entity.PipelineRunEntity} for the
 * Pipeline Run list view (Manage → Operations → Pipeline Runs). Per-phase rows live in
 * {@link PipelinePhaseSummary}, returned by the detail endpoint.
 *
 * @param id              run id (also the cycle id batches are tagged with)
 * @param cycleType       NIGHTLY or INTRADAY — both cycles are live and share this table
 * @param status          RUNNING / COMPLETED / FAILED / DEGRADED — DEGRADED means the cycle
 *                        completed and briefed but one or more forecast batch submissions
 *                        failed, so some served ratings predate this cycle
 * @param currentPhase    which phase is active right now; null after completion
 * @param waitingOn       human progress text while the orchestrator is mid-WAIT; null
 *                        otherwise. The "is it stuck?" answer surface in the live view
 * @param triggerTime     when the cycle started
 * @param completedAt     when the cycle terminated; null while RUNNING
 * @param durationSeconds total wall-clock duration, or null while RUNNING
 * @param failureReason   populated on FAILED runs (carrying the {@code Safety timeout:}
 *                        prefix when the safety backstop fired) and on DEGRADED runs (naming
 *                        which forecast batch submissions failed and how many requests were
 *                        not submitted); null otherwise
 */
public record PipelineRunSummary(
        Long id,
        CycleType cycleType,
        PipelineRunStatus status,
        PipelinePhase currentPhase,
        String waitingOn,
        Instant triggerTime,
        Instant completedAt,
        Long durationSeconds,
        String failureReason) {
}
