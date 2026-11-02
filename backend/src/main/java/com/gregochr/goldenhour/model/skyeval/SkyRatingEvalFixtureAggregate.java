package com.gregochr.goldenhour.model.skyeval;

/**
 * Query projection: one run's results for one fixture, aggregated in the database.
 *
 * <p>{@code attempts} counts every result row, including failed attempts (no rating, no direction), because
 * the pass rate is in-band over attempts. The rating statistics ({@code ratedCount}, {@code ratingSum},
 * {@code minRating}, {@code maxRating}) cover only rows that carry a rating, so a failed call never drags a
 * mean down.
 *
 * @param runId       the parent run
 * @param fixtureName the fixture
 * @param attempts    result rows, failed attempts included
 * @param inBand      rows whose rating fell inside the fixture's band
 * @param below       rows rated under the band
 * @param above       rows rated over the band
 * @param ratedCount  rows that carry a rating
 * @param ratingSum   sum of those ratings (null when none)
 * @param minRating   lowest rating (null when none)
 * @param maxRating   highest rating (null when none)
 */
public record SkyRatingEvalFixtureAggregate(Long runId, String fixtureName, Long attempts, Long inBand, Long below,
        Long above, Long ratedCount, Long ratingSum, Integer minRating, Integer maxRating) {

    /**
     * Normalises the nullable aggregates (a {@code sum} over no rows is null) so callers never unbox a null.
     *
     * @param runId       the parent run
     * @param fixtureName the fixture
     * @param attempts    result rows
     * @param inBand      in-band rows (null read as 0)
     * @param below       below-band rows (null read as 0)
     * @param above       above-band rows (null read as 0)
     * @param ratedCount  rows with a rating
     * @param ratingSum   sum of ratings
     * @param minRating   lowest rating
     * @param maxRating   highest rating
     */
    public SkyRatingEvalFixtureAggregate {
        attempts = attempts == null ? 0L : attempts;
        inBand = inBand == null ? 0L : inBand;
        below = below == null ? 0L : below;
        above = above == null ? 0L : above;
        ratedCount = ratedCount == null ? 0L : ratedCount;
    }
}
