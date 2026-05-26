package com.gregochr.goldenhour.entity;

/**
 * Lifecycle status of a {@link PipelineRunEntity}.
 */
public enum PipelineRunStatus {
    /** The orchestrator is actively running this cycle. */
    RUNNING,
    /** All phases completed successfully. */
    COMPLETED,
    /** A phase failed or the safety timeout was reached. */
    FAILED
}
