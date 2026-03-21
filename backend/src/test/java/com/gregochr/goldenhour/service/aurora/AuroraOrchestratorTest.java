package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.client.MetOfficeSpaceWeatherScraper;
import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.model.KpReading;
import com.gregochr.goldenhour.model.OvationReading;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuroraOrchestrator} — alert level derivation and pipeline control flow.
 */
@ExtendWith(MockitoExtension.class)
class AuroraOrchestratorTest {

    @Mock
    private NoaaSwpcClient noaaClient;
    @Mock
    private MetOfficeSpaceWeatherScraper metOfficeScraper;
    @Mock
    private WeatherTriageService weatherTriage;
    @Mock
    private ClaudeAuroraInterpreter claudeInterpreter;
    @Mock
    private AuroraStateCache stateCache;
    @Mock
    private LocationRepository locationRepository;

    private AuroraOrchestrator orchestrator;
    private AuroraProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AuroraProperties(); // uses all defaults: kpThreshold=5, ovation=20, etc.
        orchestrator = new AuroraOrchestrator(
                noaaClient, metOfficeScraper, weatherTriage, claudeInterpreter,
                stateCache, locationRepository, properties);
    }

    // -------------------------------------------------------------------------
    // deriveAlertLevel — Kp-based escalation
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "Kp={0} → {1}")
    @CsvSource({
            "2.0,  QUIET",
            "3.9,  QUIET",
            "4.0,  MINOR",
            "4.9,  MINOR",
            "5.0,  MODERATE",
            "6.9,  MODERATE",
            "7.0,  STRONG",
            "9.0,  STRONG",
    })
    @DisplayName("deriveAlertLevel maps Kp to correct AlertLevel (OVATION = 0)")
    void deriveAlertLevel_kpMappings(double kp, AlertLevel expected) {
        SpaceWeatherData data = spaceWeather(kp, 0.0, List.of());
        assertThat(orchestrator.deriveAlertLevel(data)).isEqualTo(expected);
    }

    @Test
    @DisplayName("deriveAlertLevel returns MODERATE when OVATION >= threshold even if Kp < 5")
    void deriveAlertLevel_ovationThresholdExceeded_returnsModerate() {
        // Default OVATION threshold = 20%
        SpaceWeatherData data = spaceWeather(3.0, 25.0, List.of());
        assertThat(orchestrator.deriveAlertLevel(data)).isEqualTo(AlertLevel.MODERATE);
    }

    @Test
    @DisplayName("deriveAlertLevel returns QUIET when OVATION < threshold and Kp < 4")
    void deriveAlertLevel_ovationBelowThreshold_returnsQuiet() {
        SpaceWeatherData data = spaceWeather(2.0, 15.0, List.of());
        assertThat(orchestrator.deriveAlertLevel(data)).isEqualTo(AlertLevel.QUIET);
    }

    @Test
    @DisplayName("deriveAlertLevel returns STRONG when forecast Kp >= 7 within lookahead window")
    void deriveAlertLevel_forecastKpStrong_returnsStrong() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        KpForecast imminent = new KpForecast(now.plusHours(2), now.plusHours(5), 7.5);
        SpaceWeatherData data = spaceWeather(2.0, 0.0, List.of(imminent));
        assertThat(orchestrator.deriveAlertLevel(data)).isEqualTo(AlertLevel.STRONG);
    }

    @Test
    @DisplayName("deriveAlertLevel ignores forecast windows beyond the lookahead horizon")
    void deriveAlertLevel_forecastBeyondLookahead_ignored() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        // 48h ahead — outside the default 6h window
        KpForecast distant = new KpForecast(now.plusHours(48), now.plusHours(51), 8.0);
        SpaceWeatherData data = spaceWeather(2.0, 0.0, List.of(distant));
        assertThat(orchestrator.deriveAlertLevel(data)).isEqualTo(AlertLevel.QUIET);
    }

    @Test
    @DisplayName("deriveAlertLevel returns QUIET when no Kp readings available")
    void deriveAlertLevel_noKpReadings_returnsQuiet() {
        SpaceWeatherData data = new SpaceWeatherData(List.of(), List.of(), null, List.of(), List.of());
        assertThat(orchestrator.deriveAlertLevel(data)).isEqualTo(AlertLevel.QUIET);
    }

    // -------------------------------------------------------------------------
    // run() — pipeline control flow
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("run returns NONE when NOAA fetch throws")
    void run_noaaFetchFails_returnsNone() {
        when(noaaClient.fetchAll()).thenThrow(new RuntimeException("network error"));

        AuroraStateCache.Action action = orchestrator.run();

        assertThat(action).isEqualTo(AuroraStateCache.Action.NONE);
        verify(stateCache, never()).evaluate(any());
    }

    @Test
    @DisplayName("run returns SUPPRESS without scoring when state machine suppresses")
    void run_suppressAction_noScoring() {
        SpaceWeatherData data = spaceWeather(5.5, 0.0, List.of());
        when(noaaClient.fetchAll()).thenReturn(data);
        var eval = new AuroraStateCache.Evaluation(AuroraStateCache.Action.SUPPRESS, AlertLevel.MODERATE, null);
        when(stateCache.evaluate(AlertLevel.MODERATE)).thenReturn(eval);

        AuroraStateCache.Action action = orchestrator.run();

        assertThat(action).isEqualTo(AuroraStateCache.Action.SUPPRESS);
        verify(locationRepository, never()).findByBortleClassLessThanEqualAndEnabledTrue(anyInt());
    }

    @Test
    @DisplayName("run triggers scoring pipeline on NOTIFY action")
    void run_notifyAction_triggersScoring() {
        SpaceWeatherData data = spaceWeather(6.0, 0.0, List.of());
        when(noaaClient.fetchAll()).thenReturn(data);
        when(stateCache.evaluate(AlertLevel.MODERATE))
                .thenReturn(new AuroraStateCache.Evaluation(AuroraStateCache.Action.NOTIFY, AlertLevel.MODERATE, null));

        LocationEntity loc = buildLocation(1L, "Cairngorms", 57.1, -3.8, 3);
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of(loc));

        WeatherTriageService.TriageResult triageResult = new WeatherTriageService.TriageResult(
                List.of(loc), List.of(), Map.of(loc, 30));
        when(weatherTriage.triage(List.of(loc))).thenReturn(triageResult);

        AuroraForecastScore score = new AuroraForecastScore(loc, 4, AlertLevel.MODERATE, 30,
                "Good aurora conditions", "✓ Active geomagnetic storm");
        when(metOfficeScraper.getForecastText()).thenReturn("Active G2 storm");
        when(claudeInterpreter.interpret(any(), any(), any(), any(), any()))
                .thenReturn(List.of(score));

        AuroraStateCache.Action action = orchestrator.run();

        assertThat(action).isEqualTo(AuroraStateCache.Action.NOTIFY);
        verify(claudeInterpreter).interpret(any(), any(), any(), any(), any());
        verify(stateCache).updateScores(any());
    }

    @Test
    @DisplayName("run caches 1-star score for overcast-rejected locations on NOTIFY")
    void run_notifyAction_overcastLocationsGetOneStar() {
        SpaceWeatherData data = spaceWeather(6.0, 0.0, List.of());
        when(noaaClient.fetchAll()).thenReturn(data);
        when(stateCache.evaluate(AlertLevel.MODERATE))
                .thenReturn(new AuroraStateCache.Evaluation(AuroraStateCache.Action.NOTIFY, AlertLevel.MODERATE, null));

        LocationEntity rejected = buildLocation(2L, "Cloudy", 55.0, -2.0, 3);
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of(rejected));

        WeatherTriageService.TriageResult triageResult = new WeatherTriageService.TriageResult(
                List.of(), List.of(rejected), Map.of(rejected, 95));
        when(weatherTriage.triage(List.of(rejected))).thenReturn(triageResult);

        orchestrator.run();

        verify(stateCache).updateScores(
                org.mockito.ArgumentMatchers.argThat(scores ->
                        scores.size() == 1 && scores.get(0).stars() == 1));
        verify(claudeInterpreter, never()).interpret(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("run returns CLEAR action and skips scoring when level drops")
    void run_clearAction_noScoring() {
        SpaceWeatherData data = spaceWeather(2.0, 0.0, List.of());
        when(noaaClient.fetchAll()).thenReturn(data);
        when(stateCache.evaluate(AlertLevel.QUIET))
                .thenReturn(new AuroraStateCache.Evaluation(AuroraStateCache.Action.CLEAR, null, AlertLevel.MODERATE));

        AuroraStateCache.Action action = orchestrator.run();

        assertThat(action).isEqualTo(AuroraStateCache.Action.CLEAR);
        verify(locationRepository, never()).findByBortleClassLessThanEqualAndEnabledTrue(anyInt());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SpaceWeatherData spaceWeather(double kp, double ovation, List<KpForecast> forecasts) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        List<KpReading> readings = List.of(new KpReading(now, kp));
        OvationReading ovationReading = new OvationReading(now, ovation, 55.0);
        return new SpaceWeatherData(readings, forecasts, ovationReading, List.of(), List.of());
    }

    private LocationEntity buildLocation(long id, String name, double lat, double lon, int bortle) {
        return LocationEntity.builder()
                .id(id).name(name).lat(lat).lon(lon).bortleClass(bortle).build();
    }
}
