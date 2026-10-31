package com.gregochr.goldenhour.model.runs;

/**
 * Whether a Runs-list row is a pipeline run (cycle) or a job run.
 *
 * <p>When two rows share a start instant the list puts {@link #JOB} before {@link #CYCLE}
 * (kind descending, as {@code RunCursor.ORDER} sorts).
 */
public enum RunKind {

    /** A pipeline run. */
    CYCLE,

    /** A top-level job run. */
    JOB
}
