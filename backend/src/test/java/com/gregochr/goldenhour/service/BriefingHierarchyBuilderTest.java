package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BriefingHierarchyBuilder}.
 */
class BriefingHierarchyBuilderTest {

    private BriefingHierarchyBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new BriefingHierarchyBuilder(new BriefingVerdictEvaluator());
    }

    @Nested
    @DisplayName("Event summary grouping")
    class EventSummaryTests {

        @Test
        @DisplayName("Slots grouped by region with unregioned fallback")
        void groupsByRegion() {
            Map<String, String> locationToRegion = new LinkedHashMap<>();
            locationToRegion.put("Bamburgh", "Northumberland");
            locationToRegion.put("Seahouses", "Northumberland");
            locationToRegion.put("Durham", null);

            List<BriefingSlot> slots = List.of(
                    slot("Bamburgh", Verdict.GO),
                    slot("Seahouses", Verdict.MARGINAL),
                    slot("Durham", Verdict.GO));

            BriefingEventSummary summary = builder.buildEventSummary(
                    TargetType.SUNSET, slots, locationToRegion);

            assertThat(summary.targetType()).isEqualTo(TargetType.SUNSET);
            assertThat(summary.regions()).hasSize(1);
            assertThat(summary.regions().getFirst().regionName()).isEqualTo("Northumberland");
            assertThat(summary.regions().getFirst().slots()).hasSize(2);
            assertThat(summary.unregioned()).hasSize(1);
            assertThat(summary.unregioned().getFirst().locationName()).isEqualTo("Durham");
        }
    }

    @Nested
    @DisplayName("Day structure")
    class DayTests {

        @Test
        @DisplayName("4 event types across 2 days")
        void fourEventTypes() {
            LocalDate today = LocalDate.of(2026, 3, 25);
            LocalDate tomorrow = today.plusDays(1);

            LocationEntity loc = location("Bamburgh", "Northumberland");

            List<BriefingSlot> slots = List.of(
                    slotAt("Bamburgh", Verdict.GO, today.atTime(6, 0)),
                    slotAt("Bamburgh", Verdict.MARGINAL, today.atTime(18, 0)),
                    slotAt("Bamburgh", Verdict.GO, tomorrow.atTime(6, 0)),
                    slotAt("Bamburgh", Verdict.STANDDOWN, tomorrow.atTime(18, 0)));

            List<BriefingDay> days = builder.buildDays(
                    slots, List.of(loc), List.of(today, tomorrow));

            assertThat(days).hasSize(2);
            assertThat(days.get(0).eventSummaries()).hasSize(2);
            assertThat(days.get(1).eventSummaries()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Region comfort rollup")
    class RegionComfortTests {

        private BriefingSlot slotWithComfort(String name, Verdict verdict,
                double temp, double apparentTemp, double wind, int weatherCode) {
            return new BriefingSlot(name, LocalDateTime.of(2026, 3, 26, 18, 0), verdict,
                    new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                            temp, apparentTemp, weatherCode, BigDecimal.valueOf(wind), 0, 0),
                    BriefingSlot.TideInfo.NONE, List.of(), null);
        }

        @Test
        @DisplayName("averages temperature and wind across GO slots")
        void averagesGoSlots() {
            List<BriefingSlot> slots = List.of(
                    slotWithComfort("A", Verdict.GO, 10.0, 7.0, 5.0, 0),
                    slotWithComfort("B", Verdict.GO, 12.0, 9.0, 3.0, 1),
                    slotWithComfort("C", Verdict.STANDDOWN, 20.0, 18.0, 1.0, 95));
            BriefingRegion region = builder.buildRegion("Test", slots);

            assertThat(region.regionTemperatureCelsius()).isEqualTo(11.0);
            assertThat(region.regionApparentTemperatureCelsius()).isEqualTo(8.0);
            assertThat(region.regionWindSpeedMs()).isEqualTo(4.0);
        }

        @Test
        @DisplayName("falls back to all slots when no GO slots present")
        void fallsBackToAllSlots() {
            List<BriefingSlot> slots = List.of(
                    slotWithComfort("A", Verdict.STANDDOWN, 8.0, 5.0, 6.0, 61),
                    slotWithComfort("B", Verdict.STANDDOWN, 10.0, 7.0, 4.0, 63));
            BriefingRegion region = builder.buildRegion("Test", slots);

            assertThat(region.regionTemperatureCelsius()).isEqualTo(9.0);
            assertThat(region.regionWindSpeedMs()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("weather code taken from median-temperature GO slot")
        void weatherCodeFromMedianSlot() {
            List<BriefingSlot> slots = List.of(
                    slotWithComfort("A", Verdict.GO, 8.0, 5.0, 5.0, 0),
                    slotWithComfort("B", Verdict.GO, 10.0, 7.0, 5.0, 3),
                    slotWithComfort("C", Verdict.GO, 12.0, 9.0, 5.0, 80));
            BriefingRegion region = builder.buildRegion("Test", slots);

            // Sorted by temp: A(8), B(10), C(12) — middle index 1 → weatherCode=3
            assertThat(region.regionWeatherCode()).isEqualTo(3);
        }

        @Test
        @DisplayName("returns null comfort fields when all slots have null temperature")
        void nullTemperatureGraceful() {
            List<BriefingSlot> slots = List.of(
                    new BriefingSlot("A", LocalDateTime.of(2026, 3, 26, 18, 0), Verdict.GO,
                            new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                                    null, null, null, BigDecimal.ONE, 0, 0),
                            BriefingSlot.TideInfo.NONE, List.of(), null));
            BriefingRegion region = builder.buildRegion("Test", slots);

            assertThat(region.regionTemperatureCelsius()).isNull();
            assertThat(region.regionApparentTemperatureCelsius()).isNull();
            assertThat(region.regionWeatherCode()).isNull();
        }
    }

    /**
     * The event's time must survive independently of its slots, because a later pass is entitled
     * to remove them: {@code BriefingHonestyFilter} empties the slot list of any region with zero
     * Claude coverage. While the slots were the only carrier, such a day served with no time at
     * all, and the client — which treats a timeless event as one that has not happened yet — kept
     * an elapsed sunrise in a fixed-size list of upcoming events, pushing the last day of the
     * window off the screen.
     */
    @Nested
    @DisplayName("Event time, carried independently of the slots")
    class SolarEventTimeTests {

        private static final Map<String, String> ONE_REGION = Map.of("A", "North", "B", "North");

        @Test
        @DisplayName("The summary carries the earliest event time across its slots")
        void carriesEarliestEventTime() {
            List<BriefingSlot> slots = List.of(
                    slotAt("B", Verdict.GO, LocalDateTime.of(2026, 8, 5, 5, 27)),
                    slotAt("A", Verdict.GO, LocalDateTime.of(2026, 8, 5, 5, 20)));

            BriefingEventSummary summary =
                    builder.buildEventSummary(TargetType.SUNRISE, slots, ONE_REGION);

            // Earliest, not first-encountered: the old client-side rule read whichever slot the
            // region grouping happened to put first, so the same data could answer differently.
            assertThat(summary.solarEventTime()).isEqualTo(LocalDateTime.of(2026, 8, 5, 5, 20));
        }

        @Test
        @DisplayName("An event with no slots carries no time rather than a fabricated one")
        void noSlotsMeansNoTime() {
            BriefingEventSummary summary =
                    builder.buildEventSummary(TargetType.SUNRISE, List.of(), Map.of());

            assertThat(summary.solarEventTime()).isNull();
        }

        @Test
        @DisplayName("Every day in the window gets both events timed")
        void everyDayInTheWindowIsTimed() {
            LocalDate first = LocalDate.of(2026, 8, 5);
            List<BriefingSlot> slots = List.of(
                    slotAt("A", Verdict.GO, first.atTime(5, 20)),
                    slotAt("A", Verdict.GO, first.atTime(21, 2)),
                    slotAt("A", Verdict.GO, first.plusDays(1).atTime(5, 22)),
                    slotAt("A", Verdict.GO, first.plusDays(1).atTime(21, 0)));

            List<BriefingDay> days = builder.buildDays(slots, List.of(),
                    List.of(first, first.plusDays(1)));

            assertThat(days).allSatisfy(day ->
                    assertThat(day.eventSummaries()).allSatisfy(es ->
                            assertThat(es.solarEventTime()).isNotNull()));
            assertThat(days.get(0).eventSummaries().get(0).solarEventTime())
                    .isEqualTo(first.atTime(5, 20));
            assertThat(days.get(0).eventSummaries().get(1).solarEventTime())
                    .isEqualTo(first.atTime(21, 2));
        }
    }

    private static BriefingSlot slot(String name, Verdict verdict) {
        return new BriefingSlot(name,
                LocalDateTime.of(2026, 3, 25, 18, 0), verdict,
                new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                        8.0, null, null, BigDecimal.ONE, 0, 0),
                BriefingSlot.TideInfo.NONE, List.of(), null);
    }

    private static BriefingSlot slotAt(String name, Verdict verdict, LocalDateTime time) {
        return new BriefingSlot(name, time, verdict,
                new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                        8.0, null, null, BigDecimal.ONE, 0, 0),
                BriefingSlot.TideInfo.NONE, List.of(), null);
    }

    private static LocationEntity location(String name, String regionName) {
        RegionEntity region = regionName != null
                ? RegionEntity.builder().name(regionName).build() : null;
        return LocationEntity.builder()
                .id(1L).name(name).lat(55.0).lon(-1.5)
                .locationType(Set.of(LocationType.LANDSCAPE))
                .tideType(Set.of())
                .solarEventType(Set.of())
                .region(region)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("Canopy slots must not vote in the sky region roll-up")
    class CanopyRollUpTests {

        /** A woodland GO: computed on inverted polarity — it means heavy cloud and mist. */
        private BriefingSlot canopyGo(String name) {
            return BriefingSlot.canopySlot(name, LocalDateTime.of(2026, 10, 12, 7, 30), Verdict.GO,
                    new BriefingSlot.WeatherConditions(95, java.math.BigDecimal.ZERO, 3000, 95,
                            9.0, 8.0, 3, java.math.BigDecimal.ONE, 85, 20),
                    List.of("Mist", "Soft even light"), null);
        }

        private BriefingSlot skyStanddown(String name) {
            return new BriefingSlot(name, LocalDateTime.of(2026, 10, 12, 7, 30), Verdict.STANDDOWN,
                    new BriefingSlot.WeatherConditions(95, java.math.BigDecimal.ZERO, 3000, 95,
                            9.0, 8.0, 3, java.math.BigDecimal.ONE, 85, 20),
                    BriefingSlot.TideInfo.NONE, List.of(), "Heavy cloud");
        }

        // rollUpVerdict fires GO at 20% of viable slots, so one overcast wood could carry a whole
        // region to "Worth it" — and buildRegionSummary would then say "Clear at 1 of 5 locations"
        // while counting a slot that is GO *because* it is overcast.
        @Test
        @DisplayName("One canopy GO does not carry a region of sky stand-downs")
        void canopyGoDoesNotCarryTheRegion() {
            List<BriefingSlot> slots = List.of(
                    canopyGo("Houghall Woods"),
                    skyStanddown("Penshaw"), skyStanddown("Marsden"),
                    skyStanddown("Souter"), skyStanddown("Tynemouth"));

            // The premise: strip the canopy marker and the wood votes again — which is exactly
            // what the sky roll-up saw for every wood before this flag existed.
            List<BriefingSlot> unmarked = slots.stream()
                    .map(sl -> sl.canopy()
                            ? new BriefingSlot(sl.locationName(), sl.solarEventTime(), sl.verdict(),
                                    sl.weather(), sl.tide(), sl.flags(), sl.standdownReason())
                            : sl)
                    .toList();
            BriefingRegion contaminated = builder.buildRegion("Tyne and Wear", unmarked);
            BriefingRegion clean = builder.buildRegion("Tyne and Wear", slots);

            assertThat(contaminated.verdict())
                    .as("premise: without the partition the wood drags the region up")
                    .isEqualTo(Verdict.GO);
            assertThat(clean.verdict())
                    .as("with it, the region reports what the sky is actually doing")
                    .isEqualTo(Verdict.STANDDOWN);
            assertThat(clean.summary()).doesNotContain("Clear at");
        }

        @Test
        @DisplayName("The wood keeps its own slot and verdict — it is excluded from the vote, not dropped")
        void canopySlotIsRetained() {
            List<BriefingSlot> slots = List.of(canopyGo("Houghall Woods"), skyStanddown("Penshaw"));

            BriefingRegion region = builder.buildRegion("Tyne and Wear", slots);

            assertThat(region.slots()).hasSize(2);
            assertThat(region.slots()).anySatisfy(s -> {
                assertThat(s.locationName()).isEqualTo("Houghall Woods");
                assertThat(s.verdict()).isEqualTo(Verdict.GO);
                assertThat(s.flags()).contains("Mist");
            });
        }

        @Test
        @DisplayName("A region of nothing but woods falls back to them rather than having no verdict")
        void allCanopyRegionFallsBack() {
            List<BriefingSlot> slots = List.of(canopyGo("Houghall Woods"), canopyGo("Plessey Woods"));

            BriefingRegion region = builder.buildRegion("Durham", slots);

            assertThat(region.verdict()).isEqualTo(Verdict.GO);
        }
    }
}
