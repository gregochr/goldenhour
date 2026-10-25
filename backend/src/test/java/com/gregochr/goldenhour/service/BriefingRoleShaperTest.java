package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.BriefingWindowTide;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.LocationTideFact;
import com.gregochr.goldenhour.model.Verdict;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BriefingRoleShaper}: the strip table of tide-pro-gate-plan.md §7.1 #4.
 */
class BriefingRoleShaperTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 11, 6, 0);

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
        assertThat(lite.nearestOffset()).isEqualTo("1h43 before sunset");
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
        assertThat(lite.tideOnTheLight()).isTrue();
        assertThat(lite.nearestSolarOffsetPhrase()).isEqualTo("HW 06:12 · 25m after sunrise");
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
    @DisplayName("LITE: a STANDDOWN verdict and its reason survive the flag strip (it is a rating)")
    void forLite_keepsStanddownVerdictAndReason() {
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
        assertThat(lite.standdownReason()).isEqualTo("Tide mismatch");
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

    @Test
    @DisplayName("Every BriefingWindowTide component is classified kept or stripped")
    void everyBriefingWindowTideComponentIsClassified() {
        Set<String> stripped = Set.of("curve", "windowPosition", "windowLevel", "sunrisePosition",
                "sunsetPosition", "extremes", "heightAtWindow");
        Set<String> kept = Set.of("locationName", "state", "direction", "nearestType", "nearestTime",
                "nearestOffset", "range", "rangeAnomaly", "seas");

        assertThat(componentNames(BriefingWindowTide.class))
                .as("a new component must be classified here and handled in forLite()")
                .containsExactlyInAnyOrderElementsOf(union(stripped, kept));
    }

    @Test
    @DisplayName("Every LocationTideFact component is classified kept or stripped")
    void everyLocationTideFactComponentIsClassified() {
        Set<String> stripped = Set.of("tideAligned", "tideAlignmentQuality", "tideShortfall",
                "tideFitPhrase", "tideHeight", "tideLevel");
        Set<String> kept = Set.of("locationId", "locationName", "tideState", "tideDirection",
                "tideOnTheLight", "nearestSolarOffsetPhrase");

        assertThat(componentNames(LocationTideFact.class))
                .as("a new component must be classified here and handled in forLite()")
                .containsExactlyInAnyOrderElementsOf(union(stripped, kept));
    }

    @Test
    @DisplayName("forLite nulls exactly the stripped components, no more and no fewer")
    void forLiteNullsExactlyTheStrippedComponents() throws Exception {
        Set<String> strippedTide = Set.of("windowPosition", "windowLevel", "sunrisePosition",
                "sunsetPosition", "extremes", "heightAtWindow");
        for (RecordComponent c : BriefingWindowTide.class.getRecordComponents()) {
            Object value = c.getAccessor().invoke(fullTide().forLite());
            if (c.getName().equals("curve")) {
                assertThat((List<?>) value).isEmpty();
            } else {
                assertThat(value == null).as(c.getName()).isEqualTo(strippedTide.contains(c.getName()));
            }
        }
        Set<String> strippedFact = Set.of("tideAligned", "tideAlignmentQuality", "tideShortfall",
                "tideFitPhrase", "tideHeight", "tideLevel");
        for (RecordComponent c : LocationTideFact.class.getRecordComponents()) {
            Object value = c.getAccessor().invoke(fullFact().forLite());
            assertThat(value == null).as(c.getName()).isEqualTo(strippedFact.contains(c.getName()));
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
