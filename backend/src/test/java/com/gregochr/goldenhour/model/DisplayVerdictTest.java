package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the {@link DisplayVerdict#resolve(Integer, Verdict)} resolver that unifies
 * Claude rating and triage verdict into a single display signal.
 */
class DisplayVerdictTest {

    @Nested
    class WhenClaudeRatingPresent {

        @Test
        void rating5WithStanddownTriageReturnsWorthIt() {
            assertThat(DisplayVerdict.resolve(5, Verdict.STANDDOWN))
                    .isEqualTo(DisplayVerdict.WORTH_IT);
        }

        @Test
        void rating4WithNullTriageReturnsWorthIt() {
            assertThat(DisplayVerdict.resolve(4, null))
                    .isEqualTo(DisplayVerdict.WORTH_IT);
        }

        @Test
        void rating3WithGoTriageReturnsMaybe() {
            assertThat(DisplayVerdict.resolve(3, Verdict.GO))
                    .isEqualTo(DisplayVerdict.MAYBE);
        }

        @Test
        void rating2WithGoTriageReturnsStandDown() {
            assertThat(DisplayVerdict.resolve(2, Verdict.GO))
                    .isEqualTo(DisplayVerdict.STAND_DOWN);
        }

        @Test
        void rating1WithNullTriageReturnsStandDown() {
            assertThat(DisplayVerdict.resolve(1, null))
                    .isEqualTo(DisplayVerdict.STAND_DOWN);
        }

        @Test
        void rating4IsWorthItBoundary() {
            assertThat(DisplayVerdict.resolve(4, null))
                    .isEqualTo(DisplayVerdict.WORTH_IT);
        }

        @Test
        void rating3IsMaybeBoundary() {
            assertThat(DisplayVerdict.resolve(3, null))
                    .isEqualTo(DisplayVerdict.MAYBE);
        }
    }

    @Nested
    class WhenClaudeRatingAbsent {

        @Test
        void nullRatingWithGoTriageReturnsUnrated() {
            assertThat(DisplayVerdict.resolve(null, Verdict.GO))
                    .isEqualTo(DisplayVerdict.UNRATED);
        }

        @Test
        void nullRatingWithMarginalTriageReturnsUnrated() {
            assertThat(DisplayVerdict.resolve(null, Verdict.MARGINAL))
                    .isEqualTo(DisplayVerdict.UNRATED);
        }

        @Test
        void nullRatingWithStanddownTriageReturnsStandDown() {
            assertThat(DisplayVerdict.resolve(null, Verdict.STANDDOWN))
                    .isEqualTo(DisplayVerdict.STAND_DOWN);
        }

        @Test
        void nullRatingAndNullTriageReturnsAwaiting() {
            assertThat(DisplayVerdict.resolve(null, null))
                    .isEqualTo(DisplayVerdict.AWAITING);
        }
    }

    @Nested
    class Vocabulary {

        @Test
        void unratedSitsBetweenStandDownAndAwaiting() {
            // Declared order is the order the enum is documented in; clients switch on the NAME
            // (the wire form), so this pins the name set and keeps UNRATED out of the rating bands.
            assertThat(DisplayVerdict.values()).containsExactly(
                    DisplayVerdict.WORTH_IT, DisplayVerdict.MAYBE, DisplayVerdict.STAND_DOWN,
                    DisplayVerdict.UNRATED, DisplayVerdict.AWAITING);
            assertThat(DisplayVerdict.valueOf("UNRATED")).isEqualTo(DisplayVerdict.UNRATED);
        }

        @Test
        void aRatingAlwaysBeatsTheTriageFallbackIncludingUnrated() {
            for (Verdict triage : Verdict.values()) {
                assertThat(DisplayVerdict.resolve(4, triage)).isEqualTo(DisplayVerdict.WORTH_IT);
                assertThat(DisplayVerdict.resolve(3, triage)).isEqualTo(DisplayVerdict.MAYBE);
                assertThat(DisplayVerdict.resolve(2, triage)).isEqualTo(DisplayVerdict.STAND_DOWN);
            }
        }
    }
}
