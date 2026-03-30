package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BriefingVerdictEvaluator}.
 */
class BriefingVerdictEvaluatorTest {

    private final BriefingVerdictEvaluator evaluator = new BriefingVerdictEvaluator();

    // ── Verdict determination ──

    @Nested
    @DisplayName("Verdict determination")
    class VerdictTests {

        @Test
        @DisplayName("GO when all metrics are clear")
        void go_allClear() {
            assertThat(evaluator.determineVerdict(20, new BigDecimal("0.0"), 15000, 70))
                    .isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("STANDDOWN when low cloud > 80%")
        void standdown_highCloud() {
            assertThat(evaluator.determineVerdict(85, BigDecimal.ZERO, 15000, 70))
                    .isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("STANDDOWN when precip > 2mm")
        void standdown_heavyRain() {
            assertThat(evaluator.determineVerdict(20, new BigDecimal("3.5"), 15000, 70))
                    .isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("STANDDOWN when visibility < 5000m")
        void standdown_poorVisibility() {
            assertThat(evaluator.determineVerdict(20, BigDecimal.ZERO, 3000, 70))
                    .isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("MARGINAL when low cloud 50-80%")
        void marginal_partialCloud() {
            assertThat(evaluator.determineVerdict(65, BigDecimal.ZERO, 15000, 70))
                    .isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("MARGINAL when precip 0.5-2mm")
        void marginal_lightRain() {
            assertThat(evaluator.determineVerdict(20, new BigDecimal("1.0"), 15000, 70))
                    .isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("MARGINAL when visibility 5000-10000m")
        void marginal_reducedVisibility() {
            assertThat(evaluator.determineVerdict(20, BigDecimal.ZERO, 7000, 70))
                    .isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("MARGINAL when humidity > 90%")
        void marginal_mistRisk() {
            assertThat(evaluator.determineVerdict(20, BigDecimal.ZERO, 15000, 95))
                    .isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("STANDDOWN takes precedence over MARGINAL")
        void standdown_precedence() {
            assertThat(evaluator.determineVerdict(85, new BigDecimal("1.0"), 7000, 95))
                    .isEqualTo(Verdict.STANDDOWN);
        }
    }

    // ── Region rollup ──

    @Nested
    @DisplayName("Region verdict rollup")
    class RollupTests {

        @Test
        @DisplayName("GO when majority of slots are GO")
        void rollup_majorityGo() {
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.GO),
                    slot("B", Verdict.GO),
                    slot("C", Verdict.STANDDOWN));
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("STANDDOWN when majority of slots are STANDDOWN")
        void rollup_majorityStanddown() {
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.STANDDOWN),
                    slot("B", Verdict.STANDDOWN),
                    slot("C", Verdict.GO));
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("MARGINAL when mixed")
        void rollup_mixed() {
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.GO),
                    slot("B", Verdict.MARGINAL),
                    slot("C", Verdict.STANDDOWN));
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("MARGINAL for empty slots")
        void rollup_empty() {
            assertThat(evaluator.rollUpVerdict(List.of())).isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("MARGINAL when all slots are MARGINAL")
        void rollup_allMarginal() {
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.MARGINAL),
                    slot("B", Verdict.MARGINAL));
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.MARGINAL);
        }
    }

    // ── Tide highlights ──

    @Nested
    @DisplayName("Tide highlights")
    class TideTests {

        @Test
        @DisplayName("Statistical king tide highlighted as Extra Extra High")
        void statKingTide() {
            BriefingSlot s = new BriefingSlot("Bamburgh",
                    LocalDateTime.of(2026, 3, 25, 5, 47), Verdict.GO,
                    new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                            8.0, null, null, BigDecimal.ONE),
                    new BriefingSlot.TideInfo("HIGH", true,
                            LocalDateTime.of(2026, 3, 25, 6, 15), new BigDecimal("1.85"),
                            true, false, null, null, null),
                    List.of());
            assertThat(evaluator.buildTideHighlights(List.of(s)))
                    .containsExactly("Extra Extra High at Bamburgh");
        }

        @Test
        @DisplayName("Statistical spring tide highlighted as Extra High")
        void statSpringTide() {
            BriefingSlot s = new BriefingSlot("Seahouses",
                    LocalDateTime.of(2026, 3, 25, 5, 47), Verdict.GO,
                    new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                            8.0, null, null, BigDecimal.ONE),
                    new BriefingSlot.TideInfo("HIGH", true, null, null, false, true,
                            null, null, null),
                    List.of());
            assertThat(evaluator.buildTideHighlights(List.of(s)))
                    .containsExactly("Extra High at Seahouses");
        }

        @Test
        @DisplayName("Lunar spring tide highlighted")
        void lunarSpringTide() {
            BriefingSlot s = new BriefingSlot("Bamburgh",
                    LocalDateTime.of(2026, 3, 25, 5, 47), Verdict.GO,
                    new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                            8.0, null, null, BigDecimal.ONE),
                    new BriefingSlot.TideInfo("HIGH", true, null, null, false, false,
                            LunarTideType.SPRING_TIDE, "Full Moon", false),
                    List.of());
            assertThat(evaluator.buildTideHighlights(List.of(s)))
                    .containsExactly("Spring Tide at Bamburgh");
        }

        @Test
        @DisplayName("Combined lunar king + statistical extra extra high")
        void combinedKingAndStatKing() {
            BriefingSlot s = new BriefingSlot("Bamburgh",
                    LocalDateTime.of(2026, 3, 25, 5, 47), Verdict.GO,
                    new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                            8.0, null, null, BigDecimal.ONE),
                    new BriefingSlot.TideInfo("HIGH", true, null, null, true, false,
                            LunarTideType.KING_TIDE, "New Moon", true),
                    List.of());
            assertThat(evaluator.buildTideHighlights(List.of(s)))
                    .containsExactly("King Tide, Extra Extra High at Bamburgh");
        }

        @Test
        @DisplayName("No highlights for inland location")
        void noTide() {
            assertThat(evaluator.buildTideHighlights(List.of(slot("Durham", Verdict.GO)))).isEmpty();
        }
    }

    // ── Flag generation ──

    @Nested
    @DisplayName("Flag generation")
    class FlagTests {

        private static final BriefingVerdictEvaluator.TideContext NO_TIDE =
                new BriefingVerdictEvaluator.TideContext(null, false, false, false, null, false);

        @Test
        @DisplayName("Sun blocked flag for cloud > 80%")
        void sunBlocked() {
            assertThat(evaluator.buildFlags(
                    new BriefingVerdictEvaluator.WeatherMetrics(85, BigDecimal.ZERO, 15000, 70),
                    NO_TIDE)).contains("Sun blocked");
        }

        @Test
        @DisplayName("Active rain flag for precip > 2mm")
        void activeRain() {
            assertThat(evaluator.buildFlags(
                    new BriefingVerdictEvaluator.WeatherMetrics(20, new BigDecimal("3.0"), 15000, 70),
                    NO_TIDE)).contains("Active rain");
        }

        @Test
        @DisplayName("No flags when all clear")
        void noFlags() {
            assertThat(evaluator.buildFlags(
                    new BriefingVerdictEvaluator.WeatherMetrics(20, BigDecimal.ZERO, 15000, 70),
                    NO_TIDE)).isEmpty();
        }

        @Test
        @DisplayName("Multiple flags accumulate")
        void multipleFlags() {
            assertThat(evaluator.buildFlags(
                    new BriefingVerdictEvaluator.WeatherMetrics(85, new BigDecimal("5.0"), 3000, 95),
                    new BriefingVerdictEvaluator.TideContext("HIGH", true, true, false,
                            LunarTideType.KING_TIDE, false)))
                    .containsExactly("Sun blocked", "Active rain", "Poor visibility",
                            "Mist risk", "King Tide, Extra Extra High", "Tide aligned");
        }

        @Test
        @DisplayName("Tide not aligned flag when tidesNotAligned=true")
        void tideNotAligned() {
            List<String> flags = evaluator.buildFlags(
                    new BriefingVerdictEvaluator.WeatherMetrics(20, BigDecimal.ZERO, 15000, 70),
                    new BriefingVerdictEvaluator.TideContext("LOW", false, false, false, null, true));
            assertThat(flags).contains("Tide not aligned");
            assertThat(flags).doesNotContain("Tide aligned");
        }

        @Test
        @DisplayName("Tide aligned and Tide not aligned are mutually exclusive")
        void tideAlignedAndNotAlignedMutuallyExclusive() {
            var weather = new BriefingVerdictEvaluator.WeatherMetrics(
                    20, BigDecimal.ZERO, 15000, 70);
            List<String> aligned = evaluator.buildFlags(weather,
                    new BriefingVerdictEvaluator.TideContext("HIGH", true, false, false, null, false));
            List<String> notAligned = evaluator.buildFlags(weather,
                    new BriefingVerdictEvaluator.TideContext("LOW", false, false, false, null, true));
            assertThat(aligned).contains("Tide aligned").doesNotContain("Tide not aligned");
            assertThat(notAligned).contains("Tide not aligned").doesNotContain("Tide aligned");
        }

        @Test
        @DisplayName("Combined label for all 9 lunar × statistical combinations")
        void allNineCombinations() {
            var weather = new BriefingVerdictEvaluator.WeatherMetrics(
                    20, BigDecimal.ZERO, 15000, 70);

            // KING_TIDE × statKing → "King Tide, Extra Extra High"
            assertThat(evaluator.buildFlags(weather,
                    new BriefingVerdictEvaluator.TideContext("HIGH", true, true, false,
                            LunarTideType.KING_TIDE, false)))
                    .contains("King Tide, Extra Extra High");

            // KING_TIDE × statSpring → "King Tide, Extra High"
            assertThat(evaluator.buildFlags(weather,
                    new BriefingVerdictEvaluator.TideContext("HIGH", true, false, true,
                            LunarTideType.KING_TIDE, false)))
                    .contains("King Tide, Extra High");

            // KING_TIDE × none → "King Tide"
            assertThat(evaluator.buildFlags(weather,
                    new BriefingVerdictEvaluator.TideContext("HIGH", true, false, false,
                            LunarTideType.KING_TIDE, false)))
                    .contains("King Tide");

            // SPRING_TIDE × statKing → "Spring Tide, Extra Extra High"
            assertThat(evaluator.buildFlags(weather,
                    new BriefingVerdictEvaluator.TideContext("HIGH", true, true, false,
                            LunarTideType.SPRING_TIDE, false)))
                    .contains("Spring Tide, Extra Extra High");

            // SPRING_TIDE × statSpring → "Spring Tide, Extra High"
            assertThat(evaluator.buildFlags(weather,
                    new BriefingVerdictEvaluator.TideContext("HIGH", true, false, true,
                            LunarTideType.SPRING_TIDE, false)))
                    .contains("Spring Tide, Extra High");

            // SPRING_TIDE × none → "Spring Tide"
            assertThat(evaluator.buildFlags(weather,
                    new BriefingVerdictEvaluator.TideContext("HIGH", true, false, false,
                            LunarTideType.SPRING_TIDE, false)))
                    .contains("Spring Tide");

            // REGULAR × statKing → "Extra Extra High"
            assertThat(evaluator.buildFlags(weather,
                    new BriefingVerdictEvaluator.TideContext("HIGH", true, true, false,
                            LunarTideType.REGULAR_TIDE, false)))
                    .contains("Extra Extra High");

            // REGULAR × statSpring → "Extra High"
            assertThat(evaluator.buildFlags(weather,
                    new BriefingVerdictEvaluator.TideContext("HIGH", true, false, true,
                            LunarTideType.REGULAR_TIDE, false)))
                    .contains("Extra High");

            // REGULAR × none → no tide classification flag (only "Tide aligned" remains)
            List<String> regularNone = evaluator.buildFlags(weather,
                    new BriefingVerdictEvaluator.TideContext("HIGH", true, false, false,
                            LunarTideType.REGULAR_TIDE, false));
            assertThat(regularNone)
                    .noneMatch(f -> f.contains("King") || f.contains("Spring")
                            || f.contains("Extra"))
                    .contains("Tide aligned");
        }
    }

    // ── Region summary text ──

    @Nested
    @DisplayName("Region summary text")
    class SummaryTextTests {

        @Test
        @DisplayName("GO summary includes location count")
        void goSummary() {
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.GO), slot("B", Verdict.GO),
                    slot("C", Verdict.GO), slot("D", Verdict.MARGINAL));
            assertThat(evaluator.buildRegionSummary(Verdict.GO, slots, List.of()))
                    .startsWith("Clear at 3 of 4");
        }

        @Test
        @DisplayName("STANDDOWN summary for all-standdown region")
        void standdownSummary() {
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.STANDDOWN),
                    slot("B", Verdict.STANDDOWN));
            assertThat(evaluator.buildRegionSummary(Verdict.STANDDOWN, slots, List.of()))
                    .contains("Heavy cloud and rain across all 2 locations");
        }

        @Test
        @DisplayName("Tide highlights appended to summary")
        void tideInSummary() {
            List<BriefingSlot> slots = List.of(slot("Bamburgh", Verdict.GO));
            assertThat(evaluator.buildRegionSummary(Verdict.GO, slots,
                    List.of("King Tide, Extra Extra High at Bamburgh")))
                    .contains("king tide, extra extra high at bamburgh");
        }
    }

    // ── Helpers ──

    private static BriefingSlot slot(String name, Verdict verdict) {
        return new BriefingSlot(name,
                LocalDateTime.of(2026, 3, 25, 18, 0), verdict,
                new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                        8.0, null, null, BigDecimal.ONE),
                BriefingSlot.TideInfo.NONE, List.of());
    }
}
