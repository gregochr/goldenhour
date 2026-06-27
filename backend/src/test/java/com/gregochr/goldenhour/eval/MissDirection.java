package com.gregochr.goldenhour.eval;

/**
 * Where a star rating falls relative to a fixture's expected {@link RatingBand}.
 *
 * <p>The direction of an out-of-band miss is the diagnostic payoff of the sky-rating eval:
 * it tells us not just <em>that</em> the scorer is miscalibrated for a fixture but <em>which
 * way</em>. A {@link #BELOW} miss means the scorer rated lower than the ground-truth band
 * (rounded down — too cautious); an {@link #ABOVE} miss means it rated higher (rounded up —
 * too generous).
 */
public enum MissDirection {

    /** Rating fell below the band — the scorer was too cautious. */
    BELOW,

    /** Rating landed inside the band — a pass. */
    IN_BAND,

    /** Rating rose above the band — the scorer was too generous. */
    ABOVE
}
