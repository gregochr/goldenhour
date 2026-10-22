package com.gregochr.goldenhour.model;

/**
 * Why a region has no per-location forecast for a window, as far as the pipeline's own record can
 * say. Chosen at serve time by the honesty filter's fold over the region's scoreable slots (see
 * {@code CoverageReasons}); it picks the wording of the blanked region. Since the Ask window spread it
 * also rides the blanked region as {@code BriefingRegion.coverageReason} (serve path only, never
 * persisted), so a reader can tell a real stand-down from the "no record" the filter words AWAITING.
 *
 * <p>Only {@link #STOOD_DOWN} keeps the "too unsettled" verdict. The other four say nothing about
 * the weather: nothing was attempted, the day is a travel day, the run did not complete, or the
 * record cannot say ({@link #UNKNOWN}, worded "Not scored").
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

    /** The record does not settle it; worded AWAITING "Not scored", with no claim about the weather. */
    UNKNOWN
}
