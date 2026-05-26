package com.gregochr.goldenhour.entity;

/**
 * Lifecycle status of a single {@link PipelineRunPhaseEntity}.
 */
public enum PipelinePhaseStatus {
    /** The phase is currently active. */
    RUNNING,
    /** The phase completed successfully. */
    COMPLETED,
    /** The phase threw or hit the safety timeout. */
    FAILED
}
