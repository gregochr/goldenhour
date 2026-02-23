package com.gregochr.goldenhour.entity;

/**
 * Photography type tags for a location.
 *
 * <p>A location can have multiple types simultaneously (e.g. a coastal cliff that is
 * both {@code SEASCAPE} and {@code LANDSCAPE}). Stored as a join table via
 * {@code @ElementCollection} on {@link LocationEntity}.
 */
public enum LocationType {

    /** Good for landscape and scenic photography. */
    LANDSCAPE,

    /** Good for wildlife and animal photography. */
    WILDLIFE,

    /** Good for seascape and coastal photography — tide data displayed prominently. */
    SEASCAPE
}
