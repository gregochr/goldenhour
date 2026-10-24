package com.gregochr.goldenhour.entity;

/**
 * The kind of wave-breaking structure a location is, for the big-seas feature: the sort of place
 * where a large sea breaks over a pier, breakwater or lighthouse. Stored as a string in
 * {@code locations.coastal_structure} (V188); null means the location is not a big-seas location.
 */
public enum CoastalStructure {

    /** A pier. */
    PIER,

    /** A breakwater or harbour arm. */
    BREAKWATER,

    /** A lighthouse the sea reaches. */
    LIGHTHOUSE
}
