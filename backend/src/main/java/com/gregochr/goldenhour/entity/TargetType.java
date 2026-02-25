package com.gregochr.goldenhour.entity;

/**
 * The solar event or time slot a forecast evaluation targets.
 */
public enum TargetType {

    /** The morning sunrise. */
    SUNRISE,

    /** The evening sunset. */
    SUNSET,

    /** An intraday hourly slot (used for WILDLIFE comfort forecasts between sunrise and sunset). */
    HOURLY
}
