package com.gregochr.goldenhour.entity;

/**
 * The type of schedule trigger for a dynamically scheduled job.
 */
public enum ScheduleType {

    /** Triggered by a cron expression. */
    CRON,

    /** Triggered on a fixed delay after the previous execution completes. */
    FIXED_DELAY
}
