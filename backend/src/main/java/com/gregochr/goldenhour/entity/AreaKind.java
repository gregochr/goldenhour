package com.gregochr.goldenhour.entity;

/**
 * What kind of named area an {@link AreaEntity} is.
 *
 * <p>The column is {@code area.kind} (V191). Adding a value here also needs a line in the local-only
 * {@code LocalH2EnumWidener.TARGETS}, so an existing local H2 file accepts it.
 */
public enum AreaKind {

    /** An ordinary named area ("Hadrian's Wall"): free overlap, partial cover, members in {@code area_location}. */
    NAMED,

    /**
     * A sub-region: a labelled set of locations that partitions its region. Its members live in
     * {@code location_sub_region} (one row per location), and it always has a region.
     */
    SUB_REGION
}
