package com.gregochr.goldenhour.model.runs;

/**
 * The status the Day view serves for a row. A degraded cycle is served as PARTIAL with its note.
 */
public enum RunDeepStatus {

    /** Finished cleanly. */
    DONE,

    /** Finished, but some of the work errored (or the cycle degraded). */
    PARTIAL,

    /** Failed, aborted or never finished. */
    FAILED,

    /** In progress. */
    RUNNING,

    /** Not yet started: a scheduler fire still to come today. */
    SCHEDULED;

    /**
     * Whether the row counts under "Need attention".
     *
     * @return true for PARTIAL and FAILED
     */
    public boolean needsAttention() {
        return this == PARTIAL || this == FAILED;
    }

    /**
     * Maps a Runs-list status onto the Day view's.
     *
     * @param status the list status
     * @return DEGRADED becomes PARTIAL; the rest keep their names
     */
    public static RunDeepStatus from(RunListStatus status) {
        return switch (status) {
            case RUNNING -> RUNNING;
            case DONE -> DONE;
            case DEGRADED, PARTIAL -> PARTIAL;
            case FAILED -> FAILED;
        };
    }
}
