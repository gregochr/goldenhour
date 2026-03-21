package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.client.MetOfficeSpaceWeatherScraper;
import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.AuroraForecastResultEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastPreview;
import com.gregochr.goldenhour.model.AuroraForecastRunRequest;
import com.gregochr.goldenhour.model.AuroraForecastRunResponse;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.model.TonightWindow;
import com.gregochr.goldenhour.repository.AuroraForecastResultRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.solarutils.SolarCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuroraForecastRunService}.
 *
 * <p>Verifies the preview generation, per-night forecast pipeline, and database persistence
 * logic without making real HTTP or Claude API calls.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuroraForecastRunServiceTest {

    @Mock private NoaaSwpcClient noaaClient;
    @Mock private MetOfficeSpaceWeatherScraper metOfficeScraper;
    @Mock private WeatherTriageService weatherTriage;
    @Mock private ClaudeAuroraInterpreter claudeInterpreter;
    @Mock private LocationRepository locationRepository;
    @Mock private AuroraForecastResultRepository resultRepository;
    @Mock private SolarCalculator solarCalculator;

    private AuroraForecastRunService service;
    private AuroraProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AuroraProperties();
        service = new AuroraForecastRunService(noaaClient, metOfficeScraper, weatherTriage,
                claudeInterpreter, locationRepository, resultRepository, properties, solarCalculator);

        ZoneId utc = ZoneId.of("UTC");
        LocalDate today = LocalDate.now(utc);
        // Stub solar calculator for any date requests
        when(solarCalculator.civilDusk(eq(AuroraForecastRunService.DURHAM_LAT),
                eq(AuroraForecastRunService.DURHAM_LON), any(LocalDate.class), eq(utc)))
                .thenReturn(LocalDateTime.of(today, java.time.LocalTime.of(20, 0)));
        when(solarCalculator.civilDawn(eq(AuroraForecastRunService.DURHAM_LAT),
                eq(AuroraForecastRunService.DURHAM_LON), any(LocalDate.class), eq(utc)))
                .thenReturn(LocalDateTime.of(today.plusDays(1), java.time.LocalTime.of(4, 0)));
    }

    // -------------------------------------------------------------------------
    // computeWindowForDate
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("computeWindowForDate returns dusk + 35 min and dawn - 35 min")
    void computeWindowForDate_appliesNauticalBuffer() {
        LocalDate date = LocalDate.now(ZoneId.of("UTC"));
        TonightWindow window = service.computeWindowForDate(date);
        // dusk = 20:00 + 35 min = 20:35; dawn = 04:00 - 35 min = 03:25 (next day)
        assertThat(window.dusk().getHour()).isEqualTo(20);
        assertThat(window.dusk().getMinute()).isEqualTo(35);
        assertThat(window.dawn().getHour()).isEqualTo(3);
        assertThat(window.dawn().getMinute()).isEqualTo(25);
    }

    // -------------------------------------------------------------------------
    // gScaleFromKp
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("gScaleFromKp returns null below Kp 5")
    void gScale_nullBelowG1() {
        assertThat(service.gScaleFromKp(4.9)).isNull();
        assertThat(service.gScaleFromKp(0.0)).isNull();
    }

    @Test
    @DisplayName("gScaleFromKp maps Kp 5–9 to G1–G5")
    void gScale_correctLabels() {
        assertThat(service.gScaleFromKp(5.0)).isEqualTo("G1");
        assertThat(service.gScaleFromKp(5.9)).isEqualTo("G1");
        assertThat(service.gScaleFromKp(6.0)).isEqualTo("G2");
        assertThat(service.gScaleFromKp(7.0)).isEqualTo("G3");
        assertThat(service.gScaleFromKp(8.0)).isEqualTo("G4");
        assertThat(service.gScaleFromKp(9.0)).isEqualTo("G5");
    }

    // -------------------------------------------------------------------------
    // buildDateLabel
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("buildDateLabel returns Tonight, Tomorrow, or day name")
    void buildDateLabel_correctLabels() {
        LocalDate date = LocalDate.of(2026, 3, 21);
        assertThat(service.buildDateLabel(date, 0)).startsWith("Tonight — ");
        assertThat(service.buildDateLabel(date, 1)).startsWith("Tomorrow — ");
        assertThat(service.buildDateLabel(date, 2)).doesNotContain("Tonight").doesNotContain("Tomorrow");
    }

    // -------------------------------------------------------------------------
    // maxKpInWindow
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("maxKpInWindow returns max Kp from overlapping forecast windows")
    void maxKpInWindow_returnsMax() {
        ZonedDateTime dusk = ZonedDateTime.parse("2026-03-21T20:35:00Z");
        ZonedDateTime dawn = ZonedDateTime.parse("2026-03-22T03:25:00Z");
        TonightWindow window = new TonightWindow(dusk, dawn);

        KpForecast inWindow = new KpForecast(
                ZonedDateTime.parse("2026-03-21T21:00:00Z"),
                ZonedDateTime.parse("2026-03-22T00:00:00Z"), 6.0);
        KpForecast outOfWindow = new KpForecast(
                ZonedDateTime.parse("2026-03-22T06:00:00Z"),
                ZonedDateTime.parse("2026-03-22T09:00:00Z"), 9.0);

        double max = service.maxKpInWindow(List.of(inWindow, outOfWindow), window);
        assertThat(max).isEqualTo(6.0);
    }

    @Test
    @DisplayName("maxKpInWindow returns 0.0 when no windows overlap")
    void maxKpInWindow_returnsZeroWhenNoOverlap() {
        ZonedDateTime dusk = ZonedDateTime.parse("2026-03-21T20:35:00Z");
        ZonedDateTime dawn = ZonedDateTime.parse("2026-03-22T03:25:00Z");
        TonightWindow window = new TonightWindow(dusk, dawn);

        KpForecast futureWindow = new KpForecast(
                ZonedDateTime.parse("2026-03-22T12:00:00Z"),
                ZonedDateTime.parse("2026-03-22T15:00:00Z"), 7.0);

        double max = service.maxKpInWindow(List.of(futureWindow), window);
        assertThat(max).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // getPreview
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getPreview returns 3 nights")
    void getPreview_returnsThreeNights() {
        when(noaaClient.fetchKpForecast()).thenReturn(List.of());
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of());

        AuroraForecastPreview preview = service.getPreview();

        assertThat(preview.nights()).hasSize(3);
    }

    @Test
    @DisplayName("getPreview marks Kp >= 5 nights as recommended")
    void getPreview_recommendedWhenKpAtThreshold() {
        ZoneId utc = ZoneId.of("UTC");
        LocalDate today = LocalDate.now(utc);
        ZonedDateTime dusk = today.atTime(20, 35).atZone(utc);
        ZonedDateTime dawn = today.plusDays(1).atTime(3, 25).atZone(utc);

        KpForecast highKp = new KpForecast(dusk, dawn, 5.0);
        when(noaaClient.fetchKpForecast()).thenReturn(List.of(highKp));
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of());

        AuroraForecastPreview preview = service.getPreview();

        // Tonight should be recommended (Kp >= 5)
        assertThat(preview.nights().get(0).recommended()).isTrue();
    }

    @Test
    @DisplayName("getPreview marks quiet nights as not recommended")
    void getPreview_notRecommendedWhenKpLow() {
        when(noaaClient.fetchKpForecast()).thenReturn(List.of());
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of());

        AuroraForecastPreview preview = service.getPreview();

        // No forecast windows → all quiet → none recommended
        assertThat(preview.nights()).allMatch(n -> !n.recommended());
    }

    // -------------------------------------------------------------------------
    // runForecast
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("runForecast returns no_activity when Kp is near zero")
    void runForecast_noActivity_whenKpLow() {
        when(noaaClient.fetchAll()).thenReturn(quietSpaceWeather());
        when(metOfficeScraper.getForecastText()).thenReturn(null);

        LocalDate date = LocalDate.now(ZoneId.of("UTC"));
        AuroraForecastRunResponse response = service.runForecast(
                new AuroraForecastRunRequest(List.of(date)));

        assertThat(response.nights()).hasSize(1);
        assertThat(response.nights().get(0).status()).isEqualTo("no_activity");
        assertThat(response.totalClaudeCalls()).isZero();
        verify(claudeInterpreter, never()).interpret(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("runForecast calls Claude once per viable night")
    void runForecast_oneClaudeCallPerNight() {
        LocalDate tonight = LocalDate.now(ZoneId.of("UTC"));
        ZoneId utc = ZoneId.of("UTC");
        ZonedDateTime dusk = tonight.atTime(20, 35).atZone(utc);
        ZonedDateTime dawn = tonight.plusDays(1).atTime(3, 25).atZone(utc);

        SpaceWeatherData data = new SpaceWeatherData(
                List.of(),
                List.of(new KpForecast(dusk, dawn, 6.0)),
                null, List.of(), List.of());

        LocationEntity loc = LocationEntity.builder()
                .id(1L).name("Test Location").lat(55.0).lon(-1.5).bortleClass(3).build();

        when(noaaClient.fetchAll()).thenReturn(data);
        when(metOfficeScraper.getForecastText()).thenReturn("Met Office text");
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of(loc));
        when(weatherTriage.triage(any())).thenReturn(
                new WeatherTriageService.TriageResult(List.of(loc), List.of(), Map.of(loc, 30)));
        when(claudeInterpreter.interpret(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new AuroraForecastScore(loc, 4, AlertLevel.MODERATE, 30,
                        "Excellent conditions", "✓ Geomagnetic: MODERATE\n✓ Cloud: 30%")));
        when(resultRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        AuroraForecastRunResponse response = service.runForecast(
                new AuroraForecastRunRequest(List.of(tonight)));

        assertThat(response.totalClaudeCalls()).isEqualTo(1);
        assertThat(response.nights().get(0).status()).isEqualTo("scored");
        assertThat(response.nights().get(0).locationsScored()).isEqualTo(1);
    }

    @Test
    @DisplayName("runForecast persists triaged locations as 1-star with triage_template source")
    void runForecast_persistsTriagedLocations() {
        LocalDate tonight = LocalDate.now(ZoneId.of("UTC"));
        ZoneId utc = ZoneId.of("UTC");
        ZonedDateTime dusk = tonight.atTime(20, 35).atZone(utc);
        ZonedDateTime dawn = tonight.plusDays(1).atTime(3, 25).atZone(utc);

        SpaceWeatherData data = new SpaceWeatherData(
                List.of(),
                List.of(new KpForecast(dusk, dawn, 5.5)),
                null, List.of(), List.of());

        LocationEntity viableLoc = LocationEntity.builder()
                .id(1L).name("Clear Sky").lat(55.0).lon(-1.5).bortleClass(3).build();
        LocationEntity triageLoc = LocationEntity.builder()
                .id(2L).name("Overcast Bay").lat(54.0).lon(-2.0).bortleClass(2).build();

        when(noaaClient.fetchAll()).thenReturn(data);
        when(metOfficeScraper.getForecastText()).thenReturn(null);
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of(viableLoc, triageLoc));
        when(weatherTriage.triage(any())).thenReturn(
                new WeatherTriageService.TriageResult(
                        List.of(viableLoc), List.of(triageLoc),
                        Map.of(viableLoc, 20, triageLoc, 95)));
        when(claudeInterpreter.interpret(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new AuroraForecastScore(viableLoc, 3, AlertLevel.MODERATE, 20,
                        "Good conditions", "✓ Cloud: 20%")));

        ArgumentCaptor<AuroraForecastResultEntity> savedCaptor =
                ArgumentCaptor.forClass(AuroraForecastResultEntity.class);
        when(resultRepository.save(savedCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        service.runForecast(new AuroraForecastRunRequest(List.of(tonight)));

        List<AuroraForecastResultEntity> saved = savedCaptor.getAllValues();
        assertThat(saved).hasSize(2);

        AuroraForecastResultEntity triaged = saved.stream()
                .filter(AuroraForecastResultEntity::isTriaged).findFirst().orElseThrow();
        assertThat(triaged.getStars()).isEqualTo(1);
        assertThat(triaged.getSource()).isEqualTo("triage_template");
        assertThat(triaged.getLocation().getName()).isEqualTo("Overcast Bay");

        AuroraForecastResultEntity claude = saved.stream()
                .filter(e -> !e.isTriaged()).findFirst().orElseThrow();
        assertThat(claude.getSource()).isEqualTo("claude");
        assertThat(claude.getStars()).isEqualTo(3);
    }

    @Test
    @DisplayName("runForecast deletes existing results before inserting new ones")
    void runForecast_deletesExistingResults() {
        LocalDate tonight = LocalDate.now(ZoneId.of("UTC"));
        when(noaaClient.fetchAll()).thenReturn(quietSpaceWeather());
        when(metOfficeScraper.getForecastText()).thenReturn(null);

        service.runForecast(new AuroraForecastRunRequest(List.of(tonight)));

        verify(resultRepository).deleteByForecastDateIn(List.of(tonight));
    }

    @Test
    @DisplayName("runForecast skips Claude when all locations are triaged")
    void runForecast_skipsClaudeWhenAllTriaged() {
        LocalDate tonight = LocalDate.now(ZoneId.of("UTC"));
        ZoneId utc = ZoneId.of("UTC");
        ZonedDateTime dusk = tonight.atTime(20, 35).atZone(utc);
        ZonedDateTime dawn = tonight.plusDays(1).atTime(3, 25).atZone(utc);

        SpaceWeatherData data = new SpaceWeatherData(
                List.of(),
                List.of(new KpForecast(dusk, dawn, 6.0)),
                null, List.of(), List.of());

        LocationEntity overcastLoc = LocationEntity.builder()
                .id(1L).name("Overcast").lat(55.0).lon(-1.5).bortleClass(2).build();

        when(noaaClient.fetchAll()).thenReturn(data);
        when(metOfficeScraper.getForecastText()).thenReturn(null);
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of(overcastLoc));
        when(weatherTriage.triage(any())).thenReturn(
                new WeatherTriageService.TriageResult(
                        List.of(), List.of(overcastLoc), Map.of(overcastLoc, 90)));
        when(resultRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        AuroraForecastRunResponse response = service.runForecast(
                new AuroraForecastRunRequest(List.of(tonight)));

        verify(claudeInterpreter, never()).interpret(any(), any(), any(), any(), any(), any(), any());
        assertThat(response.nights().get(0).status()).isEqualTo("all_triaged");
        assertThat(response.totalClaudeCalls()).isZero();
    }

    @Test
    @DisplayName("runForecast returns empty response for empty request")
    void runForecast_emptyRequest_returnsEmpty() {
        AuroraForecastRunResponse response = service.runForecast(
                new AuroraForecastRunRequest(List.of()));
        assertThat(response.nights()).isEmpty();
        assertThat(response.totalClaudeCalls()).isZero();
    }

    @Test
    @DisplayName("runForecast uses future-night triage (no real triage) for T+1")
    void runForecast_futureDateSkipsWeatherTriage() {
        LocalDate tomorrow = LocalDate.now(ZoneId.of("UTC")).plusDays(1);
        ZoneId utc = ZoneId.of("UTC");
        // The solar calculator stub always returns today's times for any date.
        // So the dark window for tomorrow is: today 20:35 → today+1 03:25 (from setUp stubs).
        // We need the KpForecast to overlap that window.
        LocalDate today = LocalDate.now(utc);
        ZonedDateTime dusk = today.atTime(20, 35).atZone(utc);
        ZonedDateTime dawn = today.plusDays(1).atTime(3, 25).atZone(utc);

        SpaceWeatherData data = new SpaceWeatherData(
                List.of(),
                List.of(new KpForecast(dusk, dawn, 5.0)),
                null, List.of(), List.of());

        LocationEntity loc = LocationEntity.builder()
                .id(1L).name("Future Location").lat(55.0).lon(-1.5).bortleClass(3).build();

        when(noaaClient.fetchAll()).thenReturn(data);
        when(metOfficeScraper.getForecastText()).thenReturn(null);
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of(loc));
        when(claudeInterpreter.interpret(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new AuroraForecastScore(loc, 3, AlertLevel.MODERATE, 50,
                        "Possible aurora", "✓ Geomagnetic: MODERATE")));
        when(resultRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.runForecast(new AuroraForecastRunRequest(List.of(tomorrow)));

        // weatherTriage.triage() should NOT be called for a future date
        verify(weatherTriage, never()).triage(any());
        verify(claudeInterpreter, times(1)).interpret(any(), any(), any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Helper to build a quiet SpaceWeatherData (Kp near zero)
    // -------------------------------------------------------------------------

    private SpaceWeatherData quietSpaceWeather() {
        return new SpaceWeatherData(List.of(), List.of(), null, List.of(), List.of());
    }
}
