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
}
