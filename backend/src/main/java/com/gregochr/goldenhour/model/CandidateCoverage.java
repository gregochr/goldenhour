package com.gregochr.goldenhour.model;

/**
 * Per-(event, region) Claude-evaluation coverage extracted while building the
 * rollup, used by {@code BestBetRanker#applyCoverageAwareRanking} to enforce the headline
 * coverage floor without re-querying the cache.
 *
 * @param claudeRatedCount     number of locations in this region/event with a Claude rating
 * @param daysAhead            forecast horizon of the event (T+0 = 0)
 * @param claudeAverageRating  mean Claude star rating (0.0 when none rated)
 */
public record CandidateCoverage(int claudeRatedCount, int daysAhead, double claudeAverageRating) {
}
