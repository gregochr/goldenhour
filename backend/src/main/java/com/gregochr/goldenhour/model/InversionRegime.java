package com.gregochr.goldenhour.model;

/**
 * The kind of warm layer the v2 inversion calculator found over a valley (inversion-v2-plan Phase 1b).
 */
public enum InversionRegime {

    /** A shallow layer whose top is the first pressure level above the valley floor (radiation fog). */
    SURFACE_BASED,

    /** A layer with cold air beneath it, a deep stratus or fog sea under a warm cap. */
    ELEVATED,

    /** No temperature rise anywhere on the profile, so no warm layer. */
    NONE
}
