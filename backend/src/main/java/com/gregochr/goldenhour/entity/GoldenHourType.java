package com.gregochr.goldenhour.entity;

/**
 * Indicates which solar events are worth photographing at a given location.
 *
 * <p>Used to skip evaluations that are not relevant for a location — for example,
 * a west-facing cliff should only be evaluated at sunset, not sunrise.
 */
public enum GoldenHourType {

    /** Only the morning sunrise is worth shooting here. */
    SUNRISE,

    /** Only the evening sunset is worth shooting here. */
    SUNSET,

    /** Both sunrise and sunset are worth shooting here (default). */
    BOTH_TIMES,

    /** Interesting light at any time — evaluate both sunrise and sunset. */
    ANYTIME
}
