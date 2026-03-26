package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.TideData;
import com.gregochr.goldenhour.model.TideStats;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BriefingSlotBuilder}.
 */
@ExtendWith(MockitoExtension.class)
class BriefingSlotBuilderTest {

    @Mock
    private SolarService solarService;
    @Mock
    private LocationService locationService;
    @Mock
    private TideService tideService;

    private BriefingSlotBuilder slotBuilder;

    @BeforeEach
    void setUp() {
        slotBuilder = new BriefingSlotBuilder(solarService, locationService,
                tideService, new BriefingVerdictEvaluator());
    }

    @Nested
    @DisplayName("Coastal tide demotion in buildSlot")
    class TideDemotionTests {

        private static final LocalDateTime SOLAR_TIME = LocalDateTime.of(2026, 3, 25, 18, 0);

        private LocationEntity coastalLoc() {
            return LocationEntity.builder()
                    .id(10L).name("Bamburgh").lat(55.6).lon(-1.7)
                    .locationType(Set.of(LocationType.SEASCAPE))
                    .tideType(Set.of(TideType.HIGH))
                    .solarEventType(Set.of())
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .build();
        }

        private LocationEntity inlandLoc() {
            return LocationEntity.builder()
                    .id(11L).name("Durham").lat(54.8).lon(-1.6)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of())
                    .solarEventType(Set.of())
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .build();
        }

        private TideData tideData(TideState state) {
            return new TideData(state, false, null, null, null, null, null, null);
        }

        @Test
        @DisplayName("Coastal + weather GO + tide not aligned → STANDDOWN with 'Tide not aligned' flag")
        void coastal_weatherGo_tideNotAligned_demotedToStanddown() throws Exception {
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveTideData(eq(loc.getId()), eq(SOLAR_TIME)))
                    .thenReturn(Optional.of(tideData(TideState.LOW)));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(false);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.STANDDOWN);
            assertThat(slot.flags()).contains("Tide not aligned");
        }

        @Test
        @DisplayName("Coastal + weather MARGINAL + tide not aligned → STANDDOWN")
        void coastal_weatherMarginal_tideNotAligned_demotedToStanddown() throws Exception {
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveTideData(eq(loc.getId()), eq(SOLAR_TIME)))
                    .thenReturn(Optional.of(tideData(TideState.LOW)));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(false);

            OpenMeteoForecastResponse marginalForecast = buildForecastResponse();
            marginalForecast.getHourly().getCloudCoverLow().replaceAll(ignored -> 65);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, marginalForecast);
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.STANDDOWN);
            assertThat(slot.flags()).contains("Tide not aligned");
        }

        @Test
        @DisplayName("Inland location + weather GO → GO, no tide demotion")
        void inland_weatherGo_notAffected() throws Exception {
            LocationEntity loc = inlandLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(false);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.GO);
            assertThat(slot.flags()).doesNotContain("Tide not aligned");
        }

        @Test
        @DisplayName("Coastal + no tide data in DB → weather GO verdict retained")
        void coastal_noTideData_weatherVerdictRetained() throws Exception {
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveTideData(eq(loc.getId()), eq(SOLAR_TIME)))
                    .thenReturn(Optional.empty());

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.GO);
            assertThat(slot.flags()).doesNotContain("Tide not aligned");
        }

        @Test
        @DisplayName("Coastal + weather already STANDDOWN + tide not aligned → no 'Tide not aligned' flag")
        void coastal_weatherStanddown_noFlagAdded() throws Exception {
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveTideData(eq(loc.getId()), eq(SOLAR_TIME)))
                    .thenReturn(Optional.of(tideData(TideState.LOW)));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(false);

            OpenMeteoForecastResponse standdownForecast = buildForecastResponse();
            standdownForecast.getHourly().getCloudCoverLow().replaceAll(ignored -> 90);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, standdownForecast);
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.STANDDOWN);
            assertThat(slot.flags()).doesNotContain("Tide not aligned");
            assertThat(slot.flags()).contains("Sun blocked");
        }

        @Test
        @DisplayName("Coastal + tide aligned → GO retained, 'Tide aligned' flag present")
        void coastal_tideAligned_goRetained() throws Exception {
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveTideData(eq(loc.getId()), eq(SOLAR_TIME)))
                    .thenReturn(Optional.of(tideData(TideState.HIGH)));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(true);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.GO);
            assertThat(slot.flags()).doesNotContain("Tide not aligned");
            assertThat(slot.flags()).contains("Tide aligned");
        }
    }

    @Test
    @DisplayName("Sunrise event type uses sunriseUtc")
    void sunrise_usesSunriseService() {
        LocationEntity loc = LocationEntity.builder()
                .id(11L).name("Durham").lat(54.8).lon(-1.6)
                .locationType(Set.of(LocationType.LANDSCAPE))
                .tideType(Set.of()).solarEventType(Set.of())
                .enabled(true).createdAt(LocalDateTime.now()).build();
        LocalDateTime sunriseTime = LocalDateTime.of(2026, 3, 25, 6, 15);
        when(solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                .thenReturn(sunriseTime);
        when(locationService.isCoastal(loc)).thenReturn(false);

        BriefingSlotBuilder.LocationWeather lw =
                new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
        BriefingSlot slot = slotBuilder.buildSlot(lw, sunriseTime.toLocalDate(),
                TargetType.SUNRISE);

        assertThat(slot).isNotNull();
        assertThat(slot.verdict()).isEqualTo(Verdict.GO);
    }

    @Test
    @DisplayName("Solar service exception returns null slot")
    void solarException_returnsNull() {
        LocationEntity loc = LocationEntity.builder()
                .id(11L).name("Durham").lat(54.8).lon(-1.6)
                .locationType(Set.of(LocationType.LANDSCAPE))
                .tideType(Set.of()).solarEventType(Set.of())
                .enabled(true).createdAt(LocalDateTime.now()).build();
        when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                .thenThrow(new RuntimeException("No sunset at this latitude"));

        BriefingSlotBuilder.LocationWeather lw =
                new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
        BriefingSlot slot = slotBuilder.buildSlot(lw, LocalDate.of(2026, 6, 21),
                TargetType.SUNSET);

        assertThat(slot).isNull();
    }

    @Test
    @DisplayName("King tide detected when height exceeds P95")
    void kingTide_detected() {
        LocalDateTime solarTime = LocalDateTime.of(2026, 3, 25, 18, 0);
        LocationEntity loc = LocationEntity.builder()
                .id(10L).name("Bamburgh").lat(55.6).lon(-1.7)
                .locationType(Set.of(LocationType.SEASCAPE))
                .tideType(Set.of(TideType.HIGH)).solarEventType(Set.of())
                .enabled(true).createdAt(LocalDateTime.now()).build();
        when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                .thenReturn(solarTime);
        when(locationService.isCoastal(loc)).thenReturn(true);

        // HIGH tide within 90 min of solar event, with height data
        TideData td = new TideData(TideState.HIGH, false, null,
                new BigDecimal("5.80"), null, null,
                solarTime.plusMinutes(30), null);
        when(tideService.deriveTideData(eq(loc.getId()), eq(solarTime)))
                .thenReturn(Optional.of(td));
        when(tideService.calculateTideAligned(any(), any())).thenReturn(true);

        // Stats with p95 = 5.50 and spring threshold = 5.00
        TideStats stats = new TideStats(
                new BigDecimal("4.00"), new BigDecimal("6.00"),
                new BigDecimal("1.00"), new BigDecimal("0.50"),
                200, new BigDecimal("3.00"),
                new BigDecimal("4.50"), new BigDecimal("5.00"), new BigDecimal("5.50"),
                10, new BigDecimal("0.05"), new BigDecimal("5.00"),
                new BigDecimal("5.50"), 5);
        when(tideService.getTideStats(loc.getId())).thenReturn(Optional.of(stats));

        BriefingSlotBuilder.LocationWeather lw =
                new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
        BriefingSlot slot = slotBuilder.buildSlot(lw, solarTime.toLocalDate(),
                TargetType.SUNSET);

        assertThat(slot).isNotNull();
        assertThat(slot.isKingTide()).isTrue();
        assertThat(slot.isSpringTide()).isTrue();
        assertThat(slot.flags()).contains("Tide aligned");
    }

    private static OpenMeteoForecastResponse buildForecastResponse() {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();

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
