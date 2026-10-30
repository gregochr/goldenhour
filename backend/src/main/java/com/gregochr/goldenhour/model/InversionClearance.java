package com.gregochr.goldenhour.model;

/**
 * Where the viewpoint stands relative to the warm layer the v2 inversion calculator found
 * (inversion-v2-plan Phase 1b).
 */
public enum InversionClearance {

    /** The viewpoint is below the layer's base, inside the cold air or the cloud under it. */
    INSIDE,

    /** The viewpoint is between the layer's base and the next level above it: the cloud top is near. */
    EDGE,

    /** The viewpoint is at or above the next level above the layer's base. */
    CLEAR,

    /** A shallow layer with no fog evidence in the valley, so nothing can be claimed. */
    UNCONFIRMED,

    /** There was no warm layer to stand against. */
    NO_LAYER
}
