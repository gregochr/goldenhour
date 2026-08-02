package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The floor a second region must clear to be offered as "Also good".
 *
 * <p>Both terms are tested at the boundary and one either side, because both bounds are inclusive
 * and an off-by-one in either direction is invisible in the rendered card — it simply offers, or
 * fails to offer, a second region that looks plausible.
 */
class AlsoGoodFloorTest {

    @Nested
    @DisplayName("absolute floor")
    class AbsoluteFloor {

        // Chosen so the gap term cannot decide any rung: at 3.2 the gaps are 0.2 / 0.3 / 0.1, all
        // comfortably inside MAX_GAP_FROM_TOP, so only MIN_ABSOLUTE can flip these three.
        // 3.5 does NOT work and was the original mistake — it puts 3.0 exactly ON the gap bound and
        // 2.9 past it, which made the "just below" rung false for every possible MIN_ABSOLUTE and
        // left the constant free to drift down to 2.6 with the whole repo green.
        private static final double TOP = 3.2;

        @Test
        void atExactlyThreeQualifies() {
            assertThat(AlsoGoodFloor.qualifies(TOP, 3.0)).isTrue();
        }

        @Test
        void justBelowThreeDoesNot() {
            assertThat(AlsoGoodFloor.qualifies(TOP, 2.9)).isFalse();
        }

        @Test
        void justAboveThreeQualifies() {
            assertThat(AlsoGoodFloor.qualifies(TOP, 3.1)).isTrue();
        }
    }

    @Nested
    @DisplayName("gap from the top region")
    class GapFromTop {

        // Every candidate here clears 3.0, so only the gap term can decide.
        @Test
        void atExactlyHalfAStarQualifies() {
            assertThat(AlsoGoodFloor.qualifies(4.0, 3.5)).isTrue();
        }

        @Test
        void sixTenthsBehindDoesNot() {
            assertThat(AlsoGoodFloor.qualifies(4.0, 3.4)).isFalse();
        }

        @Test
        void fourTenthsBehindQualifies() {
            assertThat(AlsoGoodFloor.qualifies(4.0, 3.6)).isTrue();
        }
    }

    @Test
    @DisplayName("a candidate above the top still qualifies — ranking, not this floor, decides order")
    void candidateAboveTopQualifies() {
        // Cannot arise from the projector, which sorts first. Pinned so the guard stays a floor
        // and does not quietly become an ordering assertion.
        assertThat(AlsoGoodFloor.qualifies(3.5, 4.0)).isTrue();
    }

    @Test
    @DisplayName("a poor night promotes nothing, however close the second region is")
    void bothRegionsBelowTheFloorAreRefused() {
        // The failure this prevents: on an overcast night every region is poor and close together,
        // so a gap-only rule would offer the second-worst region as an alternative worth driving to.
        assertThat(AlsoGoodFloor.qualifies(2.4, 2.3)).isFalse();
    }
}
