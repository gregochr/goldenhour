package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.AuroraRegionSummary;
import com.gregochr.goldenhour.model.AuroraTonightSummary;
import com.gregochr.goldenhour.model.AuroraTomorrowSummary;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.evaluation.AuroraGlossService;
import com.gregochr.solarutils.LunarCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gregochr.goldenhour.model.SolarWindReading;
import com.gregochr.solarutils.LunarPhase;
import com.gregochr.solarutils.LunarPosition;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BriefingAuroraSummaryBuilder}.
 */
@ExtendWith(MockitoExtension.class)
class BriefingAuroraSummaryBuilderTest {

    @Mock
    private AuroraStateCache auroraStateCache;

    @Mock
    private NoaaSwpcClient noaaSwpcClient;

    @Mock
    private AuroraWeatherEnricher weatherEnricher;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private LunarCalculator lunarCalculator;

    @Mock
    private AuroraGlossService auroraGlossService;

    private BriefingAuroraSummaryBuilder builder;

    @BeforeEach
    void setUp() {
        // By default, gloss service passes through regions unchanged
        lenient().when(auroraGlossService.enrichGlosses(anyList(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        builder = new BriefingAuroraSummaryBuilder(
                auroraStateCache, noaaSwpcClient, weatherEnricher, locationRepository,
                lunarCalculator, auroraGlossService);
    }

    @Test
    @DisplayName("buildAuroraTonight returns null when state machine is idle")
    void tonightNull_whenIdle() {
        when(auroraStateCache.isActive()).thenReturn(false);
        assertThat(builder.buildAuroraTonight()).isNull();
    }

    @Test
    @DisplayName("buildAuroraTonight returns summary with weather when active")
    void tonightSummary_whenActive() {
        LocationEntity loc = location(1L, "Kielder", "Northumberland");
        AuroraForecastScore score = new AuroraForecastScore(
                loc, 4, AlertLevel.MODERATE, 40, "Active aurora", "Clear skies");
        when(auroraStateCache.isActive()).thenReturn(true);
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.0);
        when(auroraStateCache.getCachedScores()).thenReturn(List.of(score));
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenReturn(Map.of(1L, new AuroraWeatherEnricher.AuroraWeather(
                        40, 3.5, 4.2, 2)));

        AuroraTonightSummary summary = builder.buildAuroraTonight();

        assertThat(summary).isNotNull();
        assertThat(summary.alertLevel()).isEqualTo(AlertLevel.MODERATE);
        assertThat(summary.kp()).isEqualTo(5.0);
        assertThat(summary.clearLocationCount()).isEqualTo(1);
        assertThat(summary.regions()).hasSize(1);

        AuroraRegionSummary region = summary.regions().get(0);
        assertThat(region.regionTemperatureCelsius()).isEqualTo(3.5);
        assertThat(region.regionWindSpeedMs()).isEqualTo(4.2);
        assertThat(region.regionWeatherCode()).isEqualTo(2);
        assertThat(region.locations().get(0).temperatureCelsius()).isEqualTo(3.5);
    }

    @Test
    @DisplayName("buildAuroraTonight gracefully handles weather enrichment failure")
    void tonightSummary_weatherEnrichmentFails() {
        LocationEntity loc = location(1L, "Kielder", "Northumberland");
        AuroraForecastScore score = new AuroraForecastScore(
                loc, 4, AlertLevel.MODERATE, 40, "Active aurora", "Clear skies");
        when(auroraStateCache.isActive()).thenReturn(true);
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.0);
        when(auroraStateCache.getCachedScores()).thenReturn(List.of(score));
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenThrow(new RuntimeException("Open-Meteo down"));

        AuroraTonightSummary summary = builder.buildAuroraTonight();

        assertThat(summary).isNotNull();
        assertThat(summary.regions()).hasSize(1);
        assertThat(summary.regions().get(0).regionTemperatureCelsius()).isNull();
    }

    @Test
    @DisplayName("buildAuroraTomorrow returns Quiet label when Kp < 3")
    void tomorrowQuiet() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        when(noaaSwpcClient.fetchKpForecast()).thenReturn(List.of(
                new KpForecast(now.plusHours(20), now.plusHours(23), 1.5)));
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(List.of());

        AuroraTomorrowSummary summary = builder.buildAuroraTomorrow();

        assertThat(summary).isNotNull();
        assertThat(summary.label()).isEqualTo("Quiet");
        assertThat(summary.peakKp()).isEqualTo(1.5);
        assertThat(summary.regions()).isNull();
    }

    @Test
    @DisplayName("buildAuroraTomorrow returns Worth watching label when Kp >= 4")
    void tomorrowWorthWatching() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        when(noaaSwpcClient.fetchKpForecast()).thenReturn(List.of(
                new KpForecast(now.plusHours(24), now.plusHours(27), 4.33)));
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(List.of());

        AuroraTomorrowSummary summary = builder.buildAuroraTomorrow();

        assertThat(summary).isNotNull();
        assertThat(summary.label()).isEqualTo("Worth watching");
    }

    @Test
    @DisplayName("buildAuroraTomorrow returns Potentially strong label when Kp >= 6")
    void tomorrowPotentiallyStrong() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        when(noaaSwpcClient.fetchKpForecast()).thenReturn(List.of(
                new KpForecast(now.plusHours(30), now.plusHours(33), 6.67)));
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(List.of());

        AuroraTomorrowSummary summary = builder.buildAuroraTomorrow();

        assertThat(summary).isNotNull();
        assertThat(summary.label()).isEqualTo("Potentially strong");
    }

    @Test
    @DisplayName("buildAuroraTomorrow returns null when forecast fetch throws")
    void tomorrowNull_onException() {
        when(noaaSwpcClient.fetchKpForecast())
                .thenThrow(new RuntimeException("NOAA unavailable"));

        assertThat(builder.buildAuroraTomorrow()).isNull();
    }

    @Test
    @DisplayName("buildAuroraTomorrow includes per-region weather and verdicts")
    void tomorrowWithRegions() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        when(noaaSwpcClient.fetchKpForecast()).thenReturn(List.of(
                new KpForecast(now.plusHours(24), now.plusHours(27), 5.0)));

        LocationEntity loc = location(2L, "Kielder", "Northumberland");
        loc.setBortleClass(3);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(List.of(loc));
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenReturn(Map.of(2L, new AuroraWeatherEnricher.AuroraWeather(
                        30, 1.0, 3.0, 0)));

        AuroraTomorrowSummary summary = builder.buildAuroraTomorrow();

        assertThat(summary).isNotNull();
        assertThat(summary.regions()).hasSize(1);
        AuroraRegionSummary region = summary.regions().get(0);
        assertThat(region.regionName()).isEqualTo("Northumberland");
        assertThat(region.verdict()).isEqualTo("GO");
        assertThat(region.regionTemperatureCelsius()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("buildAuroraTomorrow regions null when enrichment fails")
    void tomorrowRegionsNull_whenEnrichmentFails() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        when(noaaSwpcClient.fetchKpForecast()).thenReturn(List.of(
                new KpForecast(now.plusHours(24), now.plusHours(27), 5.0)));
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenThrow(new RuntimeException("DB error"));

        AuroraTomorrowSummary summary = builder.buildAuroraTomorrow();

        assertThat(summary).isNotNull();
        assertThat(summary.regions()).isNull();
    }

    @Test
    @DisplayName("buildAuroraTonight passes correct locations and midnight target to enricher")
    void tonightWeatherUseMidnightTarget() {
        LocationEntity loc = location(1L, "Kielder", "Northumberland");
        AuroraForecastScore score = new AuroraForecastScore(
                loc, 4, AlertLevel.MODERATE, 40, "Active aurora", "Clear skies");
        when(auroraStateCache.isActive()).thenReturn(true);
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.0);
        when(auroraStateCache.getCachedScores()).thenReturn(List.of(score));
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenReturn(Map.of());

        builder.buildAuroraTonight();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LocationEntity>> locCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ZonedDateTime> timeCaptor = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(weatherEnricher).fetchWeather(locCaptor.capture(), timeCaptor.capture());

        assertThat(locCaptor.getValue()).hasSize(1);
        assertThat(locCaptor.getValue().get(0).getName()).isEqualTo("Kielder");
        ZonedDateTime targetHour = timeCaptor.getValue();
        assertThat(targetHour.getHour()).isZero();
        assertThat(targetHour.getMinute()).isZero();
    }

    @Test
    @DisplayName("buildAuroraTomorrow passes Bortle locations and midnight target to enricher")
    void tomorrowWeatherUsesMidnightTarget() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        when(noaaSwpcClient.fetchKpForecast()).thenReturn(List.of(
                new KpForecast(now.plusHours(24), now.plusHours(27), 5.0)));
        LocationEntity loc = location(2L, "Kielder", "Northumberland");
        loc.setBortleClass(3);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(List.of(loc));
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenReturn(Map.of());

        builder.buildAuroraTomorrow();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LocationEntity>> locCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ZonedDateTime> timeCaptor = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(weatherEnricher).fetchWeather(locCaptor.capture(), timeCaptor.capture());

        assertThat(locCaptor.getValue()).hasSize(1);
        assertThat(locCaptor.getValue().get(0).getName()).isEqualTo("Kielder");
        ZonedDateTime targetHour = timeCaptor.getValue();
        assertThat(targetHour.getHour()).isZero();
        assertThat(targetHour.getMinute()).isZero();
        assertThat(targetHour).isAfter(now);
    }

    // ── Fresh weather overrides stale score cloud data ──

    @Test
    @DisplayName("Fresh enricher cloud data overrides stale score — score says clear, enricher says overcast")
    void tonightSummary_enricherOverridesStaleScore_clearToOvercast() {
        LocationEntity loc = location(1L, "Bamburgh", "Northumberland");
        // Score baked in hours ago when skies were clear (30% cloud)
        AuroraForecastScore score = new AuroraForecastScore(
                loc, 4, AlertLevel.MODERATE, 30, "Active aurora", "Clear skies");
        when(auroraStateCache.isActive()).thenReturn(true);
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.0);
        when(auroraStateCache.getCachedScores()).thenReturn(List.of(score));
        // Enricher returns fresh data showing storm has arrived (95% cloud)
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenReturn(Map.of(1L, new AuroraWeatherEnricher.AuroraWeather(
                        95, 2.0, 15.0, 63)));

        AuroraTonightSummary summary = builder.buildAuroraTonight();

        assertThat(summary.clearLocationCount()).isZero();
        AuroraRegionSummary region = summary.regions().get(0);
        assertThat(region.verdict()).isEqualTo("STANDDOWN");
        assertThat(region.clearLocationCount()).isZero();
        assertThat(region.locations().get(0).clear()).isFalse();
        assertThat(region.locations().get(0).cloudPercent()).isEqualTo(95);
    }

    @Test
    @DisplayName("Fresh enricher cloud data overrides stale score — score says overcast, enricher says clear")
    void tonightSummary_enricherOverridesStaleScore_overcastToClear() {
        LocationEntity loc = location(1L, "Kielder", "Northumberland");
        // Score baked in hours ago when storm was active (90% cloud)
        AuroraForecastScore score = new AuroraForecastScore(
                loc, 3, AlertLevel.MODERATE, 90, "Active aurora", "Overcast");
        when(auroraStateCache.isActive()).thenReturn(true);
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.0);
        when(auroraStateCache.getCachedScores()).thenReturn(List.of(score));
        // Enricher returns fresh data showing storm has passed (20% cloud)
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenReturn(Map.of(1L, new AuroraWeatherEnricher.AuroraWeather(
                        20, 5.0, 3.0, 0)));

        AuroraTonightSummary summary = builder.buildAuroraTonight();

        assertThat(summary.clearLocationCount()).isEqualTo(1);
        AuroraRegionSummary region = summary.regions().get(0);
        assertThat(region.verdict()).isEqualTo("GO");
        assertThat(region.clearLocationCount()).isEqualTo(1);
        assertThat(region.locations().get(0).clear()).isTrue();
        assertThat(region.locations().get(0).cloudPercent()).isEqualTo(20);
    }

    @Test
    @DisplayName("Falls back to score cloud data when enricher has no data for a location")
    void tonightSummary_fallsBackToScoreWhenEnricherMissing() {
        LocationEntity loc = location(1L, "Kielder", "Northumberland");
        AuroraForecastScore score = new AuroraForecastScore(
                loc, 4, AlertLevel.MODERATE, 40, "Active aurora", "Clear");
        when(auroraStateCache.isActive()).thenReturn(true);
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.0);
        when(auroraStateCache.getCachedScores()).thenReturn(List.of(score));
        // Enricher returns empty map — no fresh weather available
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenReturn(Map.of());

        AuroraTonightSummary summary = builder.buildAuroraTonight();

        // Should fall back to score's 40% cloud (clear)
        assertThat(summary.clearLocationCount()).isEqualTo(1);
        assertThat(summary.regions().get(0).locations().get(0).cloudPercent()).isEqualTo(40);
        assertThat(summary.regions().get(0).locations().get(0).clear()).isTrue();
    }

    // ── Multi-region with mixed weather ──

    @Test
    @DisplayName("Multi-region: one GO one STANDDOWN — clearCount is regional total, not stale score total")
    void tonightSummary_multiRegion_mixedWeather() {
        LocationEntity kielder = location(1L, "Kielder", "Northumberland");
        LocationEntity bamburgh = location(2L, "Bamburgh", "Northumberland");
        LocationEntity roseberry = location(3L, "Roseberry Topping", "North York Moors");

        // All scores say clear (baked in when weather was good)
        AuroraForecastScore s1 = new AuroraForecastScore(
                kielder, 4, AlertLevel.MODERATE, 20, "Aurora", "Clear");
        AuroraForecastScore s2 = new AuroraForecastScore(
                bamburgh, 3, AlertLevel.MODERATE, 25, "Aurora", "Clear");
        AuroraForecastScore s3 = new AuroraForecastScore(
                roseberry, 3, AlertLevel.MODERATE, 15, "Aurora", "Clear");

        when(auroraStateCache.isActive()).thenReturn(true);
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.5);
        when(auroraStateCache.getCachedScores()).thenReturn(List.of(s1, s2, s3));
        // Enricher: Northumberland stays clear, North York Moors now overcast
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenReturn(Map.of(
                        1L, new AuroraWeatherEnricher.AuroraWeather(25, 3.0, 4.0, 0),
                        2L, new AuroraWeatherEnricher.AuroraWeather(30, 3.5, 5.0, 0),
                        3L, new AuroraWeatherEnricher.AuroraWeather(85, 4.0, 12.0, 61)));

        AuroraTonightSummary summary = builder.buildAuroraTonight();

        // Total: 2 clear (both in Northumberland), not 3 (which stale scores would give)
        assertThat(summary.clearLocationCount()).isEqualTo(2);
        assertThat(summary.regions()).hasSize(2);

        AuroraRegionSummary northumberland = summary.regions().stream()
                .filter(r -> "Northumberland".equals(r.regionName())).findFirst().orElseThrow();
        assertThat(northumberland.verdict()).isEqualTo("GO");
        assertThat(northumberland.clearLocationCount()).isEqualTo(2);
        assertThat(northumberland.totalDarkSkyLocations()).isEqualTo(2);

        AuroraRegionSummary northYorkMoors = summary.regions().stream()
                .filter(r -> "North York Moors".equals(r.regionName())).findFirst().orElseThrow();
        assertThat(northYorkMoors.verdict()).isEqualTo("STANDDOWN");
        assertThat(northYorkMoors.clearLocationCount()).isZero();
        assertThat(northYorkMoors.locations().get(0).cloudPercent()).isEqualTo(85);
    }

    @Test
    @DisplayName("STANDDOWN verdict when every location in a region is overcast")
    void tonightSummary_allOvercast_standdown() {
        LocationEntity loc1 = location(1L, "Bamburgh", "Northumberland");
        LocationEntity loc2 = location(2L, "Embleton", "Northumberland");

        AuroraForecastScore s1 = new AuroraForecastScore(
                loc1, 3, AlertLevel.MODERATE, 20, "Aurora", "Clear");
        AuroraForecastScore s2 = new AuroraForecastScore(
                loc2, 3, AlertLevel.MODERATE, 25, "Aurora", "Clear");

        when(auroraStateCache.isActive()).thenReturn(true);
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.0);
        when(auroraStateCache.getCachedScores()).thenReturn(List.of(s1, s2));
        // Enricher: storm arrived, all overcast
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenReturn(Map.of(
                        1L, new AuroraWeatherEnricher.AuroraWeather(92, 3.0, 18.0, 65),
                        2L, new AuroraWeatherEnricher.AuroraWeather(88, 3.5, 16.0, 63)));

        AuroraTonightSummary summary = builder.buildAuroraTonight();

        assertThat(summary.clearLocationCount()).isZero();
        AuroraRegionSummary region = summary.regions().get(0);
        assertThat(region.verdict()).isEqualTo("STANDDOWN");
        assertThat(region.clearLocationCount()).isZero();
        assertThat(region.locations()).allSatisfy(slot -> {
            assertThat(slot.clear()).isFalse();
            assertThat(slot.cloudPercent()).isGreaterThanOrEqualTo(75);
        });
    }

    @Test
    @DisplayName("Cloud threshold boundary: 74% is clear, 75% is not")
    void tonightSummary_cloudThresholdBoundary() {
        LocationEntity clearLoc = location(1L, "Kielder", "Northumberland");
        LocationEntity borderLoc = location(2L, "Bamburgh", "Northumberland");

        AuroraForecastScore s1 = new AuroraForecastScore(
                clearLoc, 4, AlertLevel.MODERATE, 50, "Aurora", "Mixed");
        AuroraForecastScore s2 = new AuroraForecastScore(
                borderLoc, 3, AlertLevel.MODERATE, 50, "Aurora", "Mixed");

        when(auroraStateCache.isActive()).thenReturn(true);
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.0);
        when(auroraStateCache.getCachedScores()).thenReturn(List.of(s1, s2));
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenReturn(Map.of(
                        1L, new AuroraWeatherEnricher.AuroraWeather(74, 4.0, 3.0, 2),
                        2L, new AuroraWeatherEnricher.AuroraWeather(75, 4.0, 3.0, 3)));

        AuroraTonightSummary summary = builder.buildAuroraTonight();

        assertThat(summary.clearLocationCount()).isEqualTo(1);
        AuroraRegionSummary region = summary.regions().get(0);
        assertThat(region.clearLocationCount()).isEqualTo(1);
        assertThat(region.verdict()).isEqualTo("GO");
    }

    @Test
    @DisplayName("bestBortleClass reflects the darkest location in the region")
    void tonightSummary_bestBortleClass() {
        LocationEntity dark = location(1L, "Kielder", "Northumberland");
        dark.setBortleClass(2);
        LocationEntity moderate = location(2L, "Bamburgh", "Northumberland");
        moderate.setBortleClass(4);

        AuroraForecastScore s1 = new AuroraForecastScore(
                dark, 4, AlertLevel.MODERATE, 30, "Aurora", "Clear");
        AuroraForecastScore s2 = new AuroraForecastScore(
                moderate, 3, AlertLevel.MODERATE, 40, "Aurora", "Clear");

        when(auroraStateCache.isActive()).thenReturn(true);
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.0);
        when(auroraStateCache.getCachedScores()).thenReturn(List.of(s1, s2));
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenReturn(Map.of(
                        1L, new AuroraWeatherEnricher.AuroraWeather(30, 3.0, 4.0, 0),
                        2L, new AuroraWeatherEnricher.AuroraWeather(40, 4.0, 5.0, 0)));

        AuroraTonightSummary summary = builder.buildAuroraTonight();

        assertThat(summary.regions().get(0).bestBortleClass()).isEqualTo(2);
    }

    // ── Moon phase in tonight summary ──

    @Test
    @DisplayName("buildAuroraTonight populates moon phase, illumination, and above-horizon fields")
    void tonightSummary_includesMoonData() {
        LocationEntity loc = location(1L, "Kielder", "Northumberland");
        AuroraForecastScore score = new AuroraForecastScore(
                loc, 4, AlertLevel.MODERATE, 40, "Active aurora", "Clear skies");
        when(auroraStateCache.isActive()).thenReturn(true);
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.0);
        when(auroraStateCache.getCachedScores()).thenReturn(List.of(score));
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenReturn(Map.of(1L, new AuroraWeatherEnricher.AuroraWeather(
                        40, 3.5, 4.2, 2)));
        when(lunarCalculator.calculate(any(ZonedDateTime.class), anyDouble(), anyDouble()))
                .thenReturn(new LunarPosition(
                        25.0, 180.0, 0.73, LunarPhase.WAXING_GIBBOUS, 384400));

        AuroraTonightSummary summary = builder.buildAuroraTonight();

        assertThat(summary.moonPhase()).isEqualTo("WAXING_GIBBOUS");
        assertThat(summary.moonIlluminationPct()).isEqualTo(73.0);
        assertThat(summary.moonAboveHorizon()).isTrue();
    }

    @Test
    @DisplayName("buildAuroraTonight has null moon fields when lunar calculator fails")
    void tonightSummary_lunarCalcFails_nullMoonFields() {
        LocationEntity loc = location(1L, "Kielder", "Northumberland");
        AuroraForecastScore score = new AuroraForecastScore(
                loc, 4, AlertLevel.MODERATE, 40, "Active aurora", "Clear skies");
        when(auroraStateCache.isActive()).thenReturn(true);
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.0);
        when(auroraStateCache.getCachedScores()).thenReturn(List.of(score));
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenReturn(Map.of());
        when(lunarCalculator.calculate(any(ZonedDateTime.class), anyDouble(), anyDouble()))
                .thenThrow(new RuntimeException("Ephemeris error"));

        AuroraTonightSummary summary = builder.buildAuroraTonight();

        assertThat(summary).isNotNull();
        assertThat(summary.moonPhase()).isNull();
        assertThat(summary.moonIlluminationPct()).isNull();
        assertThat(summary.moonAboveHorizon()).isNull();
    }

    // ── Solar wind in tonight summary ──

    @Test
    @DisplayName("buildAuroraTonight populates solar wind speed from NOAA cached data")
    void tonightSummary_includesSolarWindSpeed() {
        LocationEntity loc = location(1L, "Kielder", "Northumberland");
        AuroraForecastScore score = new AuroraForecastScore(
                loc, 4, AlertLevel.MODERATE, 40, "Active aurora", "Clear skies");
        when(auroraStateCache.isActive()).thenReturn(true);
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.0);
        when(auroraStateCache.getCachedScores()).thenReturn(List.of(score));
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenReturn(Map.of());
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        when(noaaSwpcClient.getCachedSolarWind()).thenReturn(List.of(
                new SolarWindReading(now.minusMinutes(10), -4.0, 450.0, 5.0),
                new SolarWindReading(now, -6.0, 520.0, 8.0)));

        AuroraTonightSummary summary = builder.buildAuroraTonight();

        // Should use the last reading's speed
        assertThat(summary.solarWindSpeedKmPerSec()).isEqualTo(520.0);
    }

    @Test
    @DisplayName("buildAuroraTonight has null solar wind when NOAA cache is empty")
    void tonightSummary_noSolarWindCache_null() {
        LocationEntity loc = location(1L, "Kielder", "Northumberland");
        AuroraForecastScore score = new AuroraForecastScore(
                loc, 4, AlertLevel.MODERATE, 40, "Active aurora", "Clear skies");
        when(auroraStateCache.isActive()).thenReturn(true);
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.0);
        when(auroraStateCache.getCachedScores()).thenReturn(List.of(score));
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenReturn(Map.of());
        when(noaaSwpcClient.getCachedSolarWind()).thenReturn(null);

        AuroraTonightSummary summary = builder.buildAuroraTonight();

        assertThat(summary.solarWindSpeedKmPerSec()).isNull();
    }

    // ── Gloss service interaction ──

    @Test
    @DisplayName("buildAuroraTonight calls gloss service with correct alert level and kp")
    void tonightSummary_callsGlossServiceWithCorrectArgs() {
        LocationEntity loc = location(1L, "Kielder", "Northumberland");
        AuroraForecastScore score = new AuroraForecastScore(
                loc, 4, AlertLevel.STRONG, 40, "Active aurora", "Clear skies");
        when(auroraStateCache.isActive()).thenReturn(true);
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.STRONG);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(7.5);
        when(auroraStateCache.getCachedScores()).thenReturn(List.of(score));
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenReturn(Map.of(1L, new AuroraWeatherEnricher.AuroraWeather(
                        30, 3.5, 4.2, 0)));

        builder.buildAuroraTonight();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuroraRegionSummary>> regionsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(auroraGlossService).enrichGlosses(
                regionsCaptor.capture(), any(), eq(AlertLevel.STRONG), eq(7.5));

        assertThat(regionsCaptor.getValue()).hasSize(1);
        assertThat(regionsCaptor.getValue().get(0).regionName()).isEqualTo("Northumberland");
    }

    @Test
    @DisplayName("buildAuroraTonight still returns summary when gloss service throws")
    void tonightSummary_glossServiceFails_summaryStillReturned() {
        LocationEntity loc = location(1L, "Kielder", "Northumberland");
        AuroraForecastScore score = new AuroraForecastScore(
                loc, 4, AlertLevel.MODERATE, 40, "Active aurora", "Clear skies");
        when(auroraStateCache.isActive()).thenReturn(true);
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.0);
        when(auroraStateCache.getCachedScores()).thenReturn(List.of(score));
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenReturn(Map.of(1L, new AuroraWeatherEnricher.AuroraWeather(
                        30, 3.5, 4.2, 0)));
        when(auroraGlossService.enrichGlosses(anyList(), any(), any(), any()))
                .thenThrow(new RuntimeException("Gloss service down"));

        AuroraTonightSummary summary = builder.buildAuroraTonight();

        // The summary should still be returned — gloss failure must not break the briefing
        assertThat(summary).isNotNull();
        assertThat(summary.alertLevel()).isEqualTo(AlertLevel.MODERATE);
        assertThat(summary.regions()).hasSize(1);
    }

    @Test
    @DisplayName("buildAuroraTomorrow does not call gloss service (no active alert data)")
    void tomorrowSummary_noGlossCall() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        when(noaaSwpcClient.fetchKpForecast()).thenReturn(List.of(
                new KpForecast(now.plusHours(24), now.plusHours(27), 5.0)));
        LocationEntity loc = location(2L, "Kielder", "Northumberland");
        loc.setBortleClass(3);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(List.of(loc));
        when(weatherEnricher.fetchWeather(anyList(), any(ZonedDateTime.class)))
                .thenReturn(Map.of(2L, new AuroraWeatherEnricher.AuroraWeather(
                        30, 1.0, 3.0, 0)));

        builder.buildAuroraTomorrow();

        verify(auroraGlossService, never()).enrichGlosses(anyList(), any(), any(), any());
    }

    private static LocationEntity location(Long id, String name, String regionName) {
        RegionEntity region = regionName != null
                ? RegionEntity.builder().name(regionName).build() : null;
        return LocationEntity.builder()
                .id(id).name(name).lat(55.0).lon(-1.5)
                .locationType(Set.of(LocationType.LANDSCAPE))
                .tideType(Set.of())
                .solarEventType(Set.of())
                .bortleClass(3)
                .region(region)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
