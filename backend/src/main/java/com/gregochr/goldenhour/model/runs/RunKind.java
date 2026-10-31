package com.gregochr.goldenhour.model.runs;

/**
 * Whether a Runs-list row is a pipeline run (cycle) or a job run.
 *
 * <p>Declaration order is the tie-break rank when two rows share a start instant.
 */
public enum RunKind {

    /** A pipeline run. */
    CYCLE,

    /** A top-level job run. */
    JOB
}
