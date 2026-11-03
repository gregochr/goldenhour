package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.BriefingVerdictEvaluator.TideContext;
import com.gregochr.goldenhour.service.BriefingVerdictEvaluator.WeatherMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boundary tests for {@link BriefingVerdictEvaluator}: every threshold is exercised exactly on,
 * just below and just above the constant, so a {@code >} / {@code >=} slip changes an outcome.
 */
class BriefingVerdictEvaluatorBoundaryTest {

    private final BriefingVerdictEvaluator evaluator = new BriefingVerdictEvaluator();

    private static final TideContext NO_TIDE = new TideContext(null, false, false, false, null, false);

    /** Builds metrics; callers pass a cloud canvas (mid/high 30) so "Clear all layers" stays off. */
    private static WeatherMetrics metrics(int low, String precip, int vis, int hum, Integer mid, Integer high,
            boolean building) {
        return new WeatherMetrics(low, new BigDecimal(precip), vis, hum, mid, high, building);
    }

    private List<String> flags(WeatherMetrics w) {
        return evaluator.buildFlags(w, NO_TIDE);
    }

    private void assertFlags(List<String> actual, String expected) {
        if ("NONE".equals(expected)) {
            assertThat(actual).isEmpty();
        } else {
            assertThat(actual).containsExactly(expected);
        }
    }

    // ── Low cloud ──

    @ParameterizedTest(name = "low cloud {0}% -> {1}")
    @CsvSource({"50,NONE", "51,Partial cloud", "79,Partial cloud", "80,Partial cloud", "81,Sun blocked"})
    @DisplayName("low cloud flag at 50 / 80 thresholds (strictly greater)")
    void lowCloudFlag(int low, String expected) {
        assertFlags(flags(metrics(low, "0.0", 15000, 70, 30, 30, false)), expected);
    }

    @ParameterizedTest(name = "low cloud {0}% -> {1}")
    @CsvSource({"50,GO", "51,MARGINAL", "80,MARGINAL", "81,STANDDOWN"})
    @DisplayName("verdict low-cloud thresholds are strictly greater")
    void lowCloudVerdict(int low, Verdict expected) {
        assertThat(evaluator.determineVerdict(low, BigDecimal.ZERO, 15000, 70)).isEqualTo(expected);
    }

    // ── Precipitation ──

    @ParameterizedTest(name = "precip {0} mm -> {1}")
    @CsvSource({"0.5,NONE", "0.51,Light rain", "2.0,Light rain", "2.01,Active rain"})
    @DisplayName("precip flag at 0.5 / 2.0 thresholds (strictly greater)")
    void precipFlag(String precip, String expected) {
        assertFlags(flags(metrics(20, precip, 15000, 70, 30, 30, false)), expected);
    }

    @ParameterizedTest(name = "precip {0} mm -> {1}")
    @CsvSource({"0.5,GO", "0.51,MARGINAL", "2.0,MARGINAL", "2.01,STANDDOWN"})
    @DisplayName("verdict precip thresholds are strictly greater")
    void precipVerdict(String precip, Verdict expected) {
        assertThat(evaluator.determineVerdict(20, new BigDecimal(precip), 15000, 70)).isEqualTo(expected);
    }

    // ── Visibility ──

    @ParameterizedTest(name = "visibility {0} m -> {1}")
    @CsvSource({"10000,NONE", "9999,Reduced visibility", "5000,Reduced visibility", "4999,Poor visibility"})
    @DisplayName("visibility flag at 5000 / 10000 thresholds (strictly less)")
    void visibilityFlag(int vis, String expected) {
        assertFlags(flags(metrics(20, "0.0", vis, 70, 30, 30, false)), expected);
    }

    @ParameterizedTest(name = "visibility {0} m -> {1}")
    @CsvSource({"10000,GO", "9999,MARGINAL", "5000,MARGINAL", "4999,STANDDOWN"})
    @DisplayName("verdict visibility thresholds are strictly less")
    void visibilityVerdict(int vis, Verdict expected) {
        assertThat(evaluator.determineVerdict(20, BigDecimal.ZERO, vis, 70)).isEqualTo(expected);
    }

    // ── Humidity ──

    @ParameterizedTest(name = "humidity {0}% -> {1}")
    @CsvSource({"89,GO", "90,GO", "91,MARGINAL"})
    @DisplayName("verdict humidity threshold is strictly greater than 90")
    void humidityVerdict(int humidity, Verdict expected) {
        assertThat(evaluator.determineVerdict(20, BigDecimal.ZERO, 15000, humidity)).isEqualTo(expected);
    }

    @Test
    @DisplayName("humidity flag appears only above 90 and is worded by site")
    void humidityFlagBoundary() {
        WeatherMetrics onThreshold = metrics(20, "0.0", 15000, 90, 30, 30, false);
        WeatherMetrics above = metrics(20, "0.0", 15000, 91, 30, 30, false);
        assertThat(evaluator.buildFlags(onThreshold, NO_TIDE, false)).isEmpty();
        assertThat(evaluator.buildFlags(onThreshold, NO_TIDE, true)).isEmpty();
        assertThat(evaluator.buildFlags(above, NO_TIDE, false)).containsExactly("Mist risk");
        assertThat(evaluator.buildFlags(above, NO_TIDE, true)).containsExactly("Mist likely");
    }

    // ── Mid cloud ──

    @ParameterizedTest(name = "mid cloud {0}% -> {1}")
    @CsvSource({"59,NONE", "60,Heavy mid-cloud", "79,Heavy mid-cloud", "80,Grey ceiling", "81,Grey ceiling"})
    @DisplayName("mid-cloud flag at 60 / 80 thresholds (greater than or equal)")
    void midCloudFlag(int mid, String expected) {
        assertFlags(flags(metrics(20, "0.0", 15000, 70, mid, 30, false)), expected);
    }

    @Test
    @DisplayName("null mid cloud raises no mid-cloud flag")
    void midCloudNullNoFlag() {
        assertThat(flags(metrics(20, "0.0", 15000, 70, null, 30, false))).isEmpty();
    }

    @ParameterizedTest(name = "mid cloud {0}% on {1} -> {2}")
    @CsvSource({"59,GO,GO", "60,GO,MARGINAL", "79,GO,MARGINAL", "80,GO,STANDDOWN",
            "59,MARGINAL,MARGINAL", "60,MARGINAL,MARGINAL", "79,MARGINAL,MARGINAL",
            "80,MARGINAL,STANDDOWN", "79,STANDDOWN,STANDDOWN"})
    @DisplayName("mid-cloud demotion boundaries")
    void midCloudDemotion(int mid, Verdict in, Verdict expected) {
        assertThat(evaluator.applyMidCloudDemotion(in, mid)).isEqualTo(expected);
    }

    // ── Building trend ──

    @Test
    @DisplayName("Cloud building flag follows the buildingTrend boolean")
    void buildingTrendFlag() {
        assertThat(flags(metrics(20, "0.0", 15000, 70, 30, 30, true))).containsExactly("Cloud building");
        assertThat(flags(metrics(20, "0.0", 15000, 70, 30, 30, false))).doesNotContain("Cloud building");
    }

    @ParameterizedTest(name = "hours {0},{1},{2} -> {3}")
    @CsvSource({"37,38,39,GO", "40,35,41,MARGINAL", "30,40,40,MARGINAL", "40,30,40,GO",
            "50,40,50,GO", "20,30,39,GO", "20,30,40,MARGINAL", "41,40,39,GO"})
    @DisplayName("cloud-trend demotion: peak >= 40 and event hour strictly above earliest")
    void cloudTrendBoundaries(int first, int second, int third, Verdict expected) {
        assertThat(evaluator.applyCloudTrendDemotion(Verdict.GO, List.of(first, second, third)))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("cloud trend: exactly two hours is enough, one hour or none is not")
    void cloudTrendSizeBoundary() {
        assertThat(evaluator.applyCloudTrendDemotion(Verdict.GO, List.of(30, 45))).isEqualTo(Verdict.MARGINAL);
        assertThat(evaluator.applyCloudTrendDemotion(Verdict.GO, List.of(45))).isEqualTo(Verdict.GO);
        assertThat(evaluator.applyCloudTrendDemotion(Verdict.GO, List.of())).isEqualTo(Verdict.GO);
    }

    @Test
    @DisplayName("cloud trend peak is the maximum across the window, not just the end hours")
    void cloudTrendPeakInMiddle() {
        assertThat(evaluator.applyCloudTrendDemotion(Verdict.GO, List.of(20, 60, 30)))
                .isEqualTo(Verdict.MARGINAL);
        assertThat(evaluator.applyCloudTrendDemotion(Verdict.GO, List.of(10, 39, 20)))
                .isEqualTo(Verdict.GO);
    }

    // ── Clear all layers ──

    @ParameterizedTest(name = "low={0} mid={1} high={2} -> clear flag {3}")
    @CsvSource(nullValues = "null", value = {
            "14,14,14,true", "15,14,14,false", "14,15,14,false", "14,14,15,false", "16,14,14,false",
            "14,null,null,true", "14,14,null,true", "14,null,14,true",
            "15,null,null,false", "14,15,null,false", "14,null,15,false", "0,0,0,true"})
    @DisplayName("Clear all layers requires every layer strictly below 15; nulls count as 0")
    void clearAllLayersFlag(int low, Integer mid, Integer high, boolean expected) {
        List<String> f = flags(new WeatherMetrics(low, BigDecimal.ZERO, 15000, 70, mid, high, false));
        if (expected) {
            assertThat(f).contains("Clear all layers");
        } else {
            assertThat(f).doesNotContain("Clear all layers");
        }
    }

    @ParameterizedTest(name = "low={0} mid={1} high={2} -> {3}")
    @CsvSource(nullValues = "null", value = {
            "14,14,14,MARGINAL", "15,14,14,GO", "14,15,14,GO", "14,14,15,GO", "14,null,null,MARGINAL",
            "14,null,15,GO", "14,15,null,GO"})
    @DisplayName("clear-sky demotion boundaries per layer")
    void clearSkyDemotionBoundaries(int low, Integer mid, Integer high, Verdict expected) {
        assertThat(evaluator.applyClearSkyDemotion(Verdict.GO,
                new WeatherMetrics(low, BigDecimal.ZERO, 15000, 70, mid, high, false))).isEqualTo(expected);
    }

    // ── Horizon cloud ──

    @ParameterizedTest(name = "horizon {0}% on {1} -> {2}")
    @CsvSource({"39,GO,GO", "40,GO,MARGINAL", "69,GO,MARGINAL", "70,GO,STANDDOWN", "71,GO,STANDDOWN",
            "40,MARGINAL,MARGINAL", "69,MARGINAL,MARGINAL", "70,MARGINAL,STANDDOWN",
            "69,STANDDOWN,STANDDOWN"})
    @DisplayName("horizon cloud demotion boundaries (greater than or equal)")
    void horizonBoundaries(int horizon, Verdict in, Verdict expected) {
        assertThat(evaluator.applyHorizonCloudDemotion(in, horizon)).isEqualTo(expected);
    }

    // ── Flag order and interplay ──

    @Test
    @DisplayName("every milder flag together, in the documented order")
    void allMilderFlagsInOrder() {
        List<String> f = evaluator.buildFlags(
                metrics(60, "1.0", 7000, 95, 70, 10, true),
                new TideContext("HIGH", true, false, true, LunarTideType.SPRING_TIDE, true), true);
        assertThat(f).containsExactly("Partial cloud", "Light rain", "Reduced visibility", "Mist likely",
                "Heavy mid-cloud", "Cloud building", "Spring Tide, Extra High", "Tide not aligned",
                "Tide aligned");
    }

    @Test
    @DisplayName("worst-case flags replace, not accompany, their milder siblings")
    void severeFlagsExcludeMilder() {
        assertThat(flags(metrics(90, "5.0", 1000, 70, 90, 30, false)))
                .containsExactly("Sun blocked", "Active rain", "Poor visibility", "Grey ceiling");
    }

    // ── Tide flags ──

    @Test
    @DisplayName("Tide aligned and Tide not aligned are driven by their own booleans")
    void tideFlagsIndependent() {
        WeatherMetrics w = metrics(20, "0.0", 15000, 70, 30, 30, false);
        assertThat(evaluator.buildFlags(w, new TideContext("HIGH", true, false, false, null, false)))
                .containsExactly("Tide aligned");
        assertThat(evaluator.buildFlags(w, new TideContext("HIGH", false, false, false, null, true)))
                .containsExactly("Tide not aligned");
        assertThat(evaluator.buildFlags(w, new TideContext("HIGH", false, false, false, null, false)))
                .isEmpty();
    }

    @ParameterizedTest(name = "lunar={0} p95={1} spring={2} -> {3}")
    @CsvSource(nullValues = "null", value = {
            "KING_TIDE,true,true,'King Tide, Extra Extra High'",
            "KING_TIDE,false,true,'King Tide, Extra High'",
            "KING_TIDE,false,false,King Tide",
            "SPRING_TIDE,true,false,'Spring Tide, Extra Extra High'",
            "SPRING_TIDE,false,true,'Spring Tide, Extra High'",
            "SPRING_TIDE,false,false,Spring Tide",
            "REGULAR_TIDE,true,false,Extra Extra High",
            "REGULAR_TIDE,false,true,Extra High",
            "REGULAR_TIDE,false,false,null",
            "null,true,true,Extra Extra High",
            "null,false,true,Extra High",
            "null,false,false,null"})
    @DisplayName("tide label for lunar x statistical combinations, via buildFlags")
    void tideLabels(String lunar, boolean p95, boolean spring, String expected) {
        LunarTideType type = lunar == null ? null : LunarTideType.valueOf(lunar);
        List<String> f = evaluator.buildFlags(metrics(20, "0.0", 15000, 70, 30, 30, false),
                new TideContext("HIGH", false, p95, spring, type, false));
        if (expected == null) {
            assertThat(f).isEmpty();
        } else {
            assertThat(f).containsExactly(expected);
        }
    }

    // ── Tide highlights ──

    private static BriefingSlot tideSlot(String name, boolean aligned, boolean spring, LunarTideType type) {
        return new BriefingSlot(name, LocalDateTime.of(2026, 3, 25, 5, 47), Verdict.GO,
                new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                        8.0, null, null, BigDecimal.ONE, 0, 0),
                new BriefingSlot.TideInfo("HIGH", aligned, null, null, false, spring, type, null, null),
                List.of());
    }

    @Test
    @DisplayName("tide highlights group by label, count with plural, keep first-seen order")
    void tideHighlightsGrouped() {
        List<BriefingSlot> slots = List.of(
                tideSlot("A", true, false, LunarTideType.KING_TIDE),
                tideSlot("B", false, true, null),
                tideSlot("C", false, false, LunarTideType.KING_TIDE),
                tideSlot("D", true, false, LunarTideType.KING_TIDE),
                tideSlot("E", true, false, null));
        assertThat(evaluator.buildTideHighlights(slots))
                .containsExactly("King Tide at 3 coastal spots", "Extra High at 1 coastal spot");
    }

    @Test
    @DisplayName("tide highlights is empty when no slot has a notable tide")
    void tideHighlightsEmpty() {
        assertThat(evaluator.buildTideHighlights(List.of(tideSlot("A", true, false, null)))).isEmpty();
        assertThat(evaluator.buildTideHighlights(List.of())).isEmpty();
    }

    // ── Region summary ──

    private static BriefingSlot vslot(Verdict v) {
        return new BriefingSlot("L", LocalDateTime.of(2026, 3, 25, 18, 0), v,
                new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                        8.0, null, null, BigDecimal.ONE, 0, 0),
                BriefingSlot.TideInfo.NONE, List.of());
    }

    @Test
    @DisplayName("GO summary pluralises on one versus many")
    void goSummaryPlural() {
        assertThat(evaluator.buildRegionSummary(Verdict.GO, List.of(vslot(Verdict.GO)), List.of()))
                .isEqualTo("Clear at 1 of 1 location");
        assertThat(evaluator.buildRegionSummary(Verdict.GO, List.of(vslot(Verdict.GO), vslot(Verdict.GO)),
                List.of())).isEqualTo("Clear at 2 of 2 locations");
    }

    @Test
    @DisplayName("STANDDOWN summary: all vs partial, singular vs plural")
    void standdownSummaryVariants() {
        assertThat(evaluator.buildRegionSummary(Verdict.STANDDOWN, List.of(vslot(Verdict.STANDDOWN)), List.of()))
                .isEqualTo("Heavy cloud and rain across all 1 location");
        assertThat(evaluator.buildRegionSummary(Verdict.STANDDOWN,
                List.of(vslot(Verdict.STANDDOWN), vslot(Verdict.MARGINAL), vslot(Verdict.STANDDOWN)), List.of()))
                .isEqualTo("Poor conditions at 2 of 3 locations");
        assertThat(evaluator.buildRegionSummary(Verdict.STANDDOWN,
                List.of(vslot(Verdict.STANDDOWN), vslot(Verdict.MARGINAL)), List.of()))
                .isEqualTo("Poor conditions at 1 of 2 locations");
    }

    @Test
    @DisplayName("MARGINAL summary pluralises and appends lowercased tide highlights joined by comma")
    void marginalSummary() {
        assertThat(evaluator.buildRegionSummary(Verdict.MARGINAL, List.of(vslot(Verdict.MARGINAL)), List.of()))
                .isEqualTo("Mixed conditions across 1 location");
        assertThat(evaluator.buildRegionSummary(Verdict.MARGINAL,
                List.of(vslot(Verdict.MARGINAL), vslot(Verdict.GO)),
                List.of("King Tide at 2 coastal spots", "Extra High at 1 coastal spot")))
                .isEqualTo("Mixed conditions across 2 locations, king tide at 2 coastal spots, "
                        + "extra high at 1 coastal spot");
    }

    // ── Roll-up edge ──

    @Test
    @DisplayName("roll-up: one viable of five is exactly 20% viable -> MARGINAL; of six -> STANDDOWN")
    void rollupViableBoundary() {
        Verdict s = Verdict.STANDDOWN;
        assertThat(evaluator.rollUpVerdict(List.of(vslot(Verdict.MARGINAL), vslot(s), vslot(s), vslot(s),
                vslot(s)))).isEqualTo(Verdict.MARGINAL);
        assertThat(evaluator.rollUpVerdict(List.of(vslot(Verdict.MARGINAL), vslot(s), vslot(s), vslot(s),
                vslot(s), vslot(s)))).isEqualTo(Verdict.STANDDOWN);
    }
}
