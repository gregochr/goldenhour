package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    /** A voting slot the weather triage stood down — examined, but never rated. */
    private static BriefingSlot triaged(String name) {
        return new BriefingSlot(name, TIME, Verdict.STANDDOWN, null, BriefingSlot.TideInfo.NONE,
                List.of(), "Grey ceiling");
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
    @DisplayName("examinedCount — rated plus triaged, never the untouched")
    class ExaminedCount {

        @Test
        @DisplayName("35 triaged + 15 rated of 50 voting slots examines all 50")
        void fiftyVotingSlots_thirtyFiveTriagedFifteenRated_examinesFifty() {
            List<BriefingSlot> slots = new ArrayList<>();
            for (int i = 0; i < 35; i++) {
                slots.add(triaged("Triaged" + i));
            }
            for (int i = 0; i < 15; i++) {
                slots.add(rated("Rated" + i, 3));
            }

            int rated = 15;
            assertThat(VerdictSampleGate.examinedCount(slots, rated)).isEqualTo(50);
        }

        @Test
        @DisplayName("an untouched (never-triaged, never-rated) slot contributes nothing")
        void untouchedSlot_contributesNothing() {
            List<BriefingSlot> slots = List.of(
                    rated("Rated", 4), triaged("Triaged"), untouched("Untouched"));

            assertThat(VerdictSampleGate.examinedCount(slots, 1)).isEqualTo(2);
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

            int examined = VerdictSampleGate.examinedCount(slots, 6);

            assertThat(examined).isEqualTo(6);
            assertThat(VerdictSampleGate.isSufficient(6, examined, 50)).isFalse();
        }

        @Test
        @DisplayName("an empty voting slot list examines exactly the rated count handed in")
        void emptyVotingSlots_examinesRatedCountAlone() {
            assertThat(VerdictSampleGate.examinedCount(List.of(), 0)).isEqualTo(0);
        }
    }
}
