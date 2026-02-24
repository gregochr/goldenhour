package com.gregochr.goldenhour.entity;

/**
 * The tide state at a coastal location at a specific point in time.
 *
 * <p>Describes where the tide is (photographic opportunity), not which direction it is moving.
 */
public enum TideState {

    /** Tide is at or near its highest point. */
    HIGH,

    /** Tide is at or near its lowest point. */
    LOW,

    /** Tide is between high and low — neither fully in nor fully out. */
    MID
}
