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
    FAILED,
    /**
     * The cycle finished and briefed, but one or more forecast batch submissions failed, so
     * some or all of the ratings the briefing served are from an earlier run rather than this
     * one.
     *
     * <p>Distinct from {@link #FAILED}: a FAILED run did not complete its sequence at all (the
     * safety timeout fired, or an exception broke WAIT/BRIEFING outright). A DEGRADED run
     * completed every phase — WAIT and BRIEFING both ran to completion, briefing from whatever
     * cache existed, which is the correct fallback — but the {@code FORECAST_BATCH_SUBMIT} phase
     * itself is recorded FAILED, so the run's own {@code failureReason} names which submissions
     * were lost and how many requests never reached Claude. {@link #FAILED} outranks DEGRADED: a
     * run that also fails for an unrelated reason (the safety timeout, a briefing exception)
     * stays FAILED.
     *
     * <p>Born from the 2026-09-29 incident (pipeline run 249): every forecast batch submission
     * failed, but the run completed and was recorded {@code COMPLETED} — indistinguishable from a
     * night where every rating was fresh.
     */
    DEGRADED
}
