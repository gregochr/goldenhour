package com.gregochr.goldenhour.entity;

/**
 * Identifies the type of forecast run — what was requested, not which model was used.
 *
 * <p>Replaces both {@code JobName} (which conflated run identity with model choice) and
 * {@code ModelConfigType} (which was named as if it were about models rather than time horizons).
 */
public enum RunType {

    /** Very short-term: today and tomorrow (T, T+1). */
    VERY_SHORT_TERM,

    /** Short-term: today through T+2. */
    SHORT_TERM,

    /** Long-term: T+3 through T+7. */
    LONG_TERM,

    /** Wildlife comfort data — no Claude evaluation. */
    WEATHER,

    /** Tide extremes refresh. */
    TIDE
}
