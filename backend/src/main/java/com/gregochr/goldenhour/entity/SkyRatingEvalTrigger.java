package com.gregochr.goldenhour.entity;

/**
 * What initiated a sky-rating eval run.
 */
public enum SkyRatingEvalTrigger {

    /** Triggered on demand by an admin via the API. */
    MANUAL,

    /** Triggered by the dynamic scheduler on its cadence. */
    SCHEDULED
}
