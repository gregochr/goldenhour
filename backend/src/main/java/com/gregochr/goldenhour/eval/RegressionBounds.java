package com.gregochr.goldenhour.eval;

/**
 * The owner-observed ground-truth bounds that the manual {@code SkyRatingRegressionTest} asserts for
 * one registered sky-rating day.
 *
 * <p>These are the numbers Chris observed or ruled on, checked against real Claude output by the
 * paid prompt-regression suite. Changing any of them is the owner's call only (CLAUDE.md: never
 * change prompt-regression assertions); {@code RegressionBoundsRegistryTest} pins every value
 * literally so an edit cannot go unnoticed.
 *
 * <p>The rating bound is a {@link RatingBand} that equals the fixture's own band; today every
 * fixture with bounds holds that, and {@code RegressionBoundsRegistryTest} guards the equality.
 * Ratings are 1-5, so the band's implicit floor
 * of 1 for an "at most" bound changes nothing in practice. A {@code null} sub-score end means that
 * side is not bounded.
 *
 * @param rating    the acceptable star rating
 * @param fieryMin  inclusive lower bound on Fiery Sky potential, or {@code null} for none
 * @param fieryMax  inclusive upper bound on Fiery Sky potential, or {@code null} for none
 * @param goldenMin inclusive lower bound on Golden Hour potential, or {@code null} for none
 * @param goldenMax inclusive upper bound on Golden Hour potential, or {@code null} for none
 */
public record RegressionBounds(RatingBand rating, Integer fieryMin, Integer fieryMax,
        Integer goldenMin, Integer goldenMax) {
}
