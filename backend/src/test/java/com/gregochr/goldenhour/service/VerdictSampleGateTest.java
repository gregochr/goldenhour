package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link VerdictSampleGate}'s pure arithmetic — the raw sample-size test, isolated
 * from the force-evaluation exemption ({@code BriefingRegionEvaluationRollupTest} covers the
 * combined rule) and from every other rollup concern.
 */
class VerdictSampleGateTest {

    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 1, 6, 30);

    /** A rated, non-canopy voting slot. */
    private static BriefingSlot rated(String name, int rating) {
        return new BriefingSlot(name, TIME, Verdict.GO, null, BriefingSlot.TideInfo.NONE,
                List.of(), null).withClaudeScores(rating, 60, 55, "s");
    }

    /**
     * A voting slot the weather triage stood down. Carries the STANDDOWN verdict a real triaged
     * slot would, but {@link #examinedCount} must not read that field directly any more (a Codex
     * review of #943, P1-A) — the caller must ALSO pass the slot's name in the {@code
     * triagedByBatchLocationNames} set for it to count as examined, exactly as {@code
     * EvaluationViewService#loadTriagedByBatch} would report it.
     */
    private static BriefingSlot triaged(String name) {
        return new BriefingSlot(name, TIME, Verdict.STANDDOWN, null, BriefingSlot.TideInfo.NONE,
                List.of(), "Grey ceiling");
    }

    /**
     * A voting slot whose BRIEFING verdict is GO — never STANDDOWN — but whose BATCH latest
     * disposition was {@code SKIPPED_TRIAGED} regardless (the two can disagree; see {@link
     * #aSlotCountsAsExaminedByItsBatchDecision_evenWithAGoBriefingVerdict}).
     */
    private static BriefingSlot goVerdictSlot(String name) {
        return new BriefingSlot(name, TIME, Verdict.GO, null, BriefingSlot.TideInfo.NONE,
                List.of(), null);
    }

    /** A voting slot nobody has looked at yet — beyond Gate 4's horizon, no force-eval rescue. */
    private static BriefingSlot untouched(String name) {
        return new BriefingSlot(name, TIME, Verdict.GO, null, BriefingSlot.TideInfo.NONE,
                List.of(), null);
    }

    @Nested
    @DisplayName("isSufficient — the rated-count half")
    class RatedCount {

        @Test
        @DisplayName("4 rated of 4 examined, full roster — insufficient: below MIN_RATED")
        void fourRated_belowMinRated_insufficient() {
            assertThat(VerdictSampleGate.isSufficient(4, 4, 4)).isFalse();
        }

        @Test
        @DisplayName("5 rated of 5 examined, full roster — sufficient: meets MIN_RATED exactly")
        void fiveRated_atMinRated_sufficient() {
            assertThat(VerdictSampleGate.isSufficient(5, 5, 5)).isTrue();
        }
    }

    @Nested
    @DisplayName("isSufficient — the examined-coverage half, at and around the boundary")
    class ExaminedCoverage {

        @Test
        @DisplayName("even roster of 10: examined 5 (exactly half) is sufficient")
        void evenRoster_examinedExactlyHalf_sufficient() {
            // 5 rated, coverage denominator exactly met (5 >= 10 * 0.5).
            assertThat(VerdictSampleGate.isSufficient(5, 5, 10)).isTrue();
        }

        @Test
        @DisplayName("even roster of 10: examined 4 (just under half) is insufficient")
        void evenRoster_examinedJustUnderHalf_insufficient() {
            assertThat(VerdictSampleGate.isSufficient(4, 4, 10)).isFalse();
        }

        @Test
        @DisplayName("odd roster of 11: examined 6 (just over half, 5.5) is sufficient")
        void oddRoster_examinedJustOverHalf_sufficient() {
            assertThat(VerdictSampleGate.isSufficient(5, 6, 11)).isTrue();
        }

        @Test
        @DisplayName("odd roster of 11: examined 5 (just under half, 5.5) is insufficient")
        void oddRoster_examinedJustUnderHalf_insufficient() {
            assertThat(VerdictSampleGate.isSufficient(5, 5, 11)).isFalse();
        }

        @Test
        @DisplayName("the production near-window shape: 15 rated + 35 triaged of 50 is sufficient")
        void productionShape_fiftyVotingSlots_fifteenRatedThirtyFiveTriaged_sufficient() {
            // 15 rated, 35 triaged => 50 examined of 50 voting — the poor-weather near window the
            // rule must NOT touch: the region has been looked at in full, and its ratings keep
            // deciding its verdict exactly as before this gate existed.
            assertThat(VerdictSampleGate.isSufficient(15, 50, 50)).isTrue();
        }
    }

    @Nested
    @DisplayName("examinedCount — rated plus BATCH-triaged, never the untouched, never the "
            + "briefing's own weather-triage Verdict")
    class ExaminedCount {

        @Test
        @DisplayName("35 batch-triaged + 15 rated of 50 voting slots examines all 50")
        void fiftyVotingSlots_thirtyFiveTriagedFifteenRated_examinesFifty() {
            List<BriefingSlot> slots = new ArrayList<>();
            Set<String> triagedNames = new HashSet<>();
            for (int i = 0; i < 35; i++) {
                String name = "Triaged" + i;
                slots.add(triaged(name));
                triagedNames.add(name);
            }
            for (int i = 0; i < 15; i++) {
                slots.add(rated("Rated" + i, 3));
            }

            int rated = 15;
            assertThat(VerdictSampleGate.examinedCount(slots, rated, triagedNames)).isEqualTo(50);
        }

        @Test
        @DisplayName("an untouched (never-triaged, never-rated) slot contributes nothing")
        void untouchedSlot_contributesNothing() {
            List<BriefingSlot> slots = List.of(
                    rated("Rated", 4), triaged("Triaged"), untouched("Untouched"));

            assertThat(VerdictSampleGate.examinedCount(slots, 1, Set.of("Triaged")))
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("six force-evaluated ratings alone in a roster of fifty examine only six — "
                + "the gate still bites on the raw count without the exemption")
        void sixRatedAloneInFiftyVotingSlots_examinesOnlySix() {
            List<BriefingSlot> slots = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                slots.add(rated("Forced" + i, 4));
            }
            for (int i = 0; i < 44; i++) {
                slots.add(untouched("Untouched" + i));
            }

            int examined = VerdictSampleGate.examinedCount(slots, 6, Set.of());

            assertThat(examined).isEqualTo(6);
            assertThat(VerdictSampleGate.isSufficient(6, examined, 50)).isFalse();
        }

        @Test
        @DisplayName("an empty voting slot list examines exactly the rated count handed in")
        void emptyVotingSlots_examinesRatedCountAlone() {
            assertThat(VerdictSampleGate.examinedCount(List.of(), 0, Set.of())).isEqualTo(0);
        }

        @Test
        @DisplayName("a slot whose BRIEFING verdict is STANDDOWN but whose BATCH never named it "
                + "as triaged (e.g. SKIPPED_STABILITY) does NOT count as examined — the whole "
                + "point of #943's P1-A fix")
        void standdownVerdictSlot_notInTriagedSet_doesNotCountAsExamined() {
            // The slot LOOKS exactly like a real triaged slot (STANDDOWN verdict, a stand-down
            // reason) — the only difference from a genuinely examined one is that its name is
            // absent from triagedByBatchLocationNames, exactly what EvaluationViewService#
            // loadTriagedByBatch reports when the batch's latest non-cached disposition for it was
            // SKIPPED_STABILITY (or anything else that is not SKIPPED_TRIAGED) rather than
            // SKIPPED_TRIAGED. Reading slot.verdict() instead of this set is the exact bug fixed.
            List<BriefingSlot> slots = List.of(rated("Rated", 4), triaged("StabilitySkipped"));

            assertThat(VerdictSampleGate.examinedCount(slots, 1, Set.of())).isEqualTo(1);
        }

        @Test
        @DisplayName("a slot whose BRIEFING verdict is GO still counts as examined when the BATCH "
                + "named it triaged — the two axes are independent")
        void aSlotCountsAsExaminedByItsBatchDecision_evenWithAGoBriefingVerdict() {
            // The mirror image of the test above: a GO-verdict slot the batch nonetheless decided
            // SKIPPED_TRIAGED for (a stale briefing verdict computed before the batch's own,
            // independent triage ran) must still count — examinedCount takes the batch's word, not
            // the slot's own verdict field, in either direction.
            List<BriefingSlot> slots = List.of(rated("Rated", 4), goVerdictSlot("GoButTriaged"));

            assertThat(VerdictSampleGate.examinedCount(slots, 1, Set.of("GoButTriaged")))
                    .isEqualTo(2);
        }
    }
}
