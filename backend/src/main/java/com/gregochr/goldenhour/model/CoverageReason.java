package com.gregochr.goldenhour.model;

/**
 * Why a region has no per-location forecast for a window, as far as the pipeline's own record can
 * say. Chosen at serve time by the honesty filter's fold over the region's scoreable slots (see
 * {@code CoverageReasons}); it picks the wording of the blanked region and is never serialised.
 *
 * <p>Only {@link #STOOD_DOWN} and {@link #UNKNOWN} keep the "too unsettled" verdict. The other
 * three say nothing about the weather: nothing was attempted, the day is a travel day, or the run
 * did not complete.
 */
public enum CoverageReason {

    /** Every scoreable slot was skipped for a travel day: the operator is away, no batch ran. */
    AWAY,

    /** Gate 4 never scores this window (T+4, or T+2/T+3 with an unsettled pattern). */
    NOT_ATTEMPTED,

    /** Weather triage stood every scoreable slot down. */
    STOOD_DOWN,

    /** At least half of the scoreable slots were submitted or collected and the run did not finish. */
    FAILED,

    /** The record does not settle it; keeps the original "too unsettled" wording. */
    UNKNOWN
}
