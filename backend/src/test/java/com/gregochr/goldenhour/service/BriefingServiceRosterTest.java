package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BriefingRegionEvaluationRollup#rosterOf}.
 *
 * <p>The class name still says BriefingService because that is where this method lived
 * until the region rollup was extracted (2026-08-27). Only the owning class changed —
 * every assertion below is byte-identical to the one that guarded it there, which is
 * what makes them proof that the move carried no behaviour with it.
 * <p>It derives the two denominators handed to {@link ConfidenceDeriver}.
 *
 * <p>These exist because the derivation had no test at any level: the canopy filter, the
 * all-canopy fallback and the ordering of the two counts into {@code RegionRoster} were all
 * reachable only through the full briefing refresh, so deleting the fallback outright broke
 * nothing. Every assertion here names both components, because the failure mode this guards is a
 * silent transposition rather than an exception.
 */
class BriefingServiceRosterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 1);
    private static final LocalDateTime DAWN = LocalDateTime.of(TODAY, LocalTime.of(5, 30));

    /** An open-sky slot carrying no Claude rating. */
    private static BriefingSlot sky(String name) {
        return new BriefingSlot(name, DAWN, Verdict.GO, null, BriefingSlot.TideInfo.NONE,
                List.of(), null);
    }

    /** An open-sky slot the batch has scored. */
    private static BriefingSlot scoredSky(String name) {
        return sky(name).withClaudeScores(4, 70, 60, "Colour likely.");
    }

    /** A canopy slot with no rating — out of the sky batch, so out of both denominators. */
    private static BriefingSlot wood(String name) {
        return BriefingSlot.canopySlot(name, DAWN, Verdict.GO, null, List.of(), null);
    }

    /** A canopy slot the bluebell prompt HAS scored — in coverage, still not voting. */
    private static BriefingSlot scoredWood(String name) {
        return wood(name).withClaudeScores(4, 70, 60, "Bluebells at peak.");
    }

    @Nested
    @DisplayName("A region with no woods counts every slot once")
    class OpenSkyOnly {

        @Test
        void both_denominators_equal_the_slot_count() {
            ConfidenceDeriver.RegionRoster roster = BriefingRegionEvaluationRollup.rosterOf(
                    List.of(scoredSky("Bamburgh"), scoredSky("Embleton"), sky("Craster")));
            assertThat(roster.scoreable()).as("scoreable").isEqualTo(3);
            assertThat(roster.voting()).as("voting").isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("A wood-bearing region is where the two denominators diverge")
    class MixedRegion {

        @Test
        void an_unrated_wood_is_absent_from_both() {
            // Out of season: the wood carries no rating, so counting it in coverage would report a
            // shortfall that can never be closed.
            ConfidenceDeriver.RegionRoster roster = BriefingRegionEvaluationRollup.rosterOf(
                    List.of(scoredSky("Bamburgh"), scoredSky("Embleton"), wood("Plessey Woods")));
            assertThat(roster.scoreable()).as("scoreable — the wood cannot be scored").isEqualTo(2);
            assertThat(roster.voting()).as("voting — the wood never votes").isEqualTo(2);
        }

        @Test
        void a_scored_wood_counts_for_coverage_and_still_never_votes() {
            // In bluebell season. This is the whole reason the two are separate numbers, and the
            // asymmetry (3 vs 2) is what a transposition at the call site would invert.
            ConfidenceDeriver.RegionRoster roster = BriefingRegionEvaluationRollup.rosterOf(
                    List.of(scoredSky("Bamburgh"), scoredSky("Embleton"),
                            scoredWood("Plessey Woods")));
            assertThat(roster.scoreable()).as("scoreable — the bluebell prompt scored it")
                    .isEqualTo(3);
            assertThat(roster.voting()).as("voting — a canopy slot has no sky verdict to give")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("An all-canopy region falls back rather than reporting a roster of zero")
    class AllCanopy {

        @Test
        void voting_falls_back_to_the_full_slot_list() {
            // Mirrors BriefingHierarchyBuilder.buildRegion, which lets canopy slots vote when
            // there is nothing else. Without the fallback voting is 0, which DISABLES the
            // small-region rules — so the smallest possible region would be the one region exempt
            // from the floor built to catch it.
            ConfidenceDeriver.RegionRoster roster = BriefingRegionEvaluationRollup.rosterOf(
                    List.of(scoredWood("Hackfall"), scoredWood("Middleton Woods")));
            assertThat(roster.scoreable()).as("scoreable").isEqualTo(2);
            assertThat(roster.voting()).as("voting falls back to the full list").isEqualTo(2);
        }

        @Test
        void an_unscored_all_canopy_region_has_no_coverage_but_still_votes() {
            ConfidenceDeriver.RegionRoster roster = BriefingRegionEvaluationRollup.rosterOf(
                    List.of(wood("Hackfall"), wood("Middleton Woods"), wood("Houghall")));
            assertThat(roster.scoreable()).as("scoreable — nothing here can be scored").isZero();
            assertThat(roster.voting()).as("voting falls back to the full list").isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Absent input disables the roster rules rather than misfiring")
    class NoSlots {

        @Test
        void an_empty_list_yields_zeroes() {
            ConfidenceDeriver.RegionRoster roster = BriefingRegionEvaluationRollup.rosterOf(List.of());
            assertThat(roster.scoreable()).isZero();
            assertThat(roster.voting()).isZero();
        }

        @Test
        void null_yields_zeroes_rather_than_throwing() {
            ConfidenceDeriver.RegionRoster roster = BriefingRegionEvaluationRollup.rosterOf(null);
            assertThat(roster.scoreable()).isZero();
            assertThat(roster.voting()).isZero();
        }

        @Test
        void zero_disables_both_small_region_rules_in_the_deriver() {
            // The contract the zeroes rely on: unknown means "do not guess", not "assume the
            // worst". Pinned here so the two halves cannot drift apart.
            assertThat(ConfidenceDeriver.derive(0, new BriefingRatingStats.Stats(4, 4, 0L, 4.0,
                    4, 4), BriefingRegionEvaluationRollup.rosterOf(List.of())))
                    .isEqualTo(com.gregochr.goldenhour.model.Confidence.HIGH);
        }
    }
}
