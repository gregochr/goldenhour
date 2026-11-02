package com.gregochr.goldenhour.entity;

/**
 * How one fire of a scheduled job ended, as stored in {@code scheduler_fire_log.status}.
 *
 * <p>"Missed" is deliberately not a value: it is a fire that left no row, so it is derived when served.
 */
public enum SchedulerFireStatus {

    /** The fire has started and not yet finished (or the process died mid-fire). */
    RUNNING,

    /** The job ran and did its work (also the default when a job reports nothing). */
    SUCCEEDED,

    /** The job ran but some of its work failed or was left undone. */
    PARTIAL,

    /** The job threw, or reported failure. */
    FAILED,

    /** The job deliberately did nothing (a guard, a switched-off flag, nothing to do). */
    SKIPPED
}
