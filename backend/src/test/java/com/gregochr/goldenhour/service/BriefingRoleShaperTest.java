package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BestBetStatus;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.BriefingWindowTide;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.LocationTideFact;
import com.gregochr.goldenhour.model.Verdict;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BriefingRoleShaper}: the strip table of tide-pro-gate-plan.md §7.1 #4 and the
 * scores and written-why strip of pro-features-gate-plan.md §6.
 */
class BriefingRoleShaperTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 11, 6, 0);

    private static final Set<String> SLOT_STRIPPED = Set.of("fierySkyPotential", "goldenHourPotential",
            "claudeSummary", "claudeHeadline");
    private static final Set<String> SLOT_KEPT = Set.of("locationId", "locationName", "solarEventTime",
            "verdict", "weather", "tide", "flags", "standdownReason", "claudeRating", "skyRating",
            "displayVerdict", "canopy", "eclipse", "neighbourOutlier", "gated");

    private final BriefingRoleShaper shaper = new BriefingRoleShaper();

    private static BriefingWindowTide fullTide() {
        return new BriefingWindowTide("Whitby", TideState.MID,
                BriefingWindowTide.Direction.FALLING, "HW", "19:28", "1h43 before sunset",
                "4.9 m", "1.2 m above an average tide", "0.3 m · smooth",
                List.of(0.0, 0.5, 1.0), 0.88, 0.42, 0.25, 0.75,
                List.of(new BriefingWindowTide.Extreme("HW", 0.8, "19:28")), "3.1 m");
    }

    private static LocationTideFact fullFact() {
        return new LocationTideFact(7L, "Bamburgh", "HIGH", true, 0.61, true,
                "HW 06:12 · 25m after sunrise", 0.83, "RISING", "4.9 m", "wants higher",
                "Tide suits this spot");
    }

    private static BriefingSlot slot(String name, List<String> flags, String standdownReason) {
        return new BriefingSlot(7L, name, AT, Verdict.GO, null, null, flags, standdownReason,
                null, null, null, null, null, DisplayVerdict.AWAITING, null, false, null);
    }

    private static DailyBriefingResponse response(BriefingWindow window) {
        BriefingSlot regioned = slot("Bamburgh", List.of("Tide aligned", "Clear all layers"), null);
        BriefingSlot unregioned = slot("Whitby", List.of("Tide not aligned"), null);
        BriefingRegion region = new BriefingRegion("North East", Verdict.GO, "s", List.of(),
                List.of(regioned), null, null, null, null, null, null, DisplayVerdict.AWAITING, 0,
                null, false, null);
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNRISE,
                List.of(region), List.of(unregioned), AT, window);
        return new DailyBriefingResponse(AT, "h",
                List.of(new BriefingDay(LocalDate.of(2026, 10, 11), List.of(summary))),
                List.of(), null, null, false, false, 0, null, List.of(), List.of());
    }

    private static BriefingWindow fullWindow() {
        return new BriefingWindow(AT, DisplayVerdict.WORTH_IT, 4, null, null, List.of(), null,
                fullTide(), List.of(fullFact()));
    }

    private static BriefingEventSummary summaryOf(DailyBriefingResponse r) {
        return r.days().get(0).eventSummaries().get(0);
    }

    @Test
    @DisplayName("LITE: the curve, positions, extremes and height are stripped; the free tide facts stay")
    void forLite_stripsWindowTideCurveKeepsFreeFacts() {
        BriefingWindowTide lite = summaryOf(shaper.forLite(response(fullWindow()))).window().tide();

        assertThat(lite.curve()).isEmpty();
        assertThat(lite.windowPosition()).isNull();
        assertThat(lite.windowLevel()).isNull();
        assertThat(lite.sunrisePosition()).isNull();
        assertThat(lite.sunsetPosition()).isNull();
        assertThat(lite.extremes()).isNull();
        assertThat(lite.heightAtWindow()).isNull();
        assertThat(lite.locationName()).isEqualTo("Whitby");
        assertThat(lite.state()).isEqualTo(TideState.MID);
        assertThat(lite.direction()).isEqualTo(BriefingWindowTide.Direction.FALLING);
        assertThat(lite.nearestType()).isEqualTo("HW");
        assertThat(lite.nearestTime()).isEqualTo("19:28");
        assertThat(lite.nearestOffset()).as("how far HW sits from the light is Pro").isNull();
        assertThat(lite.range()).isEqualTo("4.9 m");
        assertThat(lite.rangeAnomaly()).isEqualTo("1.2 m above an average tide");
        assertThat(lite.seas()).isEqualTo("0.3 m · smooth");
    }

    @Test
    @DisplayName("LITE: the per-location match and per-spot height are stripped; state and light facts stay")
    void forLite_stripsTideFactMatchKeepsFreeFacts() {
        LocationTideFact lite = summaryOf(shaper.forLite(response(fullWindow())))
                .window().tideFacts().get(0);

        assertThat(lite.tideAligned()).isNull();
        assertThat(lite.tideAlignmentQuality()).isNull();
        assertThat(lite.tideShortfall()).isNull();
        assertThat(lite.tideFitPhrase()).isNull();
        assertThat(lite.tideHeight()).isNull();
        assertThat(lite.tideLevel()).isNull();
        assertThat(lite.locationId()).isEqualTo(7L);
        assertThat(lite.locationName()).isEqualTo("Bamburgh");
        assertThat(lite.tideState()).isEqualTo("HIGH");
        assertThat(lite.tideDirection()).isEqualTo("RISING");
        assertThat(lite.tideOnTheLight()).as("the tide against the light is Pro").isNull();
        assertThat(lite.nearestSolarOffsetPhrase()).isNull();
    }

    @Test
    @DisplayName("LITE: the 'Tide aligned' and 'Tide not aligned' flags go from regioned and unregioned "
            + "slots, every other flag stays")
    void forLite_stripsMatchFlagsOnly() {
        BriefingEventSummary lite = summaryOf(shaper.forLite(response(fullWindow())));

        assertThat(lite.regions().get(0).slots().get(0).flags()).containsExactly("Clear all layers");
        assertThat(lite.unregioned().get(0).flags()).isEmpty();
    }

    @Test
    @DisplayName("LITE: a STANDDOWN verdict survives the flag strip (it is a rating); a tide-mismatch "
            + "reason goes, any other reason stays")
    void forLite_keepsStanddownVerdictAndReason() {
        BriefingSlot other = new BriefingSlot(7L, "Bamburgh", AT, Verdict.STANDDOWN, null, null,
                List.of(), "Building cloud", null, null, null, null, null,
                DisplayVerdict.AWAITING, null, false, null);
        assertThat(other.forLite().standdownReason()).isEqualTo("Building cloud");
        assertThat(other.forLite().verdict()).isEqualTo(Verdict.STANDDOWN);
        BriefingSlot standdown = new BriefingSlot(7L, "Bamburgh", AT, Verdict.STANDDOWN, null, null,
                List.of("Tide not aligned"), "Tide mismatch", null, null, null, null, null,
                DisplayVerdict.AWAITING, null, false, null);
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNRISE, List.of(),
                List.of(standdown), AT, null);
        DailyBriefingResponse in = new DailyBriefingResponse(AT, "h",
                List.of(new BriefingDay(LocalDate.of(2026, 10, 11), List.of(summary))),
                List.of(), null, null, false, false, 0, null, List.of(), List.of());

        BriefingSlot lite = summaryOf(shaper.forLite(in)).unregioned().get(0);

        assertThat(lite.flags()).isEmpty();
        assertThat(lite.verdict()).isEqualTo(Verdict.STANDDOWN);
        assertThat(lite.standdownReason()).as("the tide-mismatch reason states the water vs the light").isNull();
    }

    @Test
    @DisplayName("LITE: everything outside the tide block is carried through the rebuild")
    void forLite_carriesEverythingElse() {
        DailyBriefingResponse in = response(fullWindow());

        DailyBriefingResponse lite = shaper.forLite(in);

        assertThat(lite.headline()).isEqualTo("h");
        assertThat(lite.generatedAt()).isEqualTo(AT);
        assertThat(summaryOf(lite).solarEventTime()).isEqualTo(AT);
        assertThat(summaryOf(lite).window().verdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        assertThat(summaryOf(lite).window().bestRating()).isEqualTo(4);
        assertThat(summaryOf(lite).window().eventTime()).isEqualTo(AT);
        assertThat(summaryOf(lite).regions().get(0).slots().get(0).locationName())
                .isEqualTo("Bamburgh");
    }

    @Test
    @DisplayName("The input briefing still carries its curve and match after forLite")
    void forLite_doesNotMutateTheOriginal() {
        DailyBriefingResponse in = response(fullWindow());

        shaper.forLite(in);

        assertThat(summaryOf(in).window().tide()).isEqualTo(fullTide());
        assertThat(summaryOf(in).window().tide().curve()).containsExactly(0.0, 0.5, 1.0);
        assertThat(summaryOf(in).window().tideFacts()).containsExactly(fullFact());
        assertThat(summaryOf(in).regions().get(0).slots().get(0).flags())
                .containsExactly("Tide aligned", "Clear all layers");
    }

    @Test
    @DisplayName("A window with no tide and no facts stays that way, and a summary with no window is untouched")
    void forLite_toleratesAbsentTideFactsAndWindow() {
        BriefingWindow bare = new BriefingWindow(AT, DisplayVerdict.AWAITING, null, null, null,
                List.of(), null, null, null);

        BriefingWindow lite = summaryOf(shaper.forLite(response(bare))).window();
        BriefingEventSummary noWindow = summaryOf(shaper.forLite(response(null)));

        assertThat(lite.tide()).isNull();
        assertThat(lite.tideFacts()).isNull();
        assertThat(noWindow.window()).isNull();
    }

    @Test
    @DisplayName("A null briefing and a briefing with no days pass through")
    void forLite_nullAndEmptyDays() {
        DailyBriefingResponse empty = new DailyBriefingResponse(AT, "h", List.of(), List.of(), null,
                null, false, false, 0, null, List.of(), List.of());

        assertThat(shaper.forLite(null)).isNull();
        assertThat(shaper.forLite(empty).days()).isEmpty();
    }

    private static BriefingWindow.Pick pick() {
        return new BriefingWindow.Pick(BriefingWindow.PickKind.BEST, "North East", "Go east", null,
                4.25, "Bamburgh", 7L);
    }

    private static DailyBriefingResponse withPicks(DailyBriefingResponse base) {
        BestBet bet = new BestBet(1, "Go east", "Clear", "Sunrise", "North East", Confidence.HIGH,
                null, "Friday", "SUNRISE", "06:00", null, null);
        return base.withBestBets(List.of(bet), true).withBestBetModel("Opus")
                .withBestBetStatus(BestBetStatus.SUCCESS_WITH_PICKS);
    }

    @Test
    @DisplayName("LITE: the window pick is removed; the verdict, rating and tide-free facts stay")
    void forLite_stripsWindowPick() {
        BriefingWindow withPick = fullWindow().withPick(pick());

        BriefingWindow lite = summaryOf(shaper.forLite(response(withPick))).window();

        assertThat(lite.pick()).isNull();
        assertThat(lite.verdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        assertThat(lite.bestRating()).isEqualTo(4);
        assertThat(lite.eventTime()).isEqualTo(AT);
    }

    @Test
    @DisplayName("LITE: bestBets is empty (not absent), and the model, status and withdrawn flag are null")
    void forLite_stripsBestBetsModelStatusAndWithdrawn() {
        DailyBriefingResponse full = withPicks(response(fullWindow().withPick(pick())));

        DailyBriefingResponse lite = shaper.forLite(full);

        assertThat(lite.bestBets()).isNotNull().isEmpty();
        assertThat(lite.bestBetModel()).isNull();
        assertThat(lite.bestBetStatus()).isNull();
        assertThat(lite.bestBetsWithdrawn()).isNull();
        assertThat(lite.headline()).isEqualTo("h");
    }

    @Test
    @DisplayName("PRO is unchanged: the input keeps its picks, and forLite never mutates it")
    void forLite_doesNotMutateInput() {
        DailyBriefingResponse full = withPicks(response(fullWindow().withPick(pick())));

        shaper.forLite(full);

        assertThat(full.bestBets()).hasSize(1);
        assertThat(full.bestBetModel()).isEqualTo("Opus");
        assertThat(full.bestBetStatus()).isEqualTo(BestBetStatus.SUCCESS_WITH_PICKS);
        assertThat(full.bestBetsWithdrawn()).isTrue();
        assertThat(summaryOf(full).window().pick()).isEqualTo(pick());
    }

    @Test
    @DisplayName("Every BriefingWindow component is classified kept or stripped")
    void everyBriefingWindowComponentIsClassified() {
        Set<String> stripped = Set.of("pick");
        Set<String> handledAsTide = Set.of("tide", "tideFacts", "badges");
        Set<String> kept = Set.of("eventTime", "verdict", "bestRating", "confidence",
                "topRarityRank");

        assertThat(componentNames(BriefingWindow.class))
                .as("a new component must be classified here and handled in forLite()")
                .containsExactlyInAnyOrderElementsOf(union(union(stripped, handledAsTide), kept));
    }

    @Test
    @DisplayName("Every DailyBriefingResponse component is classified kept or stripped")
    void everyDailyBriefingResponseComponentIsClassified() {
        Set<String> stripped = Set.of("bestBets", "bestBetModel", "bestBetStatus", "bestBetsWithdrawn");
        Set<String> rebuiltViaDays = Set.of("days", "hotTopics", "bigSeas");
        Set<String> kept = Set.of("generatedAt", "headline", "auroraTonight", "auroraTomorrow",
                "stale", "partialFailure", "failedLocationCount", "seasonalFeatures",
                "renderedEvents", "previousGeneratedAt", "allowedLocationIds");

        assertThat(componentNames(DailyBriefingResponse.class))
                .as("a new component must be classified here and handled in forLite()")
                .containsExactlyInAnyOrderElementsOf(union(union(stripped, rebuiltViaDays), kept));
    }

    @Test
    @DisplayName("Every BriefingWindowTide component is classified kept or stripped")
    void everyBriefingWindowTideComponentIsClassified() {
        Set<String> stripped = Set.of("curve", "windowPosition", "windowLevel", "sunrisePosition",
                "sunsetPosition", "extremes", "heightAtWindow", "nearestOffset");
        Set<String> kept = Set.of("locationName", "state", "direction", "nearestType", "nearestTime",
                "range", "rangeAnomaly", "seas");

        assertThat(componentNames(BriefingWindowTide.class))
                .as("a new component must be classified here and handled in forLite()")
                .containsExactlyInAnyOrderElementsOf(union(stripped, kept));
    }

    @Test
    @DisplayName("Every LocationTideFact component is classified kept or stripped")
    void everyLocationTideFactComponentIsClassified() {
        Set<String> stripped = Set.of("tideAligned", "tideAlignmentQuality", "tideShortfall",
                "tideFitPhrase", "tideHeight", "tideLevel", "tideOnTheLight", "nearestSolarOffsetPhrase");
        Set<String> kept = Set.of("locationId", "locationName", "tideState", "tideDirection");

        assertThat(componentNames(LocationTideFact.class))
                .as("a new component must be classified here and handled in forLite()")
                .containsExactlyInAnyOrderElementsOf(union(stripped, kept));
    }

    @Test
    @DisplayName("forLite nulls exactly the stripped components, no more and no fewer")
    void forLiteNullsExactlyTheStrippedComponents() throws Exception {
        Set<String> strippedTide = Set.of("windowPosition", "windowLevel", "sunrisePosition",
                "sunsetPosition", "extremes", "heightAtWindow", "nearestOffset");
        for (RecordComponent c : BriefingWindowTide.class.getRecordComponents()) {
            Object value = c.getAccessor().invoke(fullTide().forLite());
            if (c.getName().equals("curve")) {
                assertThat((List<?>) value).isEmpty();
            } else {
                assertThat(value == null).as(c.getName()).isEqualTo(strippedTide.contains(c.getName()));
            }
        }
        Set<String> strippedFact = Set.of("tideAligned", "tideAlignmentQuality", "tideShortfall",
                "tideFitPhrase", "tideHeight", "tideLevel", "tideOnTheLight", "nearestSolarOffsetPhrase");
        for (RecordComponent c : LocationTideFact.class.getRecordComponents()) {
            Object value = c.getAccessor().invoke(fullFact().forLite());
            assertThat(value == null).as(c.getName()).isEqualTo(strippedFact.contains(c.getName()));
        }
    }

    private static BriefingSlot scoredSlot(List<String> flags) {
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                12, new BigDecimal("0.0"), 24000, 70, 9.5, 7.0, 2, new BigDecimal("3.1"), 30, 55);
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo("HIGH", true, AT,
                new BigDecimal("1.5"), false, false, null, null, null, 30, "HW", true,
                "HW 06:30 · 30m after sunrise");
        return new BriefingSlot(7L, "Bamburgh", AT, Verdict.GO, weather, tide, flags, "reason",
                4, 3, 81, 66, "Clear sky over the castle.", DisplayVerdict.WORTH_IT,
                "Clear over the castle", true, null, Boolean.TRUE);
    }

    private static DailyBriefingResponse responseOf(BriefingSlot regioned, BriefingSlot unregioned) {
        BriefingRegion region = new BriefingRegion("North East", Verdict.GO, "region prose", List.of(),
                List.of(regioned), null, null, null, null, "Gloss headline", "Gloss detail",
                DisplayVerdict.AWAITING, 0, null, false, null);
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNRISE,
                List.of(region), List.of(unregioned), AT, null);
        return new DailyBriefingResponse(AT, "h",
                List.of(new BriefingDay(LocalDate.of(2026, 10, 11), List.of(summary))),
                List.of(), null, null, false, false, 0, null, List.of(), List.of());
    }

    @Test
    @DisplayName("LITE: a slot with potentials, a summary and NO tide flag still loses them "
            + "(the early return must not skip the strip)")
    void forLite_stripsScoresFromASlotWithNoTideFlag() {
        DailyBriefingResponse lite = shaper.forLite(responseOf(
                scoredSlot(List.of("Clear all layers")), scoredSlot(List.of())));

        for (BriefingSlot s : List.of(summaryOf(lite).regions().get(0).slots().get(0),
                summaryOf(lite).unregioned().get(0))) {
            assertThat(s.fierySkyPotential()).isNull();
            assertThat(s.goldenHourPotential()).isNull();
            assertThat(s.claudeSummary()).isNull();
            assertThat(s.claudeHeadline()).isNull();
            assertThat(s.claudeRating()).isEqualTo(4);
            assertThat(s.skyRating()).isEqualTo(3);
            assertThat(s.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        }
        assertThat(summaryOf(lite).regions().get(0).slots().get(0).flags())
                .containsExactly("Clear all layers");
    }

    @Test
    @DisplayName("LITE: a slot with potentials AND a tide flag loses both the scores and the flag")
    void forLite_stripsScoresAndFlagTogether() {
        DailyBriefingResponse lite = shaper.forLite(responseOf(
                scoredSlot(List.of("Tide aligned", "Clear all layers")),
                scoredSlot(List.of("Tide not aligned"))));

        BriefingSlot regioned = summaryOf(lite).regions().get(0).slots().get(0);
        assertThat(regioned.fierySkyPotential()).isNull();
        assertThat(regioned.claudeSummary()).isNull();
        assertThat(regioned.flags()).containsExactly("Clear all layers");
        assertThat(summaryOf(lite).unregioned().get(0).goldenHourPotential()).isNull();
        assertThat(summaryOf(lite).unregioned().get(0).flags()).isEmpty();
    }

    @Test
    @DisplayName("LITE: the region prose is untouched")
    void forLite_keepsRegionProse() {
        BriefingRegion region = summaryOf(shaper.forLite(responseOf(
                scoredSlot(List.of()), scoredSlot(List.of())))).regions().get(0);

        assertThat(region.summary()).isEqualTo("region prose");
        assertThat(region.glossHeadline()).isEqualTo("Gloss headline");
        assertThat(region.glossDetail()).isEqualTo("Gloss detail");
    }

    @Test
    @DisplayName("The input slot still carries its potentials and summary after forLite")
    void forLite_doesNotMutateTheSlot() {
        BriefingSlot in = scoredSlot(List.of("Tide aligned"));

        shaper.forLite(responseOf(in, in));

        assertThat(in.fierySkyPotential()).isEqualTo(81);
        assertThat(in.claudeSummary()).isEqualTo("Clear sky over the castle.");
    }

    @Test
    @DisplayName("Every BriefingSlot component is classified kept or stripped")
    void everyBriefingSlotComponentIsClassified() {
        assertThat(componentNames(BriefingSlot.class))
                .as("a new component must be classified here and handled in BriefingSlot.forLite()")
                .containsExactlyInAnyOrderElementsOf(union(SLOT_STRIPPED, SLOT_KEPT));
    }

    @Test
    @DisplayName("BriefingSlot.forLite nulls exactly the stripped components and keeps the rest verbatim")
    void slotForLiteNullsExactlyTheStrippedComponents() throws Exception {
        BriefingSlot full = scoredSlot(List.of("Clear all layers"));
        BriefingSlot lite = full.forLite();

        for (RecordComponent c : BriefingSlot.class.getRecordComponents()) {
            Object value = c.getAccessor().invoke(lite);
            if (SLOT_STRIPPED.contains(c.getName())) {
                assertThat(value).as(c.getName()).isNull();
                assertThat(c.getAccessor().invoke(full)).as(c.getName() + " in the fixture").isNotNull();
            } else if (c.getName().equals("tide")) {
                assertThat(value).as("tide is narrowed by TideInfo.forLite, covered separately")
                        .isEqualTo(full.tide().forLite());
            } else {
                assertThat(value).as(c.getName()).isEqualTo(c.getAccessor().invoke(full));
            }
        }
    }

    private static final Set<String> TIDE_INFO_STRIPPED = Set.of("tideAligned", "tideAlignmentQuality",
            "tideShortfall", "tideFitPhrase", "tideHeight", "tideLevel", "nearestHighTideHeight",
            "nearestSolarOffsetMinutes", "nearestExtremeKind", "tideOnTheLight", "nearestSolarOffsetPhrase");
    private static final Set<String> TIDE_INFO_KEPT = Set.of("tideState", "nearestHighTideTime",
            "heightAboveP95", "heightAboveSpringThreshold", "lunarTideType", "lunarPhase", "moonAtPerigee",
            "tideDirection");

    /** A slot tide block with every component set, so a stripped one cannot hide behind a null. */
    private static BriefingSlot.TideInfo fullSlotTide() {
        return new BriefingSlot.TideInfo("HIGH", true, AT, new BigDecimal("4.9"), true, true,
                LunarTideType.KING_TIDE, "Full moon", true, 30, "HW", true, "HW 06:30 · 30m after sunrise",
                0.83, "RISING", "4.9 m", "HIGHER", "Tide suits this spot", 0.61);
    }

    private static BriefingSlot slotWithFullTide(List<String> flags) {
        BriefingSlot base = scoredSlot(flags);
        return base.withTide(fullSlotTide());
    }

    @Test
    @DisplayName("Every slot TideInfo component is classified kept or stripped")
    void everyTideInfoComponentIsClassified() {
        assertThat(componentNames(BriefingSlot.TideInfo.class))
                .as("a new component must be classified here and handled in TideInfo.forLite()")
                .containsExactlyInAnyOrderElementsOf(union(TIDE_INFO_STRIPPED, TIDE_INFO_KEPT));
    }

    @Test
    @DisplayName("TideInfo.forLite nulls exactly the stripped components and keeps the rest verbatim")
    void tideInfoForLiteNullsExactlyTheStrippedComponents() throws Exception {
        BriefingSlot.TideInfo full = fullSlotTide();
        BriefingSlot.TideInfo lite = full.forLite();

        for (RecordComponent c : BriefingSlot.TideInfo.class.getRecordComponents()) {
            Object value = c.getAccessor().invoke(lite);
            Object original = c.getAccessor().invoke(full);
            assertThat(original).as(c.getName() + " in the fixture").isNotNull();
            if (TIDE_INFO_STRIPPED.contains(c.getName())) {
                assertThat(value).as(c.getName()).isNull();
            } else {
                assertThat(value).as(c.getName()).isEqualTo(original);
            }
        }
    }

    @Test
    @DisplayName("LITE: regioned and unregioned slots lose the tide match and heights, keep the rest")
    void forLite_narrowsTheSlotTideBlock() {
        DailyBriefingResponse lite = shaper.forLite(responseOf(
                slotWithFullTide(List.of()), slotWithFullTide(List.of("Tide aligned"))));

        for (BriefingSlot s : List.of(summaryOf(lite).regions().get(0).slots().get(0),
                summaryOf(lite).unregioned().get(0))) {
            BriefingSlot.TideInfo t = s.tide();
            assertThat(t.tideAligned()).isNull();
            assertThat(t.tideAlignmentQuality()).isNull();
            assertThat(t.tideShortfall()).isNull();
            assertThat(t.tideFitPhrase()).isNull();
            assertThat(t.tideHeight()).isNull();
            assertThat(t.tideLevel()).isNull();
            assertThat(t.nearestHighTideHeight()).isNull();
            assertThat(t.tideState()).isEqualTo("HIGH");
            assertThat(t.nearestHighTideTime()).isEqualTo(AT);
            assertThat(t.heightAboveP95()).isTrue();
            assertThat(t.heightAboveSpringThreshold()).isTrue();
            assertThat(t.lunarTideType()).isEqualTo(LunarTideType.KING_TIDE);
            assertThat(t.tideOnTheLight()).isNull();
            assertThat(t.nearestSolarOffsetMinutes()).isNull();
            assertThat(t.nearestExtremeKind()).isNull();
            assertThat(t.nearestSolarOffsetPhrase()).isNull();
            assertThat(t.tideDirection()).isEqualTo("RISING");
        }
    }

    @Test
    @DisplayName("LITE: a slot with no tide block stays without one")
    void forLite_keepsANullSlotTideNull() {
        BriefingSlot noTide = scoredSlot(List.of()).withTide(null);

        assertThat(noTide.forLite().tide()).isNull();
    }

    @Test
    @DisplayName("PRO and ADMIN: the shaper is not applied, so the slot tide block stays whole")
    void proBodyKeepsTheWholeSlotTide() {
        BriefingSlot slot = slotWithFullTide(List.of());

        assertThat(slot.tide()).isEqualTo(fullSlotTide());
        shaper.forLite(responseOf(slot, slot));
        assertThat(slot.tide()).as("the shaper copies, never edits").isEqualTo(fullSlotTide());
    }

    @Test
    @DisplayName("LITE JSON: the serialised slot carries no value for the stripped tide keys, PRO JSON carries all")
    void liteSlotJsonOmitsTheStrippedTideKeys() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        BriefingSlot full = slotWithFullTide(List.of());

        com.fasterxml.jackson.databind.JsonNode lite = mapper.readTree(mapper.writeValueAsString(full.forLite()));
        com.fasterxml.jackson.databind.JsonNode pro = mapper.readTree(mapper.writeValueAsString(full));

        for (String key : TIDE_INFO_STRIPPED) {
            // nearestHighTideHeight is not NON_NULL (a pre-existing wire shape), so LITE carries
            // it as an explicit null; what matters is that no value reaches the reader.
            assertThat(lite.path(key).isMissingNode() || lite.path(key).isNull())
                    .as("LITE carries a value for " + key).isTrue();
            assertThat(pro.path(key).isMissingNode() || pro.path(key).isNull())
                    .as("PRO carries a value for " + key).isFalse();
        }
        for (String key : TIDE_INFO_KEPT) {
            assertThat(lite.has(key)).as("LITE has " + key).isTrue();
        }
    }

    private static Set<String> componentNames(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return all;
    }
}
