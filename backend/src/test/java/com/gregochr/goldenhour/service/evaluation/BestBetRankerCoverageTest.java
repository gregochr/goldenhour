package com.gregochr.goldenhour.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.CandidateCoverage;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DiffersBy;
import com.gregochr.goldenhour.model.Relationship;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link BestBetRanker}'s coverage-aware headline ranking and the exemption and
 * eligibility edges of the two drop rules, complementing {@link BestBetRankerTest}.
 */
class BestBetRankerCoverageTest {

    private static final String SUNSET_30 = "2026-03-30_sunset";
    private static final String SUNSET_31 = "2026-03-31_sunset";
    private static final String SUNRISE_30 = "2026-03-30_sunrise";

    private static BestBet bet(int rank, String region, String event, Relationship relationship,
            List<DiffersBy> differsBy) {
        return new BestBet(rank, "headline-" + rank, "detail-" + rank, event, region,
                Confidence.HIGH, 30, "Today", "sunset", "18:00", relationship, differsBy);
    }

    private static BestBet bet(int rank, String region, String event) {
        return bet(rank, region, event, null, List.of());
    }

    private static CandidateCoverage rated(int count, boolean eligible) {
        return new CandidateCoverage(count, 0, 4.0, eligible);
    }

    private static Map<String, CandidateCoverage> coverage(Object... keyThenCoverage) {
        java.util.Map<String, CandidateCoverage> map = new java.util.HashMap<>();
        for (int i = 0; i < keyThenCoverage.length; i += 2) {
            map.put((String) keyThenCoverage[i], (CandidateCoverage) keyThenCoverage[i + 1]);
        }
        return map;
    }

    // ── afterRemoval ──

    @Test
    @DisplayName("afterRemoval: when the original never had a rank 1, survivors are just renumbered")
    void afterRemoval_noOriginalHeadline_renumbersSurvivors() {
        BestBet two = bet(2, "A", SUNSET_30);
        BestBet three = bet(3, "B", SUNSET_30);
        BestBet four = bet(4, "C", SUNSET_30);

        List<BestBet> result = BestBetRanker.afterRemoval(List.of(two, three, four), List.of(two, four));

        assertThat(result).extracting(BestBet::rank).containsExactly(1, 2);
        assertThat(result).extracting(BestBet::region).containsExactly("A", "C");
    }

    // ── colour-exemption edges (via the drop rules) ──

    @Test
    @DisplayName("dropUnevaluatedPicks: a pick with a region but no event is not exempt and, unrated, is dropped")
    void dropUnevaluated_regionButNoEvent_notExempt() {
        BestBet head = bet(1, "A", SUNSET_30);
        BestBet regionOnly = bet(2, "B", null);

        List<BestBet> result = BestBetRanker.dropUnevaluatedPicks(List.of(head, regionOnly),
                coverage(BestBetRanker.coverageKey(SUNSET_30, "A"), rated(4, true)));

        assertThat(result).extracting(BestBet::region).containsExactly("A");
    }

    @Test
    @DisplayName("dropUnevaluatedPicks: an event with no region is not exempt (unless aurora) and is dropped")
    void dropUnevaluated_eventButNoRegion_notExempt() {
        BestBet head = bet(1, "A", SUNSET_30);
        BestBet eventOnly = bet(2, null, SUNSET_30);

        List<BestBet> result = BestBetRanker.dropUnevaluatedPicks(List.of(head, eventOnly),
                coverage(BestBetRanker.coverageKey(SUNSET_30, "A"), rated(4, true)));

        assertThat(result).extracting(BestBet::event).containsExactly(SUNSET_30);
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("dropUnevaluatedPicks: stay-home and aurora picks are exempt and kept without coverage")
    void dropUnevaluated_stayHomeAndAurora_keptWithoutCoverage() {
        BestBet head = bet(1, "A", SUNSET_30);
        BestBet aurora = bet(2, "A", "2026-03-30_aurora");
        BestBet stayHome = bet(3, null, null);

        List<BestBet> result = BestBetRanker.dropUnevaluatedPicks(List.of(head, aurora, stayHome),
                coverage(BestBetRanker.coverageKey(SUNSET_30, "A"), rated(1, true)));

        assertThat(result).extracting(BestBet::rank).containsExactly(1, 2, 3);
    }

    // ── eligibility edges ──

    @Test
    @DisplayName("dropIneligiblePicks: picks lacking an event or a region, or absent from coverage, are dropped")
    void dropIneligible_missingEventRegionOrCoverage_dropped() {
        BestBet head = bet(1, "A", SUNSET_30);
        BestBet noEvent = bet(2, "B", null);
        BestBet noRegion = bet(3, null, SUNSET_30);
        BestBet absent = bet(4, "Z", SUNSET_30);
        BestBet ineligible = bet(5, "C", SUNSET_30);

        List<BestBet> result = BestBetRanker.dropIneligiblePicks(
                List.of(head, noEvent, noRegion, absent, ineligible),
                coverage(BestBetRanker.coverageKey(SUNSET_30, "A"), rated(4, true),
                        BestBetRanker.coverageKey(SUNSET_30, "C"), rated(4, false)));

        assertThat(result).extracting(BestBet::region).containsExactly("A");
    }

    // ── applyCoverageAwareRanking ──

    @Test
    @DisplayName("applyCoverageAwareRanking: fewer than two picks are returned as they are")
    void coverageRanking_singlePick_unchanged() {
        List<BestBet> picks = List.of(bet(1, "A", SUNSET_30));

        assertThat(BestBetRanker.applyCoverageAwareRanking(picks, coverage())).isSameAs(picks);
    }

    @Test
    @DisplayName("applyCoverageAwareRanking: a headline that clears the coverage floor keeps rank 1")
    void coverageRanking_wellCoveredHeadline_unchanged() {
        List<BestBet> picks = List.of(bet(1, "A", SUNSET_30), bet(2, "B", SUNSET_30));

        List<BestBet> result = BestBetRanker.applyCoverageAwareRanking(picks, coverage(
                BestBetRanker.coverageKey(SUNSET_30, "A"), rated(3, true),
                BestBetRanker.coverageKey(SUNSET_30, "B"), rated(9, true)));

        assertThat(result).isSameAs(picks);
    }

    @Test
    @DisplayName("applyCoverageAwareRanking: an aurora headline is exempt from the coverage floor")
    void coverageRanking_auroraHeadline_exempt() {
        List<BestBet> picks = List.of(bet(1, "A", "2026-03-30_aurora"), bet(2, "B", SUNSET_30));

        List<BestBet> result = BestBetRanker.applyCoverageAwareRanking(picks, coverage(
                BestBetRanker.coverageKey(SUNSET_30, "B"), rated(9, true)));

        assertThat(result).isSameAs(picks);
    }

    @Test
    @DisplayName("applyCoverageAwareRanking: a thin headline with no better-covered alternative keeps its place")
    void coverageRanking_noBetterCoveredAlternative_unchanged() {
        List<BestBet> picks = List.of(bet(1, "A", SUNSET_30), bet(2, "B", SUNSET_30));

        List<BestBet> result = BestBetRanker.applyCoverageAwareRanking(picks, coverage(
                BestBetRanker.coverageKey(SUNSET_30, "A"), rated(2, true),
                BestBetRanker.coverageKey(SUNSET_30, "B"), rated(2, true)));

        assertThat(result).isSameAs(picks);
    }

    @Test
    @DisplayName("applyCoverageAwareRanking: a thin headline is demoted for a well-covered same-slot pick, "
            + "and the old headline is re-related to the new one")
    void coverageRanking_thinHeadlineDemoted_sameSlotRelationships() {
        List<BestBet> picks = List.of(
                bet(1, "A", SUNSET_30),
                bet(2, "B", SUNSET_30, Relationship.SAME_SLOT, List.of()));

        List<BestBet> result = BestBetRanker.applyCoverageAwareRanking(picks, coverage(
                BestBetRanker.coverageKey(SUNSET_30, "A"), rated(1, true),
                BestBetRanker.coverageKey(SUNSET_30, "B"), rated(5, true)));

        assertThat(result).extracting(BestBet::region).containsExactly("B", "A");
        assertThat(result).extracting(BestBet::rank).containsExactly(1, 2);
        assertThat(result.get(0).relationship()).isNull();
        assertThat(result.get(0).differsBy()).isEmpty();
        assertThat(result.get(1).relationship()).isEqualTo(Relationship.SAME_SLOT);
        assertThat(result.get(1).differsBy()).isEmpty();
    }

    @Test
    @DisplayName("applyCoverageAwareRanking: differing date, event and region are all listed in differsBy")
    void coverageRanking_differentSlot_listsEveryDifferingDimension() {
        List<BestBet> picks = List.of(
                bet(1, "A", SUNSET_30),
                bet(2, "B", SUNSET_31),
                bet(3, "C", SUNRISE_30),
                bet(4, "A", "2026-03-31_sunrise"));

        List<BestBet> result = BestBetRanker.applyCoverageAwareRanking(picks, coverage(
                BestBetRanker.coverageKey(SUNSET_30, "A"), rated(0, true),
                BestBetRanker.coverageKey(SUNSET_31, "B"), rated(6, true),
                BestBetRanker.coverageKey(SUNRISE_30, "C"), rated(1, true),
                BestBetRanker.coverageKey("2026-03-31_sunrise", "A"), rated(1, true)));

        // B (2026-03-31 sunset, region B) is the new head.
        assertThat(result).extracting(BestBet::region).containsExactly("B", "A", "C", "A");
        // A / 30 sunset vs head B / 31 sunset: date and region differ, event does not.
        assertThat(result.get(1).relationship()).isEqualTo(Relationship.DIFFERENT_SLOT);
        assertThat(result.get(1).differsBy()).containsExactly(DiffersBy.DATE, DiffersBy.REGION);
        // C / 30 sunrise vs head: date, event and region all differ.
        assertThat(result.get(2).differsBy())
                .containsExactly(DiffersBy.DATE, DiffersBy.EVENT, DiffersBy.REGION);
        // A / 31 sunrise vs head B / 31 sunset: event and region differ.
        assertThat(result.get(3).differsBy()).containsExactly(DiffersBy.EVENT, DiffersBy.REGION);
    }

    @Test
    @DisplayName("applyCoverageAwareRanking: a trailing pick in the head's slot and region differs by nothing")
    void coverageRanking_sameSlotSameRegion_noDifference() {
        List<BestBet> picks = List.of(
                bet(1, "A", SUNSET_30),
                bet(2, "B", SUNSET_31),
                bet(3, "B", SUNSET_31));

        List<BestBet> result = BestBetRanker.applyCoverageAwareRanking(picks, coverage(
                BestBetRanker.coverageKey(SUNSET_30, "A"), rated(0, true),
                BestBetRanker.coverageKey(SUNSET_31, "B"), rated(6, true)));

        assertThat(result.get(0).region()).isEqualTo("B");
        assertThat(result.get(2).relationship()).isEqualTo(Relationship.SAME_SLOT);
        assertThat(result.get(2).differsBy()).isEmpty();
    }

    @Test
    @DisplayName("applyCoverageAwareRanking: an unparseable or missing event makes the trailing pick a "
            + "different slot, differing by region only")
    void coverageRanking_unparseableEvents_differentSlotByRegionOnly() {
        List<BestBet> picks = List.of(
                bet(1, "A", SUNSET_30),
                bet(2, "B", SUNSET_31),
                bet(3, "C", "garbage"),
                bet(4, "D", "_sunset"),
                bet(5, "E", "2026-03-30_"),
                bet(6, "F", "2026-03-30_aurora"),
                bet(7, null, null));

        List<BestBet> result = BestBetRanker.applyCoverageAwareRanking(picks, coverage(
                BestBetRanker.coverageKey(SUNSET_30, "A"), rated(0, true),
                BestBetRanker.coverageKey(SUNSET_31, "B"), rated(6, true)));

        assertThat(result.get(0).region()).isEqualTo("B");
        // "garbage", "_sunset" and "2026-03-30_" do not split into date and type.
        for (int i : new int[] {2, 3, 4}) {
            assertThat(result.get(i).relationship()).isEqualTo(Relationship.DIFFERENT_SLOT);
            assertThat(result.get(i).differsBy()).containsExactly(DiffersBy.REGION);
        }
        // The stay-home pick has no event and a null region (!= head's region "B").
        assertThat(result.get(6).relationship()).isEqualTo(Relationship.DIFFERENT_SLOT);
        assertThat(result.get(6).differsBy()).containsExactly(DiffersBy.REGION);
    }
}
