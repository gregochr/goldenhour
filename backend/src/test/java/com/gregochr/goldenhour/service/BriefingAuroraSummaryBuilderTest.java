package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.AuroraTonightSummary;
import com.gregochr.goldenhour.model.AuroraTomorrowSummary;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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

    private BriefingAuroraSummaryBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new BriefingAuroraSummaryBuilder(auroraStateCache, noaaSwpcClient);
    }

    @Test
    @DisplayName("buildAuroraTonight returns null when state machine is idle")
    void tonightNull_whenIdle() {
        when(auroraStateCache.isActive()).thenReturn(false);
        assertThat(builder.buildAuroraTonight()).isNull();
    }

    @Test
    @DisplayName("buildAuroraTonight returns summary with clear count when active")
    void tonightSummary_whenActive() {
        LocationEntity loc = location("Kielder", "Northumberland");
        AuroraForecastScore score = new AuroraForecastScore(
                loc, 4, AlertLevel.MODERATE, 40, "Active aurora", "Clear skies");
        when(auroraStateCache.isActive()).thenReturn(true);
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.0);
        when(auroraStateCache.getCachedScores()).thenReturn(List.of(score));

        AuroraTonightSummary summary = builder.buildAuroraTonight();

        assertThat(summary).isNotNull();
        assertThat(summary.alertLevel()).isEqualTo(AlertLevel.MODERATE);
        assertThat(summary.kp()).isEqualTo(5.0);
        assertThat(summary.clearLocationCount()).isEqualTo(1);
        assertThat(summary.regions()).hasSize(1);
    }

    @Test
    @DisplayName("buildAuroraTomorrow returns Quiet label when Kp < 3")
    void tomorrowQuiet() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        when(noaaSwpcClient.fetchKpForecast()).thenReturn(List.of(
                new KpForecast(now.plusHours(20), now.plusHours(23), 1.5)));

        AuroraTomorrowSummary summary = builder.buildAuroraTomorrow();

        assertThat(summary).isNotNull();
        assertThat(summary.label()).isEqualTo("Quiet");
        assertThat(summary.peakKp()).isEqualTo(1.5);
    }

    @Test
    @DisplayName("buildAuroraTomorrow returns Worth watching label when Kp >= 4")
    void tomorrowWorthWatching() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        when(noaaSwpcClient.fetchKpForecast()).thenReturn(List.of(
                new KpForecast(now.plusHours(24), now.plusHours(27), 4.33)));

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
