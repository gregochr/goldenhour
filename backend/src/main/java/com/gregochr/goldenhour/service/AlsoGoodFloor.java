package com.gregochr.goldenhour.service;

/**
 * The quality floor a second region must clear before a window offers it as "Also good".
 *
 * <p><b>This rule is authored here, not lifted from anywhere.</b> The plan for the window-first
 * Plan tab described it as an existing floor to be shared, on the strength of
 * {@code BriefingBestBetAdvisor.PICK_TWO_RATING_FLOOR}. That constant is private and its own
 * Javadoc says it "never gates selection or ranking" — its only use is a diagnostic log line, and
 * the 0.5 band had no Java representation at all. On the {@code bestBets} path the rule is prose in
 * {@code BestBetPromptText}, applied by Claude. So the two paths can share a <em>constant</em>;
 * they cannot share an <em>enforcement</em>, and no predicate can make them agree. Do not write
 * that they cannot drift — they can, and only a test asserting the prompt still states these
 * numbers will notice.
 *
 * <p><b>Why a floor at all.</b> A gloss carries no quality signal: glossing is gated on Claude
 * eligibility rather than verdict, so weather-STANDDOWN regions are glossed too, and the system
 * prompt asks for cautionary wording when they are. Without a floor, "Also good" would regularly
 * offer a second region whose own paragraph says not to go — a label contradicted by its body.
 * An honest silence is better than a padded recommendation.
 *
 * <p><b>Both terms are needed.</b> The absolute floor stops a poor night promoting its
 * least-poor region; the gap stops a genuinely good night promoting a region far behind the first.
 * Either alone admits the case the other exists to refuse.
 */
public final class AlsoGoodFloor {

    /**
     * The lowest average rating a second region may have and still be offered.
     *
     * <p>Mirrored by {@code BriefingBestBetAdvisor.PICK_TWO_RATING_FLOOR}'s diagnostic log so the
     * two surfaces cannot report different numbers.
     */
    public static final double MIN_ABSOLUTE = 3.0;

    /** How far below the top region's average a second region may fall and still be offered. */
    public static final double MAX_GAP_FROM_TOP = 0.5;

    private AlsoGoodFloor() {
    }

    /**
     * Whether a second region qualifies as "Also good" beside the window's top region.
     *
     * <p>Both averages must come from the same derivation — the window projection computes one
     * {@code BriefingRatingStats.Stats} per region and passes both from that single pass, so the
     * two comparands cannot drift within a window.
     *
     * <p>Both bounds are inclusive: a candidate at exactly {@link #MIN_ABSOLUTE}, or exactly
     * {@link #MAX_GAP_FROM_TOP} behind the top, qualifies.
     *
     * @param topAverage       the top region's average rating
     * @param candidateAverage the second region's average rating
     * @return {@code true} when the candidate clears both the absolute floor and the gap
     */
    public static boolean qualifies(double topAverage, double candidateAverage) {
        return candidateAverage >= MIN_ABSOLUTE
                && topAverage - candidateAverage <= MAX_GAP_FROM_TOP;
    }
}
