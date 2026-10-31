package com.gregochr.goldenhour.model.runs;

/**
 * The status the server serves for a Runs-list row. The client never works one out.
 */
public enum RunListStatus {

    /** Still in progress (and, for a job run, not past its stale ceiling). */
    RUNNING,

    /** Finished cleanly. */
    DONE,

    /** A cycle that finished with a degraded outcome. */
    DEGRADED,

    /** A job run where at least 80% of its requests succeeded but some errored. */
    PARTIAL,

    /** Failed, aborted, or never finished. */
    FAILED;

    /**
     * Whether this status belongs under the "Need attention" chip.
     *
     * @return true for DEGRADED, PARTIAL and FAILED
     */
    public boolean needsAttention() {
        return this == DEGRADED || this == PARTIAL || this == FAILED;
    }
}
