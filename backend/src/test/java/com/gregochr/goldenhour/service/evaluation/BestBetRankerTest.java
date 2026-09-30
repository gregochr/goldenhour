package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.CandidateCoverage;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DiffersBy;
import com.gregochr.goldenhour.model.Relationship;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BestBetRanker}, focused on round 11's shared removal rule ({@link
 * BestBetRanker#afterRemoval}) and the two drop methods that now delegate to it. Pins, with exact
 * field assertions, that losing rank 1 withdraws the whole set on the advisor path exactly as it
 * does on the fallback path — the question a Codex review of round 10 raised (P1, one round after
 * #943) after finding the fallback promoting an orphaned rank 2.
 */
class BestBetRankerTest {

    private static BestBet bet(int rank, String region, String event, Relationship relationship,
            List<DiffersBy> differsBy) {
        return new BestBet(rank, "headline-" + rank, "detail-" + rank, event, region,
                Confidence.HIGH, 30, "Today", "sunset", "18:00", relationship, differsBy);
    }

    private static CandidateCoverage eligible(int rated) {
        return new CandidateCoverage(rated, 0, 4.0, true);
    }

    private static CandidateCoverage ineligible(int rated) {
        return new CandidateCoverage(rated, 0, 4.0, false);
    }

    // ── afterRemoval — the shared rule ──

    @Test
    @DisplayName("rank 1 removed, rank 2 survives — the whole set withdraws (never a promoted, "
            + "orphaned rank 2)")
    void afterRemoval_rankOneRemoved_withdrawsWholeSet() {
        BestBet rank1 = bet(1, "Northumberland", "2026-03-30_sunset", null, List.of());
        BestBet rank2 = bet(2, "The Lake District", "2026-03-30_sunset",
                Relationship.SAME_SLOT, List.of());
        List<BestBet> original = List.of(rank1, rank2);
        List<BestBet> kept = List.of(rank2);

        List<BestBet> result = BestBetRanker.afterRemoval(original, kept);

        // Pinned exactly, not just "isEmpty": a future regression that returns a singleton list
        // containing rank2 (promoted, orphaned prose and all) must show up here as a size
        // mismatch, not merely as a subtly wrong list.
        assertThat(result).isEqualTo(List.of());
    }

    @Test
    @DisplayName("rank 2 removed, rank 1 survives — rank 1 returned with every field unchanged")
    void afterRemoval_rankTwoRemoved_keepsRankOneUnchanged() {
        BestBet rank1 = bet(1, "Northumberland", "2026-03-30_sunset", null, List.of());
        BestBet rank2 = bet(2, "The Lake District", "2026-03-30_sunset",
                Relationship.SAME_SLOT, List.of());
        List<BestBet> original = List.of(rank1, rank2);
        List<BestBet> kept = List.of(rank1);

        List<BestBet> result = BestBetRanker.afterRemoval(original, kept);

        assertThat(result).hasSize(1);
        BestBet survivor = result.get(0);
        assertThat(survivor.rank()).isEqualTo(1);
        assertThat(survivor.headline()).isEqualTo("headline-1");
        assertThat(survivor.detail()).isEqualTo("detail-1");
        assertThat(survivor.event()).isEqualTo("2026-03-30_sunset");
        assertThat(survivor.region()).isEqualTo("Northumberland");
        assertThat(survivor.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(survivor.nearestDriveMinutes()).isEqualTo(30);
        assertThat(survivor.dayName()).isEqualTo("Today");
        assertThat(survivor.eventType()).isEqualTo("sunset");
        assertThat(survivor.eventTime()).isEqualTo("18:00");
        assertThat(survivor.relationship()).isNull();
        assertThat(survivor.differsBy()).isEmpty();
        assertThat(survivor).isEqualTo(rank1);
    }

    @Test
    @DisplayName("nothing removed — returned unchanged")
    void afterRemoval_nothingRemoved_returnsUnchanged() {
        BestBet rank1 = bet(1, "Northumberland", "2026-03-30_sunset", null, List.of());
        List<BestBet> original = List.of(rank1);

        assertThat(BestBetRanker.afterRemoval(original, original)).isEqualTo(original);
    }

    @Test
    @DisplayName("everything removed — empty either way")
    void afterRemoval_everythingRemoved_empty() {
        BestBet rank1 = bet(1, "Northumberland", "2026-03-30_sunset", null, List.of());
        assertThat(BestBetRanker.afterRemoval(List.of(rank1), List.of())).isEmpty();
    }

    @Test
    @DisplayName("a middle pick removed while rank 1 and a trailing pick survive — the trailing "
            + "pick's rank is renumbered but its prose and relationship are untouched")
    void afterRemoval_middlePickRemoved_renumbersOnly() {
        BestBet rank1 = bet(1, "Northumberland", "2026-03-30_sunset", null, List.of());
        BestBet rank2 = bet(2, "The Lake District", "2026-03-30_sunset",
                Relationship.SAME_SLOT, List.of());
        BestBet rank3 = bet(3, "Yorkshire Dales", "2026-04-01_sunrise",
                Relationship.DIFFERENT_SLOT, List.of(DiffersBy.DATE));
        List<BestBet> original = List.of(rank1, rank2, rank3);
        List<BestBet> kept = List.of(rank1, rank3);

        List<BestBet> result = BestBetRanker.afterRemoval(original, kept);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(rank1);
        BestBet renumbered = result.get(1);
        assertThat(renumbered.rank()).isEqualTo(2);
        assertThat(renumbered.region()).isEqualTo("Yorkshire Dales");
        assertThat(renumbered.headline()).isEqualTo("headline-3");
        assertThat(renumbered.relationship()).isEqualTo(Relationship.DIFFERENT_SLOT);
        assertThat(renumbered.differsBy()).containsExactly(DiffersBy.DATE);
    }

    // ── dropUnevaluatedPicks / dropIneligiblePicks — the two callers, pinned consistent ──

    @Test
    @DisplayName("dropUnevaluatedPicks: rank 1 has zero coverage, rank 2 has coverage — the whole "
            + "set is dropped, never a lone promoted rank 2")
    void dropUnevaluatedPicks_rankOneZeroCoverage_dropsWholeSet() {
        BestBet rank1 = bet(1, "Northumberland", "2026-03-30_sunset", null, List.of());
        BestBet rank2 = bet(2, "The Lake District", "2026-03-30_sunset",
                Relationship.SAME_SLOT, List.of());
        Map<String, CandidateCoverage> coverage = Map.of(
                "2026-03-30_sunset|Northumberland", eligible(0),
                "2026-03-30_sunset|The Lake District", eligible(5));

        assertThat(BestBetRanker.dropUnevaluatedPicks(List.of(rank1, rank2), coverage))
                .isEqualTo(List.of());
    }

    @Test
    @DisplayName("dropUnevaluatedPicks: rank 2 has zero coverage, rank 1 does not — rank 1 alone")
    void dropUnevaluatedPicks_rankTwoZeroCoverage_keepsRankOne() {
        BestBet rank1 = bet(1, "Northumberland", "2026-03-30_sunset", null, List.of());
        BestBet rank2 = bet(2, "The Lake District", "2026-03-30_sunset",
                Relationship.SAME_SLOT, List.of());
        Map<String, CandidateCoverage> coverage = Map.of(
                "2026-03-30_sunset|Northumberland", eligible(5),
                "2026-03-30_sunset|The Lake District", eligible(0));

        List<BestBet> result = BestBetRanker.dropUnevaluatedPicks(List.of(rank1, rank2), coverage);
        assertThat(result).isEqualTo(List.of(rank1));
    }

    @Test
    @DisplayName("dropIneligiblePicks: rank 1 is ineligible, rank 2 is eligible — the whole set is "
            + "dropped, never a lone promoted rank 2 (round 11, matching dropUnevaluatedPicks)")
    void dropIneligiblePicks_rankOneIneligible_dropsWholeSet() {
        BestBet rank1 = bet(1, "Northumberland", "2026-03-30_sunset", null, List.of());
        BestBet rank2 = bet(2, "The Lake District", "2026-03-30_sunset",
                Relationship.SAME_SLOT, List.of());
        Map<String, CandidateCoverage> coverage = Map.of(
                "2026-03-30_sunset|Northumberland", ineligible(1),
                "2026-03-30_sunset|The Lake District", eligible(5));

        assertThat(BestBetRanker.dropIneligiblePicks(List.of(rank1, rank2), coverage))
                .isEqualTo(List.of());
    }

    @Test
    @DisplayName("dropIneligiblePicks: rank 2 is ineligible, rank 1 is eligible — rank 1 alone, "
            + "unchanged")
    void dropIneligiblePicks_rankTwoIneligible_keepsRankOne() {
        BestBet rank1 = bet(1, "Northumberland", "2026-03-30_sunset", null, List.of());
        BestBet rank2 = bet(2, "The Lake District", "2026-03-30_sunset",
                Relationship.SAME_SLOT, List.of());
        Map<String, CandidateCoverage> coverage = Map.of(
                "2026-03-30_sunset|Northumberland", eligible(5),
                "2026-03-30_sunset|The Lake District", ineligible(1));

        List<BestBet> result = BestBetRanker.dropIneligiblePicks(List.of(rank1, rank2), coverage);
        assertThat(result).isEqualTo(List.of(rank1));
    }
}
