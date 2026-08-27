package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CloudVerificationBucket#of(String, List)}, focused on the rating
 * statistics — the veto-demotion prompt change's pre-registered post-deploy instrument.
 *
 * <p>Most verified pairs carry {@code rating == null} (triaged before any prompt was built), so
 * these tests pin {@code ratedCount}, not {@code sampleCount}, as the denominator throughout.
 */
class CloudVerificationBucketTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 10);

    @Test
    @DisplayName("rating stats are computed over rated members only, ignoring null-rated ones")
    void of_mixedRatings_computesStatsOverRatedMembersOnly() {
        List<CloudVerificationPair> pairs = List.of(
                pair(3), pair(null), pair(5), pair(null), pair(1));

        CloudVerificationBucket bucket = CloudVerificationBucket.of("MIXED", pairs);

        // sampleCount counts everyone; ratedCount only the three with a non-null rating.
        assertThat(bucket.sampleCount()).isEqualTo(5);
        assertThat(bucket.ratedCount()).isEqualTo(3);
        // Mean is over the rated members only: (3 + 5 + 1) / 3 = 3.0, not diluted by the two nulls.
        assertThat(bucket.meanRating()).isEqualTo(3.0);
        // One each at ratings 1, 3 and 5; none at 2 or 4.
        assertThat(bucket.ratingCounts()).containsExactly(1, 0, 1, 0, 1);
        // The sum invariant: ratingCounts always sums to ratedCount, never sampleCount.
        assertThat(bucket.ratingCounts().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(bucket.ratedCount())
                .isNotEqualTo(bucket.sampleCount());
    }

    @Test
    @DisplayName("an out-of-range rating (0 or 6) is excluded exactly as if it were null")
    void of_outOfRangeRatings_excludedFromEveryStat() {
        // The synchronous engine writes this column without RatingValidator's guard, and 14
        // months of history predate the validator entirely — so 0 and 6 are real possible values,
        // not hypothetical ones. Alongside two in-range ratings so the exclusion is visible rather
        // than vacuous: a bucket of nothing-but-garbage would look identical to an all-null one.
        List<CloudVerificationPair> pairs = List.of(pair(0), pair(3), pair(6), pair(1));

        CloudVerificationBucket bucket = CloudVerificationBucket.of("OUT_OF_RANGE", pairs);

        // sampleCount still counts all four; ratedCount counts only the two in-range ratings.
        assertThat(bucket.sampleCount()).isEqualTo(4);
        assertThat(bucket.ratedCount()).isEqualTo(2);
        // Mean is (3 + 1) / 2 = 2.0 — 0 and 6 pulled neither into the sum nor the denominator.
        assertThat(bucket.meanRating()).isEqualTo(2.0);
        // Member: ratings 1 and 3 counted in their own bands. Non-members: 0 and 6 land nowhere —
        // not clamped into band 1 or band 5, and not silently summed into an adjacent band.
        assertThat(bucket.ratingCounts()).containsExactly(1, 0, 1, 0, 0);
        assertThat(bucket.ratingCounts().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(bucket.ratedCount());
    }

    @Test
    @DisplayName("an all-null-rating bucket has zero ratedCount, null meanRating, and zero counts")
    void of_allNullRatings_ratedCountZeroMeanRatingNull() {
        List<CloudVerificationPair> pairs = List.of(pair(null), pair(null), pair(null));

        CloudVerificationBucket bucket = CloudVerificationBucket.of("TRIAGED", pairs);

        assertThat(bucket.sampleCount()).isEqualTo(3);
        assertThat(bucket.ratedCount()).isZero();
        // Pinned as null rather than 0.0 — a zero would read as a rating of zero.
        assertThat(bucket.meanRating()).isNull();
        assertThat(bucket.ratingCounts()).containsExactly(0, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("an empty bucket behaves like an all-null one, with sampleCount also zero")
    void of_emptyPairList_sampleCountAndRatedCountBothZero() {
        CloudVerificationBucket bucket = CloudVerificationBucket.of("EMPTY", List.of());

        assertThat(bucket.sampleCount()).isZero();
        assertThat(bucket.ratedCount()).isZero();
        assertThat(bucket.meanRating()).isNull();
        assertThat(bucket.ratingCounts()).containsExactly(0, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("a null pair list is treated the same as an empty one")
    void of_nullPairList_sampleCountAndRatedCountBothZero() {
        CloudVerificationBucket bucket = CloudVerificationBucket.of("NULL", null);

        assertThat(bucket.sampleCount()).isZero();
        assertThat(bucket.ratedCount()).isZero();
        assertThat(bucket.meanRating()).isNull();
        assertThat(bucket.ratingCounts()).containsExactly(0, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("a rating of exactly 1 lands in the first band and nowhere else")
    void of_ratingExactlyOne_landsInFirstIndexOnly() {
        CloudVerificationBucket bucket = CloudVerificationBucket.of("LOW", List.of(pair(1)));

        assertThat(bucket.ratedCount()).isEqualTo(1);
        assertThat(bucket.meanRating()).isEqualTo(1.0);
        // Member: index 0 (rating 1) holds the count. Non-members: every other band is zero — an
        // off-by-one here would silently credit rating 1 to the wrong band.
        assertThat(bucket.ratingCounts()).containsExactly(1, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("a rating of exactly 5 lands in the last band and nowhere else")
    void of_ratingExactlyFive_landsInLastIndexOnly() {
        CloudVerificationBucket bucket = CloudVerificationBucket.of("HIGH", List.of(pair(5)));

        assertThat(bucket.ratedCount()).isEqualTo(1);
        assertThat(bucket.meanRating()).isEqualTo(5.0);
        // Member: index 4 (rating 5) holds the count. Non-members: every other band is zero.
        assertThat(bucket.ratingCounts()).containsExactly(0, 0, 0, 0, 1);
    }

    @Test
    @DisplayName("one member per band maps every rating to its own index, none swapped")
    void of_oneMemberPerBand_mapsEachRatingToItsOwnIndex() {
        List<CloudVerificationPair> pairs = List.of(
                pair(1), pair(2), pair(3), pair(4), pair(5));

        CloudVerificationBucket bucket = CloudVerificationBucket.of("ALL_BANDS", pairs);

        assertThat(bucket.ratedCount()).isEqualTo(5);
        assertThat(bucket.meanRating()).isEqualTo(3.0);
        // Each index holds exactly the count for its own rating — a reversed or off-by-one mapping
        // would still sum to 5 but move a count to the wrong index.
        assertThat(bucket.ratingCounts()).containsExactly(1, 1, 1, 1, 1);
    }

    /**
     * A pair whose only field this suite cares about is {@code rating}; every other component is
     * left null since the rating statistics are independent of the cloud-comparison fields.
     *
     * @param rating the rating to carry, or {@code null} for a triaged (unrated) member
     * @return the pair
     */
    private CloudVerificationPair pair(Integer rating) {
        return new CloudVerificationPair("Durham UK", DATE, TargetType.SUNSET, 0, rating,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }
}
