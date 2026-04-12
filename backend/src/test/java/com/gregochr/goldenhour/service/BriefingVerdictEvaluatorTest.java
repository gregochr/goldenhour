package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    // ── Mid-cloud demotion ──

    @Nested
    @DisplayName("Mid-cloud blanket demotion")
    class MidCloudTests {

        @Test
        @DisplayName("Mid-cloud >= 80% demotes GO to STANDDOWN")
        void midCloud80_goToStanddown() {
            assertThat(evaluator.applyMidCloudDemotion(Verdict.GO, 80))
                    .isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("Mid-cloud >= 80% demotes MARGINAL to STANDDOWN")
        void midCloud80_marginalToStanddown() {
            assertThat(evaluator.applyMidCloudDemotion(Verdict.MARGINAL, 86))
                    .isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("Mid-cloud 65% demotes GO to MARGINAL")
        void midCloud65_goToMarginal() {
            assertThat(evaluator.applyMidCloudDemotion(Verdict.GO, 65))
                    .isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("Mid-cloud 65% leaves MARGINAL unchanged")
        void midCloud65_marginalUnchanged() {
            assertThat(evaluator.applyMidCloudDemotion(Verdict.MARGINAL, 65))
                    .isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("Mid-cloud 40% leaves GO unchanged")
        void midCloud40_goUnchanged() {
            assertThat(evaluator.applyMidCloudDemotion(Verdict.GO, 40))
                    .isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("Already STANDDOWN remains STANDDOWN regardless of mid-cloud")
        void alreadyStanddown() {
            assertThat(evaluator.applyMidCloudDemotion(Verdict.STANDDOWN, 50))
                    .isEqualTo(Verdict.STANDDOWN);
        }
    }

    // ── Cloud trend demotion ──

    @Nested
    @DisplayName("Cloud trend (BUILDING) demotion")
    class CloudTrendTests {

        @Test
        @DisplayName("BUILDING trend (20->35->45) demotes GO to MARGINAL")
        void building_goToMarginal() {
            assertThat(evaluator.applyCloudTrendDemotion(Verdict.GO, List.of(20, 35, 45)))
                    .isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("CLEARING trend (50->30->15) does not demote GO")
        void clearing_goUnchanged() {
            assertThat(evaluator.applyCloudTrendDemotion(Verdict.GO, List.of(50, 30, 15)))
                    .isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("Stable low trend (15->18->12) does not demote — max below 40%")
        void stableLow_noChange() {
            assertThat(evaluator.applyCloudTrendDemotion(Verdict.GO, List.of(15, 18, 12)))
                    .isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("Building but max < 40% (10->20->30) does not demote")
        void buildingLowMax_noChange() {
            assertThat(evaluator.applyCloudTrendDemotion(Verdict.GO, List.of(10, 20, 30)))
                    .isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("Already MARGINAL not demoted further by building trend")
        void marginal_noChange() {
            assertThat(evaluator.applyCloudTrendDemotion(Verdict.MARGINAL, List.of(20, 35, 45)))
                    .isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("Already STANDDOWN not affected by building trend")
        void standdown_noChange() {
            assertThat(evaluator.applyCloudTrendDemotion(Verdict.STANDDOWN, List.of(20, 35, 45)))
                    .isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("Null trend data does not demote")
        void nullTrend_noChange() {
            assertThat(evaluator.applyCloudTrendDemotion(Verdict.GO, null))
                    .isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("Single-hour data does not demote (need at least 2 hours)")
        void singleHour_noChange() {
            assertThat(evaluator.applyCloudTrendDemotion(Verdict.GO, List.of(60)))
                    .isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("Two-hour window works when event hour near start of forecast")
        void twoHour_building() {
            assertThat(evaluator.applyCloudTrendDemotion(Verdict.GO, List.of(20, 50)))
                    .isEqualTo(Verdict.MARGINAL);
        }
    }

    // ── Flag generation for new checks ──

    @Nested
    @DisplayName("Mid-cloud and trend flags")
    class NewFlagTests {

        private static final BriefingVerdictEvaluator.TideContext NO_TIDE =
                new BriefingVerdictEvaluator.TideContext(null, false, false, false, null, false);

        @Test
        @DisplayName("Grey ceiling flag for mid-cloud >= 80%")
        void greyCeilingFlag() {
            assertThat(evaluator.buildFlags(
                    new BriefingVerdictEvaluator.WeatherMetrics(20, BigDecimal.ZERO, 15000, 70, 85, null, false),
                    NO_TIDE)).contains("Grey ceiling");
        }

        @Test
        @DisplayName("Heavy mid-cloud flag for mid-cloud 60-79%")
        void heavyMidCloudFlag() {
            assertThat(evaluator.buildFlags(
                    new BriefingVerdictEvaluator.WeatherMetrics(20, BigDecimal.ZERO, 15000, 70, 65, null, false),
                    NO_TIDE)).contains("Heavy mid-cloud");
        }

        @Test
        @DisplayName("Cloud building flag when trend detected")
        void cloudBuildingFlag() {
            assertThat(evaluator.buildFlags(
                    new BriefingVerdictEvaluator.WeatherMetrics(20, BigDecimal.ZERO, 15000, 70, null, null, true),
                    NO_TIDE)).contains("Cloud building");
        }

        @Test
        @DisplayName("No mid-cloud flag when null")
        void noFlagWhenMidCloudNull() {
            assertThat(evaluator.buildFlags(
                    new BriefingVerdictEvaluator.WeatherMetrics(20, BigDecimal.ZERO, 15000, 70, null, null, false),
                    NO_TIDE)).isEmpty();
        }
    }

    // ── Clear-sky demotion ──

    @Nested
    @DisplayName("Clear-sky (no canvas) demotion")
    class ClearSkyTests {

        @Test
        @DisplayName("All layers < 15% demotes GO to MARGINAL")
        void clearAllLayers_goToMarginal() {
            var metrics = new BriefingVerdictEvaluator.WeatherMetrics(
                    5, BigDecimal.ZERO, 15000, 70, 8, 10, false);
            assertThat(evaluator.applyClearSkyDemotion(Verdict.GO, metrics))
                    .isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("High cloud >= 15% — cirrus canvas present, no demotion")
        void highCloudCanvas_noDemotion() {
            var metrics = new BriefingVerdictEvaluator.WeatherMetrics(
                    5, BigDecimal.ZERO, 15000, 70, 8, 25, false);
            assertThat(evaluator.applyClearSkyDemotion(Verdict.GO, metrics))
                    .isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("Mid cloud >= 15% — mid canvas present, no demotion")
        void midCloudCanvas_noDemotion() {
            var metrics = new BriefingVerdictEvaluator.WeatherMetrics(
                    5, BigDecimal.ZERO, 15000, 70, 20, 10, false);
            assertThat(evaluator.applyClearSkyDemotion(Verdict.GO, metrics))
                    .isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("Already MARGINAL stays MARGINAL")
        void alreadyMarginal_noChange() {
            var metrics = new BriefingVerdictEvaluator.WeatherMetrics(
                    5, BigDecimal.ZERO, 15000, 70, 8, 10, false);
            assertThat(evaluator.applyClearSkyDemotion(Verdict.MARGINAL, metrics))
                    .isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("Already STANDDOWN stays STANDDOWN")
        void alreadyStanddown_noChange() {
            var metrics = new BriefingVerdictEvaluator.WeatherMetrics(
                    5, BigDecimal.ZERO, 15000, 70, 8, 10, false);
            assertThat(evaluator.applyClearSkyDemotion(Verdict.STANDDOWN, metrics))
                    .isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("Null mid and high treated as 0 — demotion applies")
        void nullMidHigh_treatedAsZero() {
            var metrics = new BriefingVerdictEvaluator.WeatherMetrics(
                    5, BigDecimal.ZERO, 15000, 70, null, null, false);
            assertThat(evaluator.applyClearSkyDemotion(Verdict.GO, metrics))
                    .isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("Clear all layers flag present when all < 15%")
        void clearAllLayersFlag() {
            var metrics = new BriefingVerdictEvaluator.WeatherMetrics(
                    5, BigDecimal.ZERO, 15000, 70, 8, 10, false);
            var noTide = new BriefingVerdictEvaluator.TideContext(
                    null, false, false, false, null, false);
            assertThat(evaluator.buildFlags(metrics, noTide))
                    .contains("Clear all layers");
        }

        @Test
        @DisplayName("StanddownReason.CLEAR_SKY label derivable")
        void clearSkyReasonLabel() {
            var metrics = new BriefingVerdictEvaluator.WeatherMetrics(
                    5, BigDecimal.ZERO, 15000, 70, 8, 10, false);
            assertThat(evaluator.deriveStanddownReason(metrics, false))
                    .isEqualTo("Clear sky — no canvas");
        }
    }

    // ── Horizon cloud demotion ──

    @Nested
    @DisplayName("Solar horizon low-cloud demotion")
    class HorizonCloudTests {

        @Test
        @DisplayName("Horizon >= 70% demotes GO to STANDDOWN")
        void horizon70_goToStanddown() {
            assertThat(evaluator.applyHorizonCloudDemotion(Verdict.GO, 80))
                    .isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("Horizon >= 40% demotes GO to MARGINAL")
        void horizon40_goToMarginal() {
            assertThat(evaluator.applyHorizonCloudDemotion(Verdict.GO, 50))
                    .isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("Horizon 25% — no demotion")
        void horizon25_noDemotion() {
            assertThat(evaluator.applyHorizonCloudDemotion(Verdict.GO, 25))
                    .isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("Horizon >= 70%, already STANDDOWN — no change")
        void horizon70_alreadyStanddown() {
            assertThat(evaluator.applyHorizonCloudDemotion(Verdict.STANDDOWN, 80))
                    .isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("Horizon >= 40%, already MARGINAL — no change")
        void horizon40_alreadyMarginal() {
            assertThat(evaluator.applyHorizonCloudDemotion(Verdict.MARGINAL, 50))
                    .isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("Horizon >= 70% demotes MARGINAL to STANDDOWN")
        void horizon70_marginalToStanddown() {
            assertThat(evaluator.applyHorizonCloudDemotion(Verdict.MARGINAL, 75))
                    .isEqualTo(Verdict.STANDDOWN);
        }
    }

    // ── Region rollup ──

    @Nested
    @DisplayName("Region verdict rollup — excludes STANDDOWNs")
    class RollupTests {

        @Test
        @DisplayName("GO when any viable slot is GO (STANDDOWNs excluded)")
        void rollup_goWithStanddowns() {
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.GO),
                    slot("B", Verdict.GO),
                    slot("C", Verdict.STANDDOWN));
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("GO when 1 GO + 2 STANDDOWN (STANDDOWNs no longer dominate)")
        void rollup_singleGoAmongStanddowns() {
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.STANDDOWN),
                    slot("B", Verdict.STANDDOWN),
                    slot("C", Verdict.GO));
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("GO when 1 GO + 1 MARGINAL + 1 STANDDOWN (best viable is GO)")
        void rollup_goWhenMixed() {
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.GO),
                    slot("B", Verdict.MARGINAL),
                    slot("C", Verdict.STANDDOWN));
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("STANDDOWN for empty slots — no viable locations")
        void rollup_empty() {
            assertThat(evaluator.rollUpVerdict(List.of())).isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("MARGINAL when all slots are MARGINAL")
        void rollup_allMarginal() {
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.MARGINAL),
                    slot("B", Verdict.MARGINAL));
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("GO when 1 GO + 8 STANDDOWN (no MARGINALs — 1/1 viable = 100% GO)")
        void rollup_oneGoAmongManyStanddowns() {
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.GO),
                    slot("B", Verdict.STANDDOWN),
                    slot("C", Verdict.STANDDOWN),
                    slot("D", Verdict.STANDDOWN),
                    slot("E", Verdict.STANDDOWN),
                    slot("F", Verdict.STANDDOWN),
                    slot("G", Verdict.STANDDOWN),
                    slot("H", Verdict.STANDDOWN),
                    slot("I", Verdict.STANDDOWN));
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("MARGINAL when 3 MARGINAL + 5 STANDDOWN (3/8 = 37.5% viable — above 20%)")
        void rollup_marginalAmongStanddowns() {
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.MARGINAL),
                    slot("B", Verdict.MARGINAL),
                    slot("C", Verdict.MARGINAL),
                    slot("D", Verdict.STANDDOWN),
                    slot("E", Verdict.STANDDOWN),
                    slot("F", Verdict.STANDDOWN),
                    slot("G", Verdict.STANDDOWN),
                    slot("H", Verdict.STANDDOWN));
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("STANDDOWN when all slots are STANDDOWN")
        void rollup_allStanddown() {
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.STANDDOWN),
                    slot("B", Verdict.STANDDOWN),
                    slot("C", Verdict.STANDDOWN),
                    slot("D", Verdict.STANDDOWN),
                    slot("E", Verdict.STANDDOWN),
                    slot("F", Verdict.STANDDOWN),
                    slot("G", Verdict.STANDDOWN));
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.STANDDOWN);
        }

        // ── 20% threshold cases ──

        @Test
        @DisplayName("MAYBE — Northumberland clear-sky: 1 GO + 8 MARGINAL + 33 STANDDOWN")
        void rollup_northumberlandClearSky() {
            // 1/9 = 11% GO (below 20%); 9/42 = 21% viable (above 20%) → MARGINAL
            List<BriefingSlot> slots = slots(1, 8, 33);
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("WORTH IT — Northumberland good: 12 GO + 8 MARGINAL + 22 STANDDOWN")
        void rollup_northumberlandGoodConditions() {
            // 12/20 = 60% GO (above 20%) → GO
            List<BriefingSlot> slots = slots(12, 8, 22);
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("WORTH IT — Teesdale small: 1 GO + 1 MARGINAL + 2 STANDDOWN (50% GO)")
        void rollup_teedsdaleSingleGo() {
            // 1/2 = 50% GO (above 20%) → GO — small region, single GO is meaningful
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.GO),
                    slot("B", Verdict.MARGINAL),
                    slot("C", Verdict.STANDDOWN),
                    slot("D", Verdict.STANDDOWN));
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("MAYBE — N Yorks Coast: 2 GO + 18 MARGINAL + 4 STANDDOWN")
        void rollup_northYorksCoastMostlyMarginal() {
            // 2/20 = 10% GO (below 20%); 20/24 = 83% viable (above 20%) → MARGINAL
            List<BriefingSlot> slots = slots(2, 18, 4);
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("STANDDOWN — Lake District: 54 STANDDOWN")
        void rollup_lakeDistrictAllStanddown() {
            List<BriefingSlot> slots = slots(0, 0, 54);
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("STANDDOWN — 5 MARGINAL out of 42 (5/42 = 12% viable — below 20%)")
        void rollup_fewMarginalInLargeRegion() {
            List<BriefingSlot> slots = slots(0, 5, 37);
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("MAYBE — 10 MARGINAL out of 42 (10/42 = 24% viable — above 20%)")
        void rollup_tenMarginalInLargeRegion() {
            List<BriefingSlot> slots = slots(0, 10, 32);
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("GO boundary — exactly 20% GO (8 GO + 32 MARGINAL = 20% GO)")
        void rollup_exactlyTwentyPercentGo() {
            List<BriefingSlot> slots = slots(8, 32, 0);
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("MAYBE — just below GO boundary (7 GO + 33 MARGINAL = 17.5% GO)")
        void rollup_justBelowTwentyPercentGo() {
            List<BriefingSlot> slots = slots(7, 33, 0);
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("MAYBE — exactly 20% viable boundary (0 GO + 5 MARGINAL + 20 STANDDOWN = 5/25 = 20%)")
        void rollup_exactlyTwentyPercentViable() {
            // 5*100=500 >= 25*20=500: the >= boundary — mutating to > would yield STANDDOWN
            List<BriefingSlot> slots = slots(0, 5, 20);
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("STANDDOWN — just below 20% viable (0 GO + 4 MARGINAL + 21 STANDDOWN = 4/25 = 16%)")
        void rollup_justBelowTwentyPercentViable() {
            // 4*100=400 < 25*20=500: one slot below the boundary
            List<BriefingSlot> slots = slots(0, 4, 21);
            assertThat(evaluator.rollUpVerdict(slots)).isEqualTo(Verdict.STANDDOWN);
        }
    }

    // ── Standdown reason derivation ──

    @Nested
    @DisplayName("Standdown reason derivation")
    class StanddownReasonTests {

        @Test
        @DisplayName("Heavy cloud when low cloud > 80%")
        void heavyCloud() {
            assertThat(evaluator.deriveStanddownReason(
                    new BriefingVerdictEvaluator.WeatherMetrics(
                            85, BigDecimal.ZERO, 15000, 70, null, null, false),
                    false)).isEqualTo("Heavy cloud");
        }

        @Test
        @DisplayName("Overcast when mid-cloud >= 80%")
        void overcast() {
            assertThat(evaluator.deriveStanddownReason(
                    new BriefingVerdictEvaluator.WeatherMetrics(
                            30, BigDecimal.ZERO, 15000, 70, 85, null, false),
                    false)).isEqualTo("Overcast");
        }

        @Test
        @DisplayName("Rain when precip > 2mm")
        void rain() {
            assertThat(evaluator.deriveStanddownReason(
                    new BriefingVerdictEvaluator.WeatherMetrics(
                            30, new BigDecimal("3.0"), 15000, 70, null, null, false),
                    false)).isEqualTo("Rain");
        }

        @Test
        @DisplayName("Poor visibility when below 5000m")
        void poorVisibility() {
            assertThat(evaluator.deriveStanddownReason(
                    new BriefingVerdictEvaluator.WeatherMetrics(
                            30, BigDecimal.ZERO, 3000, 70, null, null, false),
                    false)).isEqualTo("Poor visibility");
        }

        @Test
        @DisplayName("Building cloud when trend detected")
        void buildingCloud() {
            assertThat(evaluator.deriveStanddownReason(
                    new BriefingVerdictEvaluator.WeatherMetrics(
                            30, BigDecimal.ZERO, 15000, 70, null, null, true),
                    false)).isEqualTo("Building cloud");
        }

        @Test
        @DisplayName("Tide mismatch when tides not aligned")
        void tideMismatch() {
            assertThat(evaluator.deriveStanddownReason(
                    new BriefingVerdictEvaluator.WeatherMetrics(
                            30, BigDecimal.ZERO, 15000, 70, 50, 50, false),
                    true)).isEqualTo("Tide mismatch");
        }

        @Test
        @DisplayName("Fallback to 'Poor conditions' when no specific reason")
        void fallback() {
            assertThat(evaluator.deriveStanddownReason(
                    new BriefingVerdictEvaluator.WeatherMetrics(
                            30, BigDecimal.ZERO, 15000, 70, 50, 50, false),
                    false)).isEqualTo("Poor conditions");
        }

        @Test
        @DisplayName("Priority: heavy cloud takes precedence over rain")
        void priority_cloudOverRain() {
            assertThat(evaluator.deriveStanddownReason(
                    new BriefingVerdictEvaluator.WeatherMetrics(
                            85, new BigDecimal("5.0"), 3000, 70, 90, null, true),
                    true)).isEqualTo("Heavy cloud");
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
                            8.0, null, null, BigDecimal.ONE, 0, 0),
                    new BriefingSlot.TideInfo("HIGH", true,
                            LocalDateTime.of(2026, 3, 25, 6, 15), new BigDecimal("1.85"),
                            true, false, null, null, null),
                    List.of(), null);
            assertThat(evaluator.buildTideHighlights(List.of(s)))
                    .containsExactly("Extra Extra High at 1 coastal spot");
        }

        @Test
        @DisplayName("Statistical spring tide highlighted as Extra High")
        void statSpringTide() {
            BriefingSlot s = new BriefingSlot("Seahouses",
                    LocalDateTime.of(2026, 3, 25, 5, 47), Verdict.GO,
                    new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                            8.0, null, null, BigDecimal.ONE, 0, 0),
                    new BriefingSlot.TideInfo("HIGH", true, null, null, false, true,
                            null, null, null),
                    List.of(), null);
            assertThat(evaluator.buildTideHighlights(List.of(s)))
                    .containsExactly("Extra High at 1 coastal spot");
        }

        @Test
        @DisplayName("Lunar spring tide highlighted")
        void lunarSpringTide() {
            BriefingSlot s = new BriefingSlot("Bamburgh",
                    LocalDateTime.of(2026, 3, 25, 5, 47), Verdict.GO,
                    new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                            8.0, null, null, BigDecimal.ONE, 0, 0),
                    new BriefingSlot.TideInfo("HIGH", true, null, null, false, false,
                            LunarTideType.SPRING_TIDE, "Full Moon", false),
                    List.of(), null);
            assertThat(evaluator.buildTideHighlights(List.of(s)))
                    .containsExactly("Spring Tide at 1 coastal spot");
        }

        @Test
        @DisplayName("Combined lunar king + statistical extra extra high")
        void combinedKingAndStatKing() {
            BriefingSlot s = new BriefingSlot("Bamburgh",
                    LocalDateTime.of(2026, 3, 25, 5, 47), Verdict.GO,
                    new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                            8.0, null, null, BigDecimal.ONE, 0, 0),
                    new BriefingSlot.TideInfo("HIGH", true, null, null, true, false,
                            LunarTideType.KING_TIDE, "New Moon", true),
                    List.of(), null);
            assertThat(evaluator.buildTideHighlights(List.of(s)))
                    .containsExactly("King Tide, Extra Extra High at 1 coastal spot");
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
        @DisplayName("No flags when conditions are good with some cloud canvas")
        void noFlags() {
            assertThat(evaluator.buildFlags(
                    new BriefingVerdictEvaluator.WeatherMetrics(
                            20, BigDecimal.ZERO, 15000, 70, 30, 25, false),
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
                    List.of("King Tide, Extra Extra High at 3 coastal spots")))
                    .contains("king tide, extra extra high at 3 coastal spots");
        }
    }

    // ── Helpers ──

    /**
     * Builds a mixed slot list with exactly {@code go} GO slots, {@code marginal} MARGINAL
     * slots, and {@code standdown} STANDDOWN slots.
     */
    private static List<BriefingSlot> slots(int go, int marginal, int standdown) {
        List<BriefingSlot> result = new ArrayList<>();
        for (int i = 0; i < go; i++) {
            result.add(slot("G" + i, Verdict.GO));
        }
        for (int i = 0; i < marginal; i++) {
            result.add(slot("M" + i, Verdict.MARGINAL));
        }
        for (int i = 0; i < standdown; i++) {
            result.add(slot("S" + i, Verdict.STANDDOWN));
        }
        return result;
    }

    private static BriefingSlot slot(String name, Verdict verdict) {
        return new BriefingSlot(name,
                LocalDateTime.of(2026, 3, 25, 18, 0), verdict,
                new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                        8.0, null, null, BigDecimal.ONE, 0, 0),
                BriefingSlot.TideInfo.NONE, List.of(), null);
    }
}
