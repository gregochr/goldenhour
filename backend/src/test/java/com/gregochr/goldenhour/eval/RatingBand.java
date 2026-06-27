package com.gregochr.goldenhour.eval;

/**
 * An inclusive band of acceptable star ratings {@code [min, max]} for a sky-rating eval fixture.
 *
 * <p>The eval asserts a <em>band</em>, never a point. The scorer is non-deterministic, so a
 * point assertion ({@code rating == 4}) would fail on legitimate run-to-run variance. A band
 * ({@code rating ∈ {4,5}}) absorbs that variance while still catching genuine miscalibration:
 * a fixture that should be strong landing at 2, or a washout landing at 5, falls outside the
 * band regardless of which way the dice rolled.
 *
 * <p>Ratings are 1–5 (the {@code rating} field Claude returns directly). A band's bounds must
 * lie within that range and be non-inverted.
 *
 * @param min the lowest acceptable rating (inclusive, 1–5)
 * @param max the highest acceptable rating (inclusive, 1–5, ≥ {@code min})
 */
public record RatingBand(int min, int max) {

    private static final int MIN_RATING = 1;
    private static final int MAX_RATING = 5;

    /**
     * Validates the band lies within 1–5 and is not inverted.
     */
    public RatingBand {
        if (min < MIN_RATING || max > MAX_RATING || min > max) {
            throw new IllegalArgumentException(
                    "RatingBand must satisfy 1 <= min <= max <= 5 but was [" + min + ", " + max + "]");
        }
    }

    /**
     * Band of an exact rating, e.g. {@code exactly(4)} → {@code {4}}.
     *
     * @param rating the single acceptable rating
     * @return a one-wide band
     */
    public static RatingBand exactly(int rating) {
        return new RatingBand(rating, rating);
    }

    /**
     * Band of "no more than" a rating, e.g. {@code atMost(2)} → {@code {1,2}} (mirrors a
     * {@code rating <= 2} assertion).
     *
     * @param max the highest acceptable rating
     * @return a band from 1 to {@code max}
     */
    public static RatingBand atMost(int max) {
        return new RatingBand(MIN_RATING, max);
    }

    /**
     * Band of "at least" a rating, e.g. {@code atLeast(4)} → {@code {4,5}} (mirrors a
     * {@code rating >= 4} assertion).
     *
     * @param min the lowest acceptable rating
     * @return a band from {@code min} to 5
     */
    public static RatingBand atLeast(int min) {
        return new RatingBand(min, MAX_RATING);
    }

    /**
     * Returns whether a rating lies within this band.
     *
     * @param rating the star rating to test
     * @return true if {@code min <= rating <= max}
     */
    public boolean contains(int rating) {
        return rating >= min && rating <= max;
    }

    /**
     * Classifies a rating relative to this band — in-band, below (too cautious), or above
     * (too generous).
     *
     * @param rating the star rating to classify
     * @return the {@link MissDirection}
     */
    public MissDirection classify(int rating) {
        if (rating < min) {
            return MissDirection.BELOW;
        }
        if (rating > max) {
            return MissDirection.ABOVE;
        }
        return MissDirection.IN_BAND;
    }

    /**
     * Compact human-readable label, e.g. {@code {4}} or {@code {3–4}}.
     *
     * @return the label
     */
    public String label() {
        return min == max ? "{" + min + "}" : "{" + min + "–" + max + "}";
    }
}
