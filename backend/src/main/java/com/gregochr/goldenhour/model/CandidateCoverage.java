package com.gregochr.goldenhour.model;

/**
 * Per-(event, region) Claude-evaluation coverage extracted while building the
 * rollup, used by {@code BestBetRanker#applyCoverageAwareRanking} to enforce the headline
 * coverage floor without re-querying the cache.
 *
 * @param claudeRatedCount     number of locations in this region/event with a Claude rating
 * @param daysAhead            forecast horizon of the event (T+0 = 0)
 * @param claudeAverageRating  mean Claude star rating (0.0 when none rated)
 * @param verdictEligible      the region's own {@code BriefingRegion#verdictEligible()} at the
 *                             moment the rollup was built (round 10, P1-B) — whether its sample
 *                             is large enough to trust, or it carries a force-evaluation
 *                             exemption. {@code BestBetRanker#dropIneligiblePicks} uses this to
 *                             validate the advisor's own model-generated response against the
 *                             identical rule the Plan tab already applies, so an insufficient,
 *                             non-exempt region cannot be crowned a best bet merely because
 *                             Claude named it. {@code false} on a {@link
 *                             com.gregochr.goldenhour.service.evaluation.BriefingRollupBuilder
 *                             #reconstructRollup} replay — a stored rollup JSON never captured
 *                             this per-region fact (adding it would put a new field in front of
 *                             Claude, the exact prompt-shape risk this fix avoids), so the value
 *                             is a placeholder never consulted on that path; see {@code
 *                             BestBetRanker#dropIneligiblePicks}'s own javadoc for why replay
 *                             does not call it at all
 */
public record CandidateCoverage(int claudeRatedCount, int daysAhead, double claudeAverageRating,
                                 boolean verdictEligible) {
}
