package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.model.comingup.ComingUpBands;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link SurpriseScore}. */
class SurpriseScoreTest {

    // ── rarity ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("rarity is log2 of the mean gap in days, matching the README's reference points")
    void rarityMatchesReferencePoints() {
        assertThat(SurpriseScore.rarity(14.8)).isCloseTo(3.887, org.assertj.core.data.Offset.offset(0.01));
        assertThat(SurpriseScore.rarity(365.25)).isCloseTo(8.51, org.assertj.core.data.Offset.offset(0.01));
        assertThat(SurpriseScore.rarity(182.625)).isCloseTo(7.51, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("a non-positive mean gap is rejected rather than producing NaN or infinity")
    void nonPositiveGapIsRejected() {
        assertThatThrownBy(() -> SurpriseScore.rarity(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SurpriseScore.rarity(-5)).isInstanceOf(IllegalArgumentException.class);
    }

    // ── magnitude ────────────────────────────────────────────────────────

    @Test
    @DisplayName("no history at all defaults to the median, marked cold start")
    void noHistoryDefaultsToMedian() {
        SurpriseScore.MagnitudeResult result = SurpriseScore.magnitudeFromHistory(
                List.of(), 5.0, new ComingUpScoringProperties.Magnitude());

        assertThat(result.bits()).isEqualTo(SurpriseScore.DEFAULT_MAGNITUDE_BITS);
        assertThat(result.coldStart()).isTrue();
    }

    @Test
    @DisplayName("a mature distribution computes an exact percentile rather than bucketing")
    void matureDistributionComputesExactly() {
        // 100 observations, uniformly 1..100. A value of 100 is the single biggest → P(X>=100)=1/100.
        List<Double> history = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            history.add((double) i);
        }
        SurpriseScore.MagnitudeResult result = SurpriseScore.magnitudeFromHistory(
                history, 100.0, new ComingUpScoringProperties.Magnitude());

        assertThat(result.coldStart()).isFalse();
        // Laplace-smoothed: p = 1/101, bits = -log2(1/101) ≈ 6.658.
        assertThat(result.bits()).isCloseTo(6.658, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("under cold start, a median value does NOT score at or above the p95 reference — "
            + "the whole point of bucketing is that a thin sample cannot claim an extreme percentile "
            + "it did not actually observe")
    void coldStartMedianDoesNotReachP95() {
        // 10 observations (well under the 60-observation floor), value sits at the middle.
        List<Double> history = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);
        ComingUpScoringProperties.Magnitude config = new ComingUpScoringProperties.Magnitude();

        SurpriseScore.MagnitudeResult result = SurpriseScore.magnitudeFromHistory(history, 5.0, config);

        assertThat(result.coldStart()).isTrue();
        assertThat(result.bits()).isLessThan(config.getP95Bits());
        assertThat(result.bits()).isEqualTo(config.getMedianBits());
    }

    @Test
    @DisplayName("under cold start, a value clearing every observation on record buckets at p97, "
            + "not an inflated exact percentile a ten-point sample cannot support")
    void coldStartTopValueBucketsAtP97() {
        List<Double> history = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);
        ComingUpScoringProperties.Magnitude config = new ComingUpScoringProperties.Magnitude();

        SurpriseScore.MagnitudeResult result = SurpriseScore.magnitudeFromHistory(history, 100.0, config);

        assertThat(result.coldStart()).isTrue();
        assertThat(result.bits()).isEqualTo(config.getP97Bits());
    }

    // ── bands ────────────────────────────────────────────────────────────

    private static final ComingUpBands BANDS = new ComingUpBands(5.0, 7.5, 9.5);

    @Test
    @DisplayName("band edges are lower-inclusive: a score exactly at an edge clears that band")
    void bandEdgesAreLowerInclusive() {
        assertThat(SurpriseScore.bandOf(4.99, BANDS)).isEqualTo("onRequest");
        assertThat(SurpriseScore.bandOf(5.0, BANDS)).isEqualTo("list");
        assertThat(SurpriseScore.bandOf(7.49, BANDS)).isEqualTo("list");
        assertThat(SurpriseScore.bandOf(7.5, BANDS)).isEqualTo("announce");
        assertThat(SurpriseScore.bandOf(9.49, BANDS)).isEqualTo("announce");
        assertThat(SurpriseScore.bandOf(9.5, BANDS)).isEqualTo("interrupt");
        assertThat(SurpriseScore.bandOf(20.0, BANDS)).isEqualTo("interrupt");
    }

    // ── king-as-big-spring (plan D4) ─────────────────────────────────────

    @Test
    @DisplayName("a king run's rarity is the spring rate (3.9 bits), never the near-annual perigee "
            + "rate — a king tide is a big spring, not a rarer event of its own")
    void kingRunUsesSpringRarityNotItsOwnRate() {
        double springTideRarity = SurpriseScore.rarity(14.8);

        assertThat(springTideRarity).isCloseTo(3.9, org.assertj.core.data.Offset.offset(0.05));
        // An annual-ish king rate (~5.5/year, per LunarPhaseService's perigee-window doc) would be
        // roughly log2(365.25/5.5) ≈ 6.05 bits — noticeably higher, and double-counts the same
        // perigee that already inflated the magnitude.
        assertThat(springTideRarity).isLessThan(SurpriseScore.rarity(365.25 / 5.5));
    }

    // ── gapWord (lunar-eclipse plan §2.9: "no more bits") ──────────────────

    @Test
    @DisplayName("gapWord reads the rarity component back as a plain calendar phrase, matching "
            + "every mean-gap-days value actually configured for a topic — not merely tuned to "
            + "hit the four phrases the design bundle happens to name")
    void gapWordMatchesConfiguredTopics() {
        // spring/king tide: half the mean synodic month.
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(14.8))).isEqualTo("a fortnight");
        // supermoon: a full moon within the perigee window.
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(60.0))).isEqualTo("a couple of months");
        // equinox/solstice: two of each a year.
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(182.625))).isEqualTo("six months");
        // meteor shower and NLC season: once a year.
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(365.25))).isEqualTo("a year");
        // lunar eclipse (L1): 900 days ≈ 2.46 years.
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(900.0))).isEqualTo("every two to three years");
        // solar eclipse: 1500 days ≈ 4.1 years — outside the "two to three" band, so it falls to
        // the open-ended phrase rather than overclaiming "two to three years" for a 4-year gap.
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(1500.0))).isEqualTo("every 4 years");
    }

    @Test
    @DisplayName("gapWord's bucket boundaries — a value just inside a bucket reads that bucket's "
            + "phrase, a value just past it reads the next one")
    void gapWordBucketBoundaries() {
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(1.4))).isEqualTo("a day");
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(1.6))).isEqualTo("a week");
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(9.9))).isEqualTo("a week");
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(10.1))).isEqualTo("a fortnight");
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(24.9))).isEqualTo("a fortnight");
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(25.1))).isEqualTo("a month");
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(44.9))).isEqualTo("a month");
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(45.1))).isEqualTo("a couple of months");
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(99.9))).isEqualTo("a couple of months");
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(100.1))).isEqualTo("six months");
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(273.9))).isEqualTo("six months");
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(274.1))).isEqualTo("a year");
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(547.9))).isEqualTo("a year");
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(548.1))).isEqualTo("every two to three years");
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(1199.9))).isEqualTo("every two to three years");
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(1200.1))).isEqualTo("every 3 years");
    }

    @Test
    @DisplayName("gapWord never prints the raw bits figure alongside the phrase")
    void gapWordNeverPrintsTheFigure() {
        assertThat(SurpriseScore.gapWord(SurpriseScore.rarity(14.8))).doesNotContain("3.9").doesNotContain("(");
    }
}
