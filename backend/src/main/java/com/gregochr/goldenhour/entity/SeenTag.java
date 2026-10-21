package com.gregochr.goldenhour.entity;

/**
 * What a reader says they actually saw at a sunrise or sunset they rated (V187,
 * {@code docs/engineering/valley-mist-plan.md} M3): an optional tag on {@code outcome_rating}.
 *
 * <p>Independent of the star - a 4 with {@link #MIST} is a different morning from a 4 with {@link
 * #COLOUR} - and never required. Stored as the enum NAME in a {@code VARCHAR(10)}, so a new value is
 * a code change plus a widened CHECK, never a column rewrite.
 */
public enum SeenTag {

    /** Colour in the sky - the classic sunrise or sunset. */
    COLOUR,

    /** Valley or river mist. */
    MIST,

    /** A cloud inversion: a sea of cloud below the viewpoint. */
    INVERSION,

    /** Nothing to photograph: a flat, grey or blank sky. */
    FLAT,

    /** The reader did not see it. Goes with the "didn't see it" record (no star) and only with it. */
    NOT_SEEN
}
