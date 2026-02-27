package com.gregochr.goldenhour.entity;

/**
 * Identifies which forecast run type a model configuration applies to.
 *
 * <p>Each run type can have an independent evaluation model selection,
 * allowing the admin to use a more accurate (and expensive) model for
 * imminent forecasts while using a cheaper model for distant ones.
 */
public enum ModelConfigType {

    /** Very short-term: today and tomorrow (T, T+1). */
    VERY_SHORT_TERM,

    /** Short-term: today through T+2. */
    SHORT_TERM,

    /** Long-term: T+3 through T+7. */
    LONG_TERM
}
