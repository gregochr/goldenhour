package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AstroConditionsEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.repository.AstroConditionsRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.AstroConditionsService.CloudResult;
import com.gregochr.goldenhour.service.AstroConditionsService.MoonResult;
import com.gregochr.goldenhour.service.AstroConditionsService.NightHour;
import com.gregochr.goldenhour.service.AstroConditionsService.VisibilityResult;
import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.LunarPhase;
import com.gregochr.solarutils.LunarPosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AstroConditionsService}.
 */
@ExtendWith(MockitoExtension.class)
class AstroConditionsServiceTest {

    @Mock
    private OpenMeteoClient openMeteoClient;

    @Mock
    private SolarService solarService;

    @Mock
    private LunarCalculator lunarCalculator;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private AstroConditionsRepository astroConditionsRepository;

    private AstroConditionsService service;

    @BeforeEach
    void setUp() {
        service = new AstroConditionsService(
                openMeteoClient, solarService, lunarCalculator,
                locationRepository, astroConditionsRepository);
    }

    // -------------------------------------------------------------------------
    // Cloud scoring
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Cloud scoring")
    class CloudScoringTests {

        @Test
        @DisplayName("Clear skies (< 20%) gives +1.0 modifier")
        void clearSkies_givesPositiveModifier() {
            List<NightHour> hours = nightHoursWithCloud(10, 10, 10, 10);

            CloudResult result = service.scoreCloud(hours);

            assertThat(result.modifier()).isEqualTo(1.0);
            assertThat(result.meanPct()).isEqualTo(10);
        }

        @Test
        @DisplayName("Mostly clear (20-40%) gives +0.5 modifier")
        void mostlyClear_givesHalfModifier() {
            List<NightHour> hours = nightHoursWithCloud(30, 30, 30, 30);

            CloudResult result = service.scoreCloud(hours);

            assertThat(result.modifier()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("Broken cloud (40-60%) gives 0 modifier")
        void brokenCloud_givesZeroModifier() {
            List<NightHour> hours = nightHoursWithCloud(50, 50, 50, 50);

            CloudResult result = service.scoreCloud(hours);

            assertThat(result.modifier()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Mostly cloudy (60-80%) gives -1.0 modifier")
        void mostlyCloudy_givesNegativeModifier() {
            List<NightHour> hours = nightHoursWithCloud(70, 70, 70, 70);

            CloudResult result = service.scoreCloud(hours);

            assertThat(result.modifier()).isEqualTo(-1.0);
        }

        @Test
        @DisplayName("Overcast (> 80%) gives -1.5 modifier")
        void overcast_givesLargeNegativeModifier() {
            List<NightHour> hours = nightHoursWithCloud(90, 90, 90, 90);

            CloudResult result = service.scoreCloud(hours);

            assertThat(result.modifier()).isEqualTo(-1.5);
        }

        @ParameterizedTest(name = "mean cloud {0}% → modifier {1}")
        @DisplayName("Cloud modifier boundary values")
        @CsvSource({
                "19, 1.0",
                "20, 0.5",
                "39, 0.5",
                "40, 0.0",
                "59, 0.0",
                "60, -1.0",
                "79, -1.0",
                "80, -1.5"
        })
        void cloudModifier_boundaries(int cloudPct, double expectedModifier) {
            List<NightHour> hours = nightHoursWithCloud(cloudPct);

            CloudResult result = service.scoreCloud(hours);

            assertThat(result.modifier()).isEqualTo(expectedModifier);
        }
    }

    // -------------------------------------------------------------------------
    // Visibility scoring
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Visibility scoring")
    class VisibilityScoringTests {

        @Test
        @DisplayName("Crystal clear (> 20 km) gives +0.5 modifier")
        void crystalClear_givesPositiveModifier() {
            List<NightHour> hours = nightHoursWithVisibility(25000);

            VisibilityResult result = service.scoreVisibility(hours);

            assertThat(result.modifier()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("Good transparency (10-20 km) gives 0 modifier")
        void goodTransparency_givesZeroModifier() {
            List<NightHour> hours = nightHoursWithVisibility(15000);

            VisibilityResult result = service.scoreVisibility(hours);

            assertThat(result.modifier()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Dense fog (< 1 km) gives -1.5 modifier")
        void denseFog_givesLargeNegativeModifier() {
            List<NightHour> hours = nightHoursWithVisibility(500);

            VisibilityResult result = service.scoreVisibility(hours);

            assertThat(result.modifier()).isEqualTo(-1.5);
        }

        @ParameterizedTest(name = "mean visibility {0}m → modifier {1}")
        @DisplayName("Visibility modifier boundary values")
        @CsvSource({
                "20001, 0.5",
                "20000, 0.0",
                "10000, 0.0",
                "9999, -0.5",
                "5000, -0.5",
                "4999, -1.0",
                "1000, -1.0",
                "999, -1.5"
        })
        void visibilityModifier_boundaries(double visM, double expectedModifier) {
            List<NightHour> hours = nightHoursWithVisibility(visM);

            VisibilityResult result = service.scoreVisibility(hours);

            assertThat(result.modifier()).isEqualTo(expectedModifier);
        }
    }

    // -------------------------------------------------------------------------
    // Moon scoring
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Moon scoring")
    class MoonScoringTests {

        @Test
        @DisplayName("Moon below horizon gives +0.5 modifier")
        void moonBelowHorizon_givesBonus() {
            LunarPosition moon = lunarPosition(-10, 0.3, LunarPhase.WAXING_GIBBOUS);

            MoonResult result = service.scoreMoon(moon);

            assertThat(result.modifier()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("Moon below horizon with new moon phase mentions perfect dark sky")
        void moonBelowHorizon_newMoon_mentionsPerfectDarkSky() {
            LunarPosition moon = lunarPosition(-5, 0.02, LunarPhase.NEW_MOON);

            MoonResult result = service.scoreMoon(moon);

            assertThat(result.explanation()).contains("perfect dark sky");
        }

        @Test
        @DisplayName("Thin crescent above horizon gives +0.25 modifier")
        void thinCrescent_aboveHorizon_givesSmallBonus() {
            // illumination 0.20 → illuminationPercent() = 20%
            LunarPosition moon = lunarPosition(30, 0.20, LunarPhase.WAXING_CRESCENT);

            MoonResult result = service.scoreMoon(moon);

            assertThat(result.modifier()).isEqualTo(0.25);
        }

        @Test
        @DisplayName("Quarter moon gives 0 modifier")
        void quarterMoon_givesZeroModifier() {
            // illumination 0.40 → illuminationPercent() = 40%
            LunarPosition moon = lunarPosition(45, 0.40, LunarPhase.FIRST_QUARTER);

            MoonResult result = service.scoreMoon(moon);

            assertThat(result.modifier()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Gibbous moon gives -0.5 modifier")
        void gibbousMoon_givesNegativeModifier() {
            // illumination 0.60 → illuminationPercent() = 60%
            LunarPosition moon = lunarPosition(50, 0.60, LunarPhase.WAXING_GIBBOUS);

            MoonResult result = service.scoreMoon(moon);

            assertThat(result.modifier()).isEqualTo(-0.5);
        }

        @Test
        @DisplayName("Bright full moon gives -1.0 modifier")
        void brightFullMoon_givesLargeNegativeModifier() {
            // illumination 0.95 → illuminationPercent() = 95%
            LunarPosition moon = lunarPosition(60, 0.95, LunarPhase.FULL_MOON);

            MoonResult result = service.scoreMoon(moon);

            assertThat(result.modifier()).isEqualTo(-1.0);
        }

        @ParameterizedTest(name = "illumination {0}% → modifier {1}")
        @DisplayName("Moon modifier boundary values")
        @CsvSource({
                "0.24, 0.25",
                "0.25, 0.0",
                "0.49, 0.0",
                "0.50, -0.5",
                "0.74, -0.5",
                "0.75, -1.0"
        })
        void moonModifier_boundaries(double illumination, double expectedModifier) {
            LunarPosition moon = lunarPosition(45, illumination, LunarPhase.WAXING_GIBBOUS);

            MoonResult result = service.scoreMoon(moon);

            assertThat(result.modifier()).isEqualTo(expectedModifier);
        }
    }

    // -------------------------------------------------------------------------
    // Fog hard cap
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Fog hard cap")
    class FogHardCapTests {

        @Test
        @DisplayName("Persistent fog (all hours < 1 km) hard caps to 1 star")
        void persistentFog_hardCapsTo1Star() {
            LocationEntity location = buildLocation(1L, "Kielder", 3);
            LocalDate date = LocalDate.of(2026, 6, 21);
            LocalDateTime dusk = LocalDateTime.of(2026, 6, 21, 21, 30);
            LocalDateTime dawn = LocalDateTime.of(2026, 6, 22, 3, 0);

            // All hours < 1000m visibility — fog hard cap
            OpenMeteoForecastResponse forecast = buildForecast(dusk, dawn,
                    List.of(5, 5, 5, 5, 5), List.of(500.0, 800.0, 900.0, 700.0, 600.0));

            // Moon below horizon (best possible) — would normally be 5 stars
            when(lunarCalculator.calculate(any(), anyDouble(), anyDouble()))
                    .thenReturn(lunarPosition(-10, 0.01, LunarPhase.NEW_MOON));

            AstroConditionsEntity result = service.scoreLocation(
                    location, date, dusk, dawn, Instant.now(),
                    forecastMap(location, forecast));

            assertThat(result).isNotNull();
            assertThat(result.getStars()).isEqualTo(1);
            assertThat(result.isFogCapped()).isTrue();
        }

        @Test
        @DisplayName("Partial fog (some hours >= 1 km) does not trigger hard cap")
        void partialFog_noHardCap() {
            LocationEntity location = buildLocation(1L, "Kielder", 3);
            LocalDate date = LocalDate.of(2026, 6, 21);
            LocalDateTime dusk = LocalDateTime.of(2026, 6, 21, 21, 30);
            LocalDateTime dawn = LocalDateTime.of(2026, 6, 22, 3, 0);

            // One hour has >= 1000m — no fog cap
            OpenMeteoForecastResponse forecast = buildForecast(dusk, dawn,
                    List.of(10, 10, 10, 10, 10), List.of(500.0, 800.0, 1500.0, 700.0, 600.0));

            when(lunarCalculator.calculate(any(), anyDouble(), anyDouble()))
                    .thenReturn(lunarPosition(-10, 0.01, LunarPhase.NEW_MOON));

            AstroConditionsEntity result = service.scoreLocation(
                    location, date, dusk, dawn, Instant.now(),
                    forecastMap(location, forecast));

            assertThat(result).isNotNull();
            assertThat(result.isFogCapped()).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // End-to-end scoring
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("End-to-end scoring")
    class EndToEndScoringTests {

        @Test
        @DisplayName("Clear skies + dark moon → 5 stars")
        void clearSkies_darkMoon_returns5Stars() {
            LocationEntity location = buildLocation(1L, "Kielder", 2);
            LocalDate date = LocalDate.of(2026, 6, 21);
            LocalDateTime dusk = LocalDateTime.of(2026, 6, 21, 21, 30);
            LocalDateTime dawn = LocalDateTime.of(2026, 6, 22, 3, 0);

            // Clear skies (5% cloud), excellent visibility (30 km), new moon below horizon
            OpenMeteoForecastResponse forecast = buildForecast(dusk, dawn,
                    List.of(5, 5, 5, 5, 5), List.of(30000.0, 30000.0, 30000.0, 30000.0, 30000.0));

            when(lunarCalculator.calculate(any(), anyDouble(), anyDouble()))
                    .thenReturn(lunarPosition(-10, 0.01, LunarPhase.NEW_MOON));

            AstroConditionsEntity result = service.scoreLocation(
                    location, date, dusk, dawn, Instant.now(),
                    forecastMap(location, forecast));

            // base 3 + cloud 1.0 + vis 0.5 + moon 0.5 = 5.0
            assertThat(result).isNotNull();
            assertThat(result.getStars()).isEqualTo(5);
        }

        @Test
        @DisplayName("Overcast + poor visibility + bright moon → 1 star")
        void overcast_poorVis_brightMoon_returns1Star() {
            LocationEntity location = buildLocation(1L, "Durham", 4);
            LocalDate date = LocalDate.of(2026, 12, 21);
            LocalDateTime dusk = LocalDateTime.of(2026, 12, 21, 17, 0);
            LocalDateTime dawn = LocalDateTime.of(2026, 12, 22, 7, 0);

            // 90% cloud, 2 km visibility
            OpenMeteoForecastResponse forecast = buildForecast(dusk, dawn,
                    List.of(90, 90, 90, 90, 90),
                    List.of(2000.0, 2000.0, 2000.0, 2000.0, 2000.0));

            when(lunarCalculator.calculate(any(), anyDouble(), anyDouble()))
                    .thenReturn(lunarPosition(60, 0.95, LunarPhase.FULL_MOON));

            AstroConditionsEntity result = service.scoreLocation(
                    location, date, dusk, dawn, Instant.now(),
                    forecastMap(location, forecast));

            // base 3 + cloud -1.5 + vis -1.0 + moon -1.0 = -0.5 → clamped to 1
            assertThat(result).isNotNull();
            assertThat(result.getStars()).isEqualTo(1);
        }

        @Test
        @DisplayName("Score is clamped between 1 and 5")
        void score_isClamped_between1And5() {
            assertThat(AstroConditionsService.clamp(0, 1, 5)).isEqualTo(1);
            assertThat(AstroConditionsService.clamp(-3, 1, 5)).isEqualTo(1);
            assertThat(AstroConditionsService.clamp(6, 1, 5)).isEqualTo(5);
            assertThat(AstroConditionsService.clamp(10, 1, 5)).isEqualTo(5);
            assertThat(AstroConditionsService.clamp(3, 1, 5)).isEqualTo(3);
        }

        @Test
        @DisplayName("Null forecast returns null")
        void nullForecast_returnsNull() {
            LocationEntity location = buildLocation(1L, "Durham", 3);

            AstroConditionsEntity result = service.scoreLocation(
                    location, LocalDate.of(2026, 6, 21),
                    LocalDateTime.of(2026, 6, 21, 21, 30),
                    LocalDateTime.of(2026, 6, 22, 3, 0),
                    Instant.now(), Map.of());

            assertThat(result).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // Night hour extraction
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Night hour extraction")
    class NightHourExtractionTests {

        @Test
        @DisplayName("Only hours within dusk-dawn window are extracted")
        void onlyNightHours_areExtracted() {
            LocalDateTime dusk = LocalDateTime.of(2026, 6, 21, 22, 0);
            LocalDateTime dawn = LocalDateTime.of(2026, 6, 22, 2, 0);

            OpenMeteoForecastResponse forecast = buildForecast(
                    LocalDateTime.of(2026, 6, 21, 20, 0),
                    LocalDateTime.of(2026, 6, 22, 4, 0),
                    List.of(10, 20, 30, 40, 50, 60, 70, 80),
                    List.of(10000.0, 10000.0, 10000.0, 10000.0,
                            10000.0, 10000.0, 10000.0, 10000.0));

            List<NightHour> result = service.extractNightHours(forecast, dusk, dawn);

            // Hours 22:00, 23:00, 00:00, 01:00 are within window (22:00 <= h < 02:00)
            assertThat(result).hasSize(4);
        }

        @Test
        @DisplayName("Empty forecast returns empty night hours")
        void emptyForecast_returnsEmptyHours() {
            OpenMeteoForecastResponse forecast = new OpenMeteoForecastResponse();
            OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
            hourly.setTime(Collections.emptyList());
            forecast.setHourly(hourly);

            List<NightHour> result = service.extractNightHours(forecast,
                    LocalDateTime.of(2026, 6, 21, 22, 0),
                    LocalDateTime.of(2026, 6, 22, 3, 0));

            assertThat(result).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // Summary generation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Summary generation")
    class SummaryTests {

        @Test
        @DisplayName("Fog-capped summary mentions fog")
        void fogCapped_mentionsFog() {
            String summary = service.buildSummary(1,
                    new CloudResult(1.0, "Clear skies", 10),
                    new VisibilityResult(-1.5, "Dense fog", 500),
                    new MoonResult(0.5, "Moon below horizon"),
                    true);

            assertThat(summary).containsIgnoringCase("fog");
        }

        @Test
        @DisplayName("High star summary includes cloud and moon explanations")
        void highStars_includesCloudAndMoon() {
            String summary = service.buildSummary(5,
                    new CloudResult(1.0, "Clear skies", 10),
                    new VisibilityResult(0.5, "Crystal clear air", 25000),
                    new MoonResult(0.5, "Moon below horizon"),
                    false);

            assertThat(summary).contains("Clear skies");
            assertThat(summary).contains("Moon below horizon");
        }

        @Test
        @DisplayName("Low star summary leads with worst factor")
        void lowStars_leadsWithWorstFactor() {
            String summary = service.buildSummary(2,
                    new CloudResult(-1.5, "Overcast", 90),
                    new VisibilityResult(-0.5, "Some haze", 7000),
                    new MoonResult(0.0, "Quarter moon"),
                    false);

            // Cloud is worst (-1.5) so should appear first
            assertThat(summary).startsWith("Overcast");
        }
    }

    // -------------------------------------------------------------------------
    // evaluateAndPersist integration
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("evaluateAndPersist")
    class EvaluateAndPersistTests {

        @Test
        @DisplayName("Only dark-sky locations (bortleClass not null + enabled) are scored")
        void onlyDarkSkyLocations_areScored() {
            List<LocalDate> dates = List.of(LocalDate.of(2026, 6, 21));
            when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                    .thenReturn(Collections.emptyList());

            int result = service.evaluateAndPersist(dates);

            assertThat(result).isZero();
            verify(locationRepository).findByBortleClassIsNotNullAndEnabledTrue();
        }

        @Test
        @DisplayName("Results are persisted via saveAll")
        void results_arePersistedViaSaveAll() {
            List<LocalDate> dates = List.of(LocalDate.of(2026, 6, 21));
            LocationEntity location = buildLocation(1L, "Kielder", 2);
            when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                    .thenReturn(List.of(location));

            LocalDateTime dusk = LocalDateTime.of(2026, 6, 21, 21, 30);
            LocalDateTime dawn = LocalDateTime.of(2026, 6, 22, 3, 0);
            when(solarService.nauticalDuskUtc(anyDouble(), anyDouble(), any())).thenReturn(dusk);
            when(solarService.nauticalDawnUtc(anyDouble(), anyDouble(), any())).thenReturn(dawn);

            OpenMeteoForecastResponse forecast = buildForecast(dusk, dawn,
                    List.of(10, 10, 10, 10, 10),
                    List.of(25000.0, 25000.0, 25000.0, 25000.0, 25000.0));
            when(openMeteoClient.fetchForecastBatch(any())).thenReturn(List.of(forecast));
            when(lunarCalculator.calculate(any(), anyDouble(), anyDouble()))
                    .thenReturn(lunarPosition(-10, 0.01, LunarPhase.NEW_MOON));

            int result = service.evaluateAndPersist(dates);

            assertThat(result).isEqualTo(1);
            verify(astroConditionsRepository).findByForecastDateIn(dates);
            verify(astroConditionsRepository).saveAll(any());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static LocationEntity buildLocation(long id, String name, int bortleClass) {
        return LocationEntity.builder()
                .id(id)
                .name(name)
                .lat(54.776)
                .lon(-1.575)
                .bortleClass(bortleClass)
                .enabled(true)
                .build();
    }

    private static LunarPosition lunarPosition(double altitude, double illumination,
                                                LunarPhase phase) {
        return new LunarPosition(altitude, 180.0, illumination, phase, 384400);
    }

    private static List<NightHour> nightHoursWithCloud(int... cloudPcts) {
        List<NightHour> hours = new ArrayList<>();
        for (int pct : cloudPcts) {
            hours.add(new NightHour(pct, 20000));
        }
        return hours;
    }

    private static List<NightHour> nightHoursWithVisibility(double visM) {
        return List.of(
                new NightHour(20, visM),
                new NightHour(20, visM),
                new NightHour(20, visM),
                new NightHour(20, visM));
    }

    private static Map<String, OpenMeteoForecastResponse> forecastMap(
            LocationEntity location, OpenMeteoForecastResponse forecast) {
        String key = OpenMeteoService.coordKey(location.getLat(), location.getLon());
        return Map.of(key, forecast);
    }

    /**
     * Builds a forecast response spanning from {@code dusk} to {@code dawn} with hourly entries.
     */
    private static OpenMeteoForecastResponse buildForecast(
            LocalDateTime dusk, LocalDateTime dawn,
            List<Integer> cloudPcts, List<Double> visibilities) {

        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();

        List<String> times = new ArrayList<>();
        List<Integer> cloudLow = new ArrayList<>();
        List<Integer> cloudMid = new ArrayList<>();
        List<Integer> cloudHigh = new ArrayList<>();
        List<Double> vis = new ArrayList<>();

        LocalDateTime time = dusk;
        for (int i = 0; i < cloudPcts.size(); i++) {
            times.add(time.toString());
            cloudLow.add(cloudPcts.get(i));
            cloudMid.add(0);
            cloudHigh.add(0);
            vis.add(visibilities.get(i));
            time = time.plusHours(1);
        }

        hourly.setTime(times);
        hourly.setCloudCoverLow(cloudLow);
        hourly.setCloudCoverMid(cloudMid);
        hourly.setCloudCoverHigh(cloudHigh);
        hourly.setVisibility(vis);

        response.setHourly(hourly);
        return response;
    }
}
