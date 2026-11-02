package com.gregochr.goldenhour.entity;

/**
 * What caused one fire of a scheduled job, as stored in {@code scheduler_fire_log.trigger}.
 */
public enum SchedulerFireTrigger {

    /** The job's own schedule (cron, fixed delay or solar trigger). */
    SCHEDULED,

    /** An admin's "Run now". */
    MANUAL,

    /** The startup catch-up of a solar job that missed today's fire while the application was down. */
    CATCH_UP
}
