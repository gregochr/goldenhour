package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.service.ConfidenceDeriver.RegionRoster;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ConfidenceDeriver} — the region/cell confidence derivation
 * (horizon dominant; spread, coverage and roster size can only downgrade; a roster too small
 * for its verdict to mean anything is floored at LOW; unknown → null).
 */
class ConfidenceDeriverTest {

    /**
     * A voting roster comfortably clear of both small-region rules, so tests of the horizon,
     * spread and coverage terms are not silently answered by the floor instead.
     */
    private static final int HEALTHY_VOTING = 20;

    /** Full-coverage, tight-agreement stats for a region of {@code roster} locations. */
    private static BriefingRatingStats.Stats tight(int count) {
        // all ratings equal (range 0), full coverage
        return new BriefingRatingStats.Stats(count, count, 0L, 4.0, 4, 4);
    }

    /** A roster with the given coverage denominator and a voting roster that never downgrades. */
    private static RegionRoster roster(int scoreable) {
        return new RegionRoster(scoreable, HEALTHY_VOTING);
    }

    @Nested
    class HorizonBase {

        @Test
        void today_and_tomorrow_are_high() {
            assertThat(ConfidenceDeriver.derive(0, tight(4), roster(4))).isEqualTo(Confidence.HIGH);
            assertThat(ConfidenceDeriver.derive(1, tight(4), roster(4))).isEqualTo(Confidence.HIGH);
        }

        @Test
        void two_and_three_days_out_are_medium() {
            assertThat(ConfidenceDeriver.derive(2, tight(4), roster(4)))
                    .isEqualTo(Confidence.MEDIUM);
            assertThat(ConfidenceDeriver.derive(3, tight(4), roster(4)))
                    .isEqualTo(Confidence.MEDIUM);
        }

        @Test
        void four_days_or_more_are_low() {
            assertThat(ConfidenceDeriver.derive(4, tight(4), roster(4))).isEqualTo(Confidence.LOW);
            assertThat(ConfidenceDeriver.derive(7, tight(4), roster(4))).isEqualTo(Confidence.LOW);
        }

        @Test
        void a_past_date_is_treated_as_high() {
            // Defensive: daysAhead can be negative if a stale slot lingers; the <= 1 rule holds.
            assertThat(ConfidenceDeriver.derive(-1, tight(4), roster(4))).isEqualTo(Confidence.HIGH);
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
            assertThat(ConfidenceDeriver.derive(0, wideSpread(4, 4), roster(4)))
                    .isEqualTo(Confidence.MEDIUM);
        }

        @Test
        void wide_spread_downgrades_medium_to_low() {
            assertThat(ConfidenceDeriver.derive(2, wideSpread(4, 4), roster(4)))
                    .isEqualTo(Confidence.LOW);
        }

        @Test
        void wide_spread_cannot_go_below_low() {
            assertThat(ConfidenceDeriver.derive(5, wideSpread(4, 4), roster(4)))
                    .isEqualTo(Confidence.LOW);
        }

        @Test
        void a_range_of_one_does_not_downgrade() {
            // ratings 3 and 4 → range 1 (< WIDE_SPREAD_RANGE)
            BriefingRatingStats.Stats narrow = new BriefingRatingStats.Stats(4, 2, 2L, 3.5, 3, 4);
            assertThat(ConfidenceDeriver.derive(0, narrow, roster(4))).isEqualTo(Confidence.HIGH);
        }
    }

    @Nested
    class CoverageDowngrade {

        @Test
        void thin_coverage_downgrades_one_band() {
            // 1 of 4 scored (< half) at T+0 → HIGH downgrades to MEDIUM
            assertThat(ConfidenceDeriver.derive(0, tight(1), roster(4)))
                    .isEqualTo(Confidence.MEDIUM);
        }

        @Test
        void adequate_coverage_does_not_downgrade() {
            // 2 of 4 scored (== half, not below) → no downgrade
            assertThat(ConfidenceDeriver.derive(0, tight(2), roster(4))).isEqualTo(Confidence.HIGH);
        }

        @Test
        void unknown_roster_size_disables_coverage_downgrade() {
            // scoreable <= 0 must not divide/misfire — coverage rule is skipped
            assertThat(ConfidenceDeriver.derive(0, tight(1), roster(0))).isEqualTo(Confidence.HIGH);
        }
    }

    @Nested
    class SmallRegionFloor {

        /**
         * The band where {@code rollUpVerdict} is degenerate: one GO location produces "Worth it"
         * for every possible split, so the verdict cannot be presented as settled at any horizon.
         */
        @Test
        void a_degenerate_voting_roster_is_floored_at_low_even_today() {
            // Teesdale's four voting locations, perfect agreement, full coverage, T+0.
            assertThat(ConfidenceDeriver.derive(0, tight(4), new RegionRoster(4, 4)))
                    .isEqualTo(Confidence.LOW);
        }

        @Test
        void the_floor_holds_at_the_boundary_of_five() {
            assertThat(ConfidenceDeriver.derive(0, tight(5), new RegionRoster(5, 5)))
                    .isEqualTo(Confidence.LOW);
        }

        @Test
        void the_floor_beats_a_signal_that_would_otherwise_read_high() {
            // Tight agreement and full coverage at T+0 is the strongest possible input; the floor
            // must still win, because the weakness is structural rather than in the ratings.
            assertThat(ConfidenceDeriver.derive(1, tight(3), new RegionRoster(3, 3)))
                    .isEqualTo(Confidence.LOW);
        }

        @Test
        void a_fragile_roster_downgrades_one_band() {
            // Tyne and Wear's seven voting locations: two stand-downs put it in the degenerate
            // band, so T+0 reads MEDIUM rather than HIGH.
            assertThat(ConfidenceDeriver.derive(0, tight(7), new RegionRoster(7, 7)))
                    .isEqualTo(Confidence.MEDIUM);
        }

        @Test
        void a_fragile_roster_cannot_push_below_low() {
            assertThat(ConfidenceDeriver.derive(6, tight(7), new RegionRoster(7, 7)))
                    .isEqualTo(Confidence.LOW);
        }

        @Test
        void six_is_the_first_roster_above_the_degenerate_band() {
            // The boundary's other side. Without this, moving DEGENERATE_VOTING_ROSTER from 5 to 6
            // passes the whole suite: (5,5) is LOW either way, and the next case exercised was 7.
            // test-improvement-standards.md asks for the value, one below and one above.
            assertThat(ConfidenceDeriver.derive(0, tight(6), new RegionRoster(6, 6)))
                    .as("voting 6 is fragile, not degenerate — MEDIUM, not LOW")
                    .isEqualTo(Confidence.MEDIUM);
        }

        @Test
        void nine_is_still_fragile_and_ten_is_not() {
            assertThat(ConfidenceDeriver.derive(0, tight(9), new RegionRoster(9, 9)))
                    .isEqualTo(Confidence.MEDIUM);
            assertThat(ConfidenceDeriver.derive(0, tight(10), new RegionRoster(10, 10)))
                    .isEqualTo(Confidence.HIGH);
        }

        @Test
        void the_post_merge_regions_all_clear_both_rules() {
            // V137 leaves 77 / 59 / 58 / 33 voting. None may be downgraded on size alone.
            for (int voting : new int[] {33, 58, 59, 77}) {
                assertThat(ConfidenceDeriver.derive(0, tight(voting), new RegionRoster(voting, voting)))
                        .as("voting roster %d", voting)
                        .isEqualTo(Confidence.HIGH);
            }
        }

        @Test
        void a_non_positive_voting_roster_disables_both_rules() {
            // Mirrors the coverage rule's convention: unknown means "do not guess", not "assume
            // the worst" — a caller that cannot supply the roster gets the horizon reading.
            assertThat(ConfidenceDeriver.derive(0, tight(4), new RegionRoster(0, 0)))
                    .isEqualTo(Confidence.HIGH);
        }

        @Test
        void a_null_roster_disables_both_rules() {
            assertThat(ConfidenceDeriver.derive(0, tight(4), null)).isEqualTo(Confidence.HIGH);
        }

        @Test
        void the_two_denominators_are_not_interchangeable() {
            // A wood-heavy region in bluebell season: twelve slots carry ratings (the woods ARE
            // scored, by the bluebell prompt) but only four of them vote, because a canopy slot
            // never votes on the sky verdict. scoreable = 12, voting = 4.
            //
            // Chosen so the two orderings cannot agree. Correct: voting 4 is degenerate → LOW.
            // Transposed: scoreable 4 (coverage 10 of 4, adequate) and voting 12 (clear of both
            // roster rules) → HIGH. An earlier version of this test used (6, 8), where BOTH
            // orderings returned MEDIUM — so the one test written to catch a transposition could
            // not catch one. Swap the reads in derive(), or the arguments at
            // BriefingService.rosterOf, and this now fails.
            assertThat(ConfidenceDeriver.derive(0, tight(10), new RegionRoster(12, 4)))
                    .as("scoreable=12, voting=4 — the degenerate voting roster must win")
                    .isEqualTo(Confidence.LOW);
            assertThat(ConfidenceDeriver.derive(0, tight(10), new RegionRoster(4, 12)))
                    .as("transposed — must NOT agree with the correct ordering")
                    .isEqualTo(Confidence.HIGH);
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
            assertThat(ConfidenceDeriver.derive(0, BriefingRatingStats.Stats.empty(), roster(4)))
                    .isNull();
        }

        @Test
        void null_stats_yield_null() {
            assertThat(ConfidenceDeriver.derive(0, null, roster(4))).isNull();
        }

        @Test
        void a_degenerate_roster_does_not_manufacture_a_signal_from_nothing() {
            // The floor must not turn "unknown" into LOW — zero coverage stays null so the
            // frontend reads it as provisional rather than as a confident low reading.
            assertThat(ConfidenceDeriver.derive(0, BriefingRatingStats.Stats.empty(),
                    new RegionRoster(4, 4))).isNull();
        }
    }
}
