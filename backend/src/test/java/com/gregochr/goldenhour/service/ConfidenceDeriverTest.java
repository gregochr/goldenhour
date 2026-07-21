package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.Confidence;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ConfidenceDeriver} — the region/cell confidence derivation
 * (horizon dominant; spread/coverage can only downgrade; unknown → null).
 */
class ConfidenceDeriverTest {

    /** Full-coverage, tight-agreement stats for a region of {@code roster} locations. */
    private static BriefingRatingStats.Stats tight(int count) {
        // all ratings equal (range 0), full coverage
        return new BriefingRatingStats.Stats(count, count, 0L, 4.0, 4, 4);
    }

    @Nested
    class HorizonBase {

        @Test
        void today_and_tomorrow_are_high() {
            assertThat(ConfidenceDeriver.derive(0, tight(4), 4)).isEqualTo(Confidence.HIGH);
            assertThat(ConfidenceDeriver.derive(1, tight(4), 4)).isEqualTo(Confidence.HIGH);
        }

        @Test
        void two_and_three_days_out_are_medium() {
            assertThat(ConfidenceDeriver.derive(2, tight(4), 4)).isEqualTo(Confidence.MEDIUM);
            assertThat(ConfidenceDeriver.derive(3, tight(4), 4)).isEqualTo(Confidence.MEDIUM);
        }

        @Test
        void four_days_or_more_are_low() {
            assertThat(ConfidenceDeriver.derive(4, tight(4), 4)).isEqualTo(Confidence.LOW);
            assertThat(ConfidenceDeriver.derive(7, tight(4), 4)).isEqualTo(Confidence.LOW);
        }

        @Test
        void a_past_date_is_treated_as_high() {
            // Defensive: daysAhead can be negative if a stale slot lingers; the <= 1 rule holds.
            assertThat(ConfidenceDeriver.derive(-1, tight(4), 4)).isEqualTo(Confidence.HIGH);
        }
    }

    @Nested
    class SpreadDowngrade {

        private static BriefingRatingStats.Stats wideSpread(int count, int roster) {
            // range = 5 - 2 = 3 (>= WIDE_SPREAD_RANGE) — locations disagree sharply
            return new BriefingRatingStats.Stats(count, 1, 0L, 3.5, 2, 5);
        }

        @Test
        void wide_spread_downgrades_high_to_medium() {
            assertThat(ConfidenceDeriver.derive(0, wideSpread(4, 4), 4)).isEqualTo(Confidence.MEDIUM);
        }

        @Test
        void wide_spread_downgrades_medium_to_low() {
            assertThat(ConfidenceDeriver.derive(2, wideSpread(4, 4), 4)).isEqualTo(Confidence.LOW);
        }

        @Test
        void wide_spread_cannot_go_below_low() {
            assertThat(ConfidenceDeriver.derive(5, wideSpread(4, 4), 4)).isEqualTo(Confidence.LOW);
        }

        @Test
        void a_range_of_one_does_not_downgrade() {
            // ratings 3 and 4 → range 1 (< WIDE_SPREAD_RANGE)
            BriefingRatingStats.Stats narrow = new BriefingRatingStats.Stats(4, 2, 2L, 3.5, 3, 4);
            assertThat(ConfidenceDeriver.derive(0, narrow, 4)).isEqualTo(Confidence.HIGH);
        }
    }

    @Nested
    class CoverageDowngrade {

        @Test
        void thin_coverage_downgrades_one_band() {
            // 1 of 4 scored (< half) at T+0 → HIGH downgrades to MEDIUM
            assertThat(ConfidenceDeriver.derive(0, tight(1), 4)).isEqualTo(Confidence.MEDIUM);
        }

        @Test
        void adequate_coverage_does_not_downgrade() {
            // 2 of 4 scored (== half, not below) → no downgrade
            assertThat(ConfidenceDeriver.derive(0, tight(2), 4)).isEqualTo(Confidence.HIGH);
        }

        @Test
        void unknown_roster_size_disables_coverage_downgrade() {
            // rosterSize <= 0 must not divide/misfire — coverage rule is skipped
            assertThat(ConfidenceDeriver.derive(0, tight(1), 0)).isEqualTo(Confidence.HIGH);
        }
    }

    @Nested
    class HorizonOnly {

        @Test
        void maps_bands_and_is_never_null() {
            // The per-evaluation (forecast_evaluation.confidence) derivation — horizon only, no
            // spread, never null since an evaluated row always has a known horizon.
            assertThat(ConfidenceDeriver.fromHorizon(0)).isEqualTo(Confidence.HIGH);
            assertThat(ConfidenceDeriver.fromHorizon(1)).isEqualTo(Confidence.HIGH);
            assertThat(ConfidenceDeriver.fromHorizon(2)).isEqualTo(Confidence.MEDIUM);
            assertThat(ConfidenceDeriver.fromHorizon(3)).isEqualTo(Confidence.MEDIUM);
            assertThat(ConfidenceDeriver.fromHorizon(4)).isEqualTo(Confidence.LOW);
            assertThat(ConfidenceDeriver.fromHorizon(10)).isEqualTo(Confidence.LOW);
            assertThat(ConfidenceDeriver.fromHorizon(-1)).isEqualTo(Confidence.HIGH);
        }
    }

    @Nested
    class UnknownSignal {

        @Test
        void empty_stats_yield_null_not_medium() {
            assertThat(ConfidenceDeriver.derive(0, BriefingRatingStats.Stats.empty(), 4)).isNull();
        }

        @Test
        void null_stats_yield_null() {
            assertThat(ConfidenceDeriver.derive(0, null, 4)).isNull();
        }
    }
}
