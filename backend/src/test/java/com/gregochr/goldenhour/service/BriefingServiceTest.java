package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BriefingService}.
 */
@ExtendWith(MockitoExtension.class)
class BriefingServiceTest {

    @Mock
    private LocationService locationService;
    @Mock
    private SolarService solarService;
    @Mock
    private OpenMeteoClient openMeteoClient;
    @Mock
    private TideService tideService;
    @Mock
    private JobRunService jobRunService;

    private BriefingService briefingService;

    @BeforeEach
    void setUp() {
        briefingService = new BriefingService(
                locationService, solarService, openMeteoClient, tideService,
                jobRunService, Executors.newVirtualThreadPerTaskExecutor());
    }

    // ── Verdict logic ──

    @Nested
    @DisplayName("Verdict determination")
    class VerdictTests {

        @Test
        @DisplayName("GO when all metrics are clear")
        void go_allClear() {
            assertThat(BriefingService.determineVerdict(
                    20, new BigDecimal("0.0"), 15000, 70))
                    .isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("STANDDOWN when low cloud > 80%")
        void standdown_highCloud() {
            assertThat(BriefingService.determineVerdict(
                    85, BigDecimal.ZERO, 15000, 70))
                    .isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("STANDDOWN when precip > 2mm")
        void standdown_heavyRain() {
            assertThat(BriefingService.determineVerdict(
                    20, new BigDecimal("3.5"), 15000, 70))
                    .isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("STANDDOWN when visibility < 5000m")
        void standdown_poorVisibility() {
            assertThat(BriefingService.determineVerdict(
                    20, BigDecimal.ZERO, 3000, 70))
                    .isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("MARGINAL when low cloud 50-80%")
        void marginal_partialCloud() {
            assertThat(BriefingService.determineVerdict(
                    65, BigDecimal.ZERO, 15000, 70))
                    .isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("MARGINAL when precip 0.5-2mm")
        void marginal_lightRain() {
            assertThat(BriefingService.determineVerdict(
                    20, new BigDecimal("1.0"), 15000, 70))
                    .isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("MARGINAL when visibility 5000-10000m")
        void marginal_reducedVisibility() {
            assertThat(BriefingService.determineVerdict(
                    20, BigDecimal.ZERO, 7000, 70))
                    .isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("MARGINAL when humidity > 90%")
        void marginal_mistRisk() {
            assertThat(BriefingService.determineVerdict(
                    20, BigDecimal.ZERO, 15000, 95))
                    .isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("STANDDOWN takes precedence over MARGINAL")
        void standdown_precedence() {
            assertThat(BriefingService.determineVerdict(
                    85, new BigDecimal("1.0"), 7000, 95))
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
            assertThat(BriefingService.rollUpVerdict(slots)).isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("STANDDOWN when majority of slots are STANDDOWN")
        void rollup_majorityStanddown() {
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.STANDDOWN),
                    slot("B", Verdict.STANDDOWN),
                    slot("C", Verdict.GO));
            assertThat(BriefingService.rollUpVerdict(slots)).isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("MARGINAL when mixed")
        void rollup_mixed() {
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.GO),
                    slot("B", Verdict.MARGINAL),
                    slot("C", Verdict.STANDDOWN));
            assertThat(BriefingService.rollUpVerdict(slots)).isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("MARGINAL for empty slots")
        void rollup_empty() {
            assertThat(BriefingService.rollUpVerdict(List.of())).isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("MARGINAL when all slots are MARGINAL")
        void rollup_allMarginal() {
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.MARGINAL),
                    slot("B", Verdict.MARGINAL));
            assertThat(BriefingService.rollUpVerdict(slots)).isEqualTo(Verdict.MARGINAL);
        }
    }

    

    @Nested
    @DisplayName("Tide highlights")
    class TideTests {

        @Test
        @DisplayName("King tide highlighted")
        void kingTide() {
            BriefingSlot s = new BriefingSlot("Bamburgh",
                    LocalDateTime.of(2026, 3, 25, 5, 47), Verdict.GO,
                    20, BigDecimal.ZERO, 15000, 70, 8.0, BigDecimal.ONE,
                    "HIGH", true,
                    LocalDateTime.of(2026, 3, 25, 6, 15), new BigDecimal("1.85"),
                    true, false, List.of("King tide"));
            assertThat(BriefingService.buildTideHighlights(List.of(s)))
                    .containsExactly("King tide at Bamburgh");
        }

        @Test
        @DisplayName("Spring tide highlighted when not king")
        void springTide() {
            BriefingSlot s = new BriefingSlot("Seahouses",
                    LocalDateTime.of(2026, 3, 25, 5, 47), Verdict.GO,
                    20, BigDecimal.ZERO, 15000, 70, 8.0, BigDecimal.ONE,
                    "HIGH", true, null, null,
                    false, true, List.of("Spring tide"));
            assertThat(BriefingService.buildTideHighlights(List.of(s)))
                    .containsExactly("Spring tide at Seahouses");
        }

        @Test
        @DisplayName("No highlights for inland location")
        void noTide() {
            assertThat(BriefingService.buildTideHighlights(
                    List.of(slot("Durham", Verdict.GO)))).isEmpty();
        }
    }

    

    @Nested
    @DisplayName("Flag generation")
    class FlagTests {

        @Test
        @DisplayName("Sun blocked flag for cloud > 80%")
        void sunBlocked() {
            List<String> flags = BriefingService.buildFlags(
                    85, BigDecimal.ZERO, 15000, 70, null, false, false, false);
            assertThat(flags).contains("Sun blocked");
        }

        @Test
        @DisplayName("Active rain flag for precip > 2mm")
        void activeRain() {
            List<String> flags = BriefingService.buildFlags(
                    20, new BigDecimal("3.0"), 15000, 70, null, false, false, false);
            assertThat(flags).contains("Active rain");
        }

        @Test
        @DisplayName("No flags when all clear")
        void noFlags() {
            List<String> flags = BriefingService.buildFlags(
                    20, BigDecimal.ZERO, 15000, 70, null, false, false, false);
            assertThat(flags).isEmpty();
        }

        @Test
        @DisplayName("Multiple flags accumulate")
        void multipleFlags() {
            List<String> flags = BriefingService.buildFlags(
                    85, new BigDecimal("5.0"), 3000, 95, "HIGH", true, true, false);
            assertThat(flags).containsExactly(
                    "Sun blocked", "Active rain", "Poor visibility",
                    "Mist risk", "King tide", "Tide aligned");
        }
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

            BriefingEventSummary summary = briefingService.buildEventSummary(
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

            List<BriefingDay> days = briefingService.buildDays(
                    slots, List.of(loc), List.of(today, tomorrow));

            assertThat(days).hasSize(2);
            assertThat(days.get(0).eventSummaries()).hasSize(2);
            assertThat(days.get(1).eventSummaries()).hasSize(2);
        }
    }

    

    @Nested
    @DisplayName("Headline generation")
    class HeadlineTests {

        @Test
        @DisplayName("Best region highlighted in headline")
        void bestRegionHeadline() {
            BriefingRegion goRegion = new BriefingRegion("Lake District",
                    Verdict.GO, "Clear skies", List.of(), List.of());
            BriefingRegion standdownRegion = new BriefingRegion("Northumberland",
                    Verdict.STANDDOWN, "Rain everywhere", List.of(), List.of());

            LocalDate today = LocalDate.now();
            BriefingDay day = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNSET,
                            List.of(goRegion, standdownRegion), List.of())));

            String headline = briefingService.generateHeadline(List.of(day));
            assertThat(headline).contains("Lake District");
            assertThat(headline).contains("sunset");
        }

        @Test
        @DisplayName("Standdown headline when no good conditions")
        void standdownHeadline() {
            BriefingRegion region = new BriefingRegion("Northumberland",
                    Verdict.STANDDOWN, "Rain everywhere", List.of(), List.of());
            LocalDate today = LocalDate.now();
            BriefingDay day = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNSET,
                            List.of(region), List.of())));

            String headline = briefingService.generateHeadline(List.of(day));
            assertThat(headline).isEqualTo("No promising conditions in the next two days");
        }
    }

    

    @Nested
    @DisplayName("Region summary text")
    class SummaryTextTests {

        @Test
        @DisplayName("GO summary includes location count")
        void goSummary() {
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.GO), slot("B", Verdict.GO),
                    slot("C", Verdict.GO), slot("D", Verdict.MARGINAL));
            String summary = BriefingService.buildRegionSummary(
                    Verdict.GO, slots, List.of());
            assertThat(summary).startsWith("Clear at 3 of 4");
        }

        @Test
        @DisplayName("STANDDOWN summary for all-standdown region")
        void standdownSummary() {
            List<BriefingSlot> slots = List.of(
                    slot("A", Verdict.STANDDOWN),
                    slot("B", Verdict.STANDDOWN));
            String summary = BriefingService.buildRegionSummary(
                    Verdict.STANDDOWN, slots, List.of());
            assertThat(summary).contains("Heavy cloud and rain across all 2 locations");
        }

        @Test
        @DisplayName("Tide highlights appended to summary")
        void tideInSummary() {
            List<BriefingSlot> slots = List.of(slot("Bamburgh", Verdict.GO));
            List<String> tideHighlights = List.of("King tide at Bamburgh");
            String summary = BriefingService.buildRegionSummary(
                    Verdict.GO, slots, tideHighlights);
            assertThat(summary).contains("king tide at bamburgh");
        }
    }

    

    @Nested
    @DisplayName("Colour location filter")
    class ColourFilterTests {

        @Test
        @DisplayName("LANDSCAPE location is colour")
        void landscape() {
            LocationEntity loc = LocationEntity.builder()
                    .name("Test").lat(55).lon(-1)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .build();
            assertThat(briefingService.isColourLocation(loc)).isTrue();
        }

        @Test
        @DisplayName("Pure WILDLIFE location is excluded")
        void wildlife() {
            LocationEntity loc = LocationEntity.builder()
                    .name("Test").lat(55).lon(-1)
                    .locationType(Set.of(LocationType.WILDLIFE))
                    .build();
            assertThat(briefingService.isColourLocation(loc)).isFalse();
        }

        @Test
        @DisplayName("Mixed WILDLIFE + SEASCAPE location is included")
        void mixed() {
            LocationEntity loc = LocationEntity.builder()
                    .name("Test").lat(55).lon(-1)
                    .locationType(Set.of(LocationType.WILDLIFE, LocationType.SEASCAPE))
                    .build();
            assertThat(briefingService.isColourLocation(loc)).isTrue();
        }

        @Test
        @DisplayName("Empty location type defaults to colour")
        void emptyType() {
            LocationEntity loc = LocationEntity.builder()
                    .name("Test").lat(55).lon(-1)
                    .locationType(Set.of())
                    .build();
            assertThat(briefingService.isColourLocation(loc)).isTrue();
        }
    }

    

    @Test
    @DisplayName("getCachedBriefing returns null before first refresh")
    void cache_initiallyNull() {
        assertThat(briefingService.getCachedBriefing()).isNull();
    }

    @Test
    @DisplayName("Refresh populates the cache")
    void refresh_populatesCache() {
        LocationEntity loc = location("Durham", null);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
        when(solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                .thenReturn(LocalDateTime.now().withHour(6).withMinute(0));
        when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                .thenReturn(LocalDateTime.now().withHour(18).withMinute(0));
        when(openMeteoClient.fetchForecast(loc.getLat(), loc.getLon()))
                .thenReturn(buildForecastResponse());

        briefingService.refreshBriefing();

        DailyBriefingResponse cached = briefingService.getCachedBriefing();
        assertThat(cached).isNotNull();
        assertThat(cached.days()).hasSize(2);
        assertThat(cached.headline()).isNotBlank();
    }

    

    private static BriefingSlot slot(String name, Verdict verdict) {
        return new BriefingSlot(name,
                LocalDateTime.of(2026, 3, 25, 18, 0), verdict,
                20, BigDecimal.ZERO, 15000, 70, 8.0, BigDecimal.ONE,
                null, false, null, null, false, false, List.of());
    }

    private static BriefingSlot slotAt(String name, Verdict verdict, LocalDateTime time) {
        return new BriefingSlot(name, time, verdict,
                20, BigDecimal.ZERO, 15000, 70, 8.0, BigDecimal.ONE,
                null, false, null, null, false, false, List.of());
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

    private static OpenMeteoForecastResponse buildForecastResponse() {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();

        // Generate 48 hours of data (today + tomorrow)
        List<String> times = new ArrayList<>();
        List<Integer> cloudLow = new ArrayList<>();
        List<Integer> cloudMid = new ArrayList<>();
        List<Integer> cloudHigh = new ArrayList<>();
        List<Double> visibility = new ArrayList<>();
        List<Double> windSpeed = new ArrayList<>();
        List<Integer> windDir = new ArrayList<>();
        List<Double> precip = new ArrayList<>();
        List<Integer> weatherCode = new ArrayList<>();
        List<Integer> humidity = new ArrayList<>();
        List<Double> pressure = new ArrayList<>();
        List<Double> radiation = new ArrayList<>();
        List<Double> blh = new ArrayList<>();
        List<Double> temp = new ArrayList<>();
        List<Double> feelsLike = new ArrayList<>();
        List<Integer> precipProb = new ArrayList<>();
        List<Double> dewPoint = new ArrayList<>();

        LocalDateTime start = LocalDate.now().atStartOfDay();
        for (int i = 0; i < 48; i++) {
            times.add(start.plusHours(i).toString());
            cloudLow.add(20);
            cloudMid.add(30);
            cloudHigh.add(40);
            visibility.add(15000.0);
            windSpeed.add(5.0);
            windDir.add(180);
            precip.add(0.0);
            weatherCode.add(0);
            humidity.add(70);
            pressure.add(1013.0);
            radiation.add(100.0);
            blh.add(500.0);
            temp.add(10.0);
            feelsLike.add(8.0);
            precipProb.add(5);
            dewPoint.add(5.0);
        }

        hourly.setTime(times);
        hourly.setCloudCoverLow(cloudLow);
        hourly.setCloudCoverMid(cloudMid);
        hourly.setCloudCoverHigh(cloudHigh);
        hourly.setVisibility(visibility);
        hourly.setWindSpeed10m(windSpeed);
        hourly.setWindDirection10m(windDir);
        hourly.setPrecipitation(precip);
        hourly.setWeatherCode(weatherCode);
        hourly.setRelativeHumidity2m(humidity);
        hourly.setSurfacePressure(pressure);
        hourly.setShortwaveRadiation(radiation);
        hourly.setBoundaryLayerHeight(blh);
        hourly.setTemperature2m(temp);
        hourly.setApparentTemperature(feelsLike);
        hourly.setPrecipitationProbability(precipProb);
        hourly.setDewPoint2m(dewPoint);

        response.setHourly(hourly);
        return response;
    }
}
