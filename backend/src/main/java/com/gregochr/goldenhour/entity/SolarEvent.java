package com.gregochr.goldenhour.entity;

/**
 * The solar event a {@link ScheduleType#SOLAR_OFFSET} job is timed against.
 *
 * <p>A separate enum rather than {@link SolarEventType} (which also has {@code ALLDAY}, a location
 * preference with no instant) or {@link TargetType} (which has {@code HOURLY}): the scheduler needs
 * exactly two values, and a persisted enum used elsewhere must not be widened or narrowed for it
 * (the {@code LocalH2EnumWidener} trap). The column holding it is a plain string with a CHECK, so
 * this enum is never mapped by Hibernate.
 */
public enum SolarEvent {

    /** The morning event. */
    SUNRISE,

    /** The evening event. */
    SUNSET
}
