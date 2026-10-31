package com.gregochr.goldenhour.entity;

import java.util.Set;

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
    LOW;

    /**
     * Whether a wanted set names every tide state, so the location suits any tide.
     *
     * <p>The three states are matched by narrow bands around a high, a low and the midpoint,
     * which leave gaps between them. A location that wants all three has no tide it would
     * rather avoid, so it must not read as "wrong water" in a gap.
     *
     * @param wanted the location's wanted tide states, possibly null
     * @return true when {@code wanted} contains every {@link TideType}; false for null or empty
     */
    public static boolean coversEveryState(Set<TideType> wanted) {
        return wanted != null && wanted.containsAll(Set.of(values()));
    }
}
