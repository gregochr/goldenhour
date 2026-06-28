package com.gregochr.goldenhour.entity;

/**
 * Lifecycle status of a sky-rating eval run.
 */
public enum SkyRatingEvalStatus {

    /** The run is in progress (fixtures still being scored). */
    RUNNING,

    /** All fixtures scored and results persisted. */
    COMPLETED,

    /** The run aborted with an error before completing. */
    FAILED
}
