package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelinePhaseStatus;

import java.time.Instant;

/**
 * One phase row in a pipeline run timeline.
 *
 * @param phase           which phase
 * @param sequenceOrder   1-based position within the run
 * @param status          RUNNING / COMPLETED / FAILED
 * @param startedAt       when this phase started
 * @param completedAt     when this phase finished; null while RUNNING
 * @param durationSeconds wall-clock duration, or null while RUNNING
 * @param detail          last waiting_on text (WAIT phase) or failure reason (FAILED phase)
 */
public record PipelinePhaseSummary(
        PipelinePhase phase,
        int sequenceOrder,
        PipelinePhaseStatus status,
        Instant startedAt,
        Instant completedAt,
        Long durationSeconds,
        String detail) {
}
