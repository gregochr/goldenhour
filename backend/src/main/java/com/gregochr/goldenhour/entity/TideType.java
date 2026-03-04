package com.gregochr.goldenhour.entity;

/**
 * Indicates the photographer's tide preference for a coastal location.
 *
 * <p>A location's tide preferences are stored as a {@code Set<TideType>}.
 * An empty set means the location is inland and tide data is not fetched.
 * Multiple values indicate the photographer shoots at more than one tide state.
 */
public enum TideType {

    /** Photographer prefers to shoot at high tide. */
    HIGH,

    /** Photographer prefers to shoot at mid tide (between high and low). */
    MID,

    /** Photographer prefers to shoot at low tide. */
    LOW
}
