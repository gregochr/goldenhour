package com.gregochr.goldenhour.entity;

/**
 * Status of a dynamically scheduled job.
 */
public enum SchedulerJobStatus {

    /** Job is actively scheduled and will fire on its configured schedule. */
    ACTIVE,

    /** Job has been manually paused by an admin — can be resumed. */
    PAUSED,

    /** Job is disabled because a prerequisite config flag is off (e.g. aurora.enabled=false). */
    DISABLED_BY_CONFIG
}
