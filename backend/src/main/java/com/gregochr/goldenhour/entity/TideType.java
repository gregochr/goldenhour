package com.gregochr.goldenhour.entity;

/**
 * Indicates the photographer's tide preference for a coastal location,
 * or that the location is not coastal and tide data should not be fetched.
 */
public enum TideType {

    /** Photographer prefers to shoot at high tide. */
    HIGH_TIDE,

    /** Photographer prefers to shoot at low tide. */
    LOW_TIDE,

    /** Photographer prefers to shoot at mid tide (between high and low). */
    MID_TIDE,

    /** Both tides are suitable — evaluate regardless of tide state. */
    ANY_TIDE,

    /** Inland location — no tide data is fetched (default). */
    NOT_COASTAL
}
