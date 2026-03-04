package com.gregochr.goldenhour.entity;

/**
 * Indicates which solar events are worth photographing at a given location.
 *
 * <p>Stored as a multi-select set in the {@code location_solar_event_type} join table.
 * A location may have multiple values — e.g. both {@code SUNRISE} and {@code SUNSET}.
 */
public enum SolarEventType {

    /** Only the morning sunrise is worth shooting here. */
    SUNRISE,

    /** Only the evening sunset is worth shooting here. */
    SUNSET,

    /** Interesting light at any time of day. */
    ALLDAY
}
