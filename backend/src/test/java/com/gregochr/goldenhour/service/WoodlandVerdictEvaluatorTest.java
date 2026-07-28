package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WoodlandVerdictEvaluator}.
 *
 * <p>The tests that matter most are the ones asserting the <em>inversion</em>: conditions the sky
 * evaluator stands down on must read GO here, and vice versa. Those are the reason the class
 * exists, so they are pinned against {@link BriefingVerdictEvaluator}'s real thresholds rather
 * than against numbers restated here.
 */
@DisplayName("WoodlandVerdictEvaluator")
class WoodlandVerdictEvaluatorTest {

    private final WoodlandVerdictEvaluator evaluator = new WoodlandVerdictEvaluator();
    private final BriefingVerdictEvaluator skyEvaluator = new BriefingVerdictEvaluator();

    private static WoodlandVerdictEvaluator.WoodlandMetrics metrics(
            int lowCloud, int midCloud, int visibility, String precip, String wind) {
        return new WoodlandVerdictEvaluator.WoodlandMetrics(
                lowCloud, midCloud, visibility, new BigDecimal(precip), new BigDecimal(wind));
    }

    /** The canonical good woodland morning: heavy cloud, mist, calm, dry. */
    private static WoodlandVerdictEvaluator.WoodlandMetrics goodMorning() {
        return metrics(100, 90, 3000, "0.0", "1.5");
    }

    @Nested
    @DisplayName("The inversion — the whole reason this class is separate")
    class Inversion {

        @Test
        @DisplayName("The morning the sky evaluator stands down on is a woodland GO")
        void skyStanddownIsWoodlandGo() {
            var m = goodMorning();

            // Pinned against the real sky evaluator, not a restated number: if someone relaxes
            // CLOUD_STANDDOWN the premise of this class changes and this test should be revisited.
            Verdict skyVerdict = skyEvaluator.determineVerdict(
                    m.lowCloudPercent(), m.precipitationMm(), m.visibilityMetres(), 95);
            assertThat(skyVerdict).as("premise: the sky pipeline rejects this morning")
                    .isEqualTo(Verdict.STANDDOWN);

            assertThat(evaluator.determineVerdict(m)).isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("A clear dry day — a decent sky prospect — is a woodland stand-down")
        void clearDayIsWoodlandStanddown() {
            var m = metrics(5, 5, 30000, "0.0", "2.0");

            assertThat(evaluator.determineVerdict(m)).isEqualTo(Verdict.STANDDOWN);
            assertThat(evaluator.deriveStanddownReason(m)).contains("Harsh dapple");
        }

        @Test
        @DisplayName("Mist is named as the draw, not as a risk")
        void mistIsAnAssetNotARisk() {
            assertThat(evaluator.buildFlags(goodMorning()))
                    .contains("Mist", "Soft even light")
                    .doesNotContain("Mist risk", "Poor visibility", "Heavy cloud");
        }
    }

    @Nested
    @DisplayName("Light")
    class Light {

        @Test
        @DisplayName("Diffuse cover at or above 70% is GO even with no mist at all")
        void diffuseCoverAloneIsEnough() {
            assertThat(evaluator.determineVerdict(metrics(50, 30, 30000, "0.0", "1.0")))
                    .isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("Working mist carries a partly-clear morning on its own")
        void mistCarriesAPartlyClearMorning() {
            assertThat(evaluator.determineVerdict(metrics(10, 10, 2000, "0.0", "1.0")))
                    .isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("The 30-70% broken band is MARGINAL — dapple, the hardest light a wood gets")
        void brokenCloudIsMarginal() {
            assertThat(evaluator.determineVerdict(metrics(25, 25, 30000, "0.0", "1.0")))
                    .isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("High cloud does not count toward diffuse cover — it does not soften at ground level")
        void highCloudDoesNotSoften() {
            // 10 low + 10 mid + (any amount of high) must stay in the harsh band.
            var m = metrics(10, 10, 30000, "0.0", "1.0");
            assertThat(m.diffuseCover()).isEqualTo(20);
            assertThat(evaluator.determineVerdict(m)).isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("Diffuse cover is capped at 100, so 90+90 does not overflow the band logic")
        void diffuseCoverIsCapped() {
            assertThat(metrics(90, 90, 3000, "0.0", "1.0").diffuseCover()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("Mist thresholds")
    class Mist {

        @Test
        @DisplayName("Fog too thick to resolve anything is MARGINAL, never GO")
        void fogTooThickIsNeverGo() {
            // Heavy cover would otherwise make this a GO; the fog veto has to outrank it.
            var m = metrics(100, 100, 50, "0.0", "1.0");
            assertThat(evaluator.determineVerdict(m)).isEqualTo(Verdict.MARGINAL);
            assertThat(evaluator.buildFlags(m)).contains("Fog too thick").doesNotContain("Mist");
        }

        @Test
        @DisplayName("Mist band boundaries are inclusive at the bottom and exclusive at the top")
        void mistBandBoundaries() {
            assertThat(metrics(0, 0, 100, "0.0", "1.0").hasWorkingMist())
                    .as("100 m is workable").isTrue();
            assertThat(metrics(0, 0, 99, "0.0", "1.0").hasWorkingMist())
                    .as("99 m is too thick").isFalse();
            assertThat(metrics(0, 0, 4999, "0.0", "1.0").hasWorkingMist())
                    .as("just under 5 km still counts").isTrue();
            assertThat(metrics(0, 0, 5000, "0.0", "1.0").hasWorkingMist())
                    .as("5 km is no longer mist").isFalse();
        }
    }

    @Nested
    @DisplayName("Safety and comfort")
    class SafetyAndComfort {

        @Test
        @DisplayName("Dangerous gusts stand down regardless of perfect light")
        void unsafeWindOutranksEverything() {
            var m = metrics(100, 90, 3000, "0.0", "20.0");
            assertThat(evaluator.determineVerdict(m)).isEqualTo(Verdict.STANDDOWN);
            assertThat(evaluator.deriveStanddownReason(m)).isEqualTo("Unsafe — falling branches");
        }

        @Test
        @DisplayName("The safety flag suppresses the others — nothing else matters at that point")
        void safetyFlagIsAlone() {
            assertThat(evaluator.buildFlags(metrics(100, 90, 3000, "0.0", "20.0")))
                    .containsExactly("Unsafe — falling branches");
        }

        @Test
        @DisplayName("Heavy rain stands down on comfort, even in otherwise ideal light")
        void heavyRainStandsDown() {
            var m = metrics(100, 90, 3000, "3.0", "1.0");
            assertThat(evaluator.determineVerdict(m)).isEqualTo(Verdict.STANDDOWN);
            assertThat(evaluator.deriveStanddownReason(m)).isEqualTo("Heavy rain");
        }

        @Test
        @DisplayName("Drizzle does not stand down — damp is the point")
        void drizzleIsFine() {
            assertThat(evaluator.determineVerdict(metrics(100, 90, 3000, "0.4", "1.0")))
                    .isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("A breeze short of dangerous is flagged, not vetoed")
        void breezeIsFlaggedNotVetoed() {
            var m = metrics(100, 90, 3000, "0.0", "6.0");
            assertThat(evaluator.determineVerdict(m)).isEqualTo(Verdict.GO);
            assertThat(evaluator.buildFlags(m)).contains("Breezy — long exposures off");
        }
    }

    @Nested
    @DisplayName("Null and edge handling")
    class Edges {

        @Test
        @DisplayName("Null wind and precipitation are treated as calm and dry, not as failures")
        void nullsAreBenign() {
            var m = new WoodlandVerdictEvaluator.WoodlandMetrics(100, 90, 3000, null, null);
            assertThat(evaluator.determineVerdict(m)).isEqualTo(Verdict.GO);
            assertThat(evaluator.buildFlags(m)).isNotEmpty();
        }

        @Test
        @DisplayName("A non-standdown slot has no standdown reason")
        void noReasonWhenNotStandingDown() {
            assertThat(evaluator.deriveStanddownReason(goodMorning())).isNull();
        }
    }
}
