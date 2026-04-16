package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuroraHotTopicStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class AuroraHotTopicStrategyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 16);
    private static final LocalDate TO_DATE = TODAY.plusDays(3);

    @Mock
    private AuroraStateCache auroraStateCache;

    @Mock
    private NoaaSwpcClient noaaSwpcClient;

    @Mock
    private LocationRepository locationRepository;

    private AuroraHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new AuroraHotTopicStrategy(auroraStateCache, noaaSwpcClient,
                locationRepository);
    }

    // ── Tonight detection ────────────────────────────────────────────────────

    @Test
    @DisplayName("MODERATE alert emits tonight pill with priority 1")
    void detect_moderateAlert_emitsTonightPillPriority1() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.2);
        when(auroraStateCache.getClearLocationCount()).thenReturn(8);
        stubDarkSkyLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        HotTopic tonight = topics.stream()
                .filter(t -> t.date().equals(TODAY))
                .findFirst().orElseThrow();
        assertThat(tonight.type()).isEqualTo("AURORA");
        assertThat(tonight.label()).isEqualTo("Aurora possible");
        assertThat(tonight.priority()).isEqualTo(1);
        assertThat(tonight.detail()).contains("Kp 5");
        assertThat(tonight.detail()).contains("8 dark-sky locations");
        assertThat(tonight.regions()).containsExactly("Northumberland");
        assertThat(tonight.description()).contains("aurora borealis");
    }

    @Test
    @DisplayName("STRONG alert emits tonight pill with priority 1")
    void detect_strongAlert_emitsTonightPillPriority1() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.STRONG);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(7.0);
        stubDarkSkyLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        HotTopic tonight = topics.stream()
                .filter(t -> t.date().equals(TODAY))
                .findFirst().orElseThrow();
        assertThat(tonight.priority()).isEqualTo(1);
    }

    @Test
    @DisplayName("MINOR alert emits tonight pill with priority 2")
    void detect_minorAlert_emitsTonightPillPriority2() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MINOR);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(4.0);
        stubDarkSkyLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        HotTopic tonight = topics.stream()
                .filter(t -> t.date().equals(TODAY))
                .findFirst().orElseThrow();
        assertThat(tonight.priority()).isEqualTo(2);
    }

    @Test
    @DisplayName("QUIET alert emits no tonight pill")
    void detect_quietAlert_emitsNothing() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.QUIET);

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.stream().filter(t -> t.date().equals(TODAY)).toList()).isEmpty();
    }

    @Test
    @DisplayName("null alert level emits no tonight pill")
    void detect_nullAlertLevel_emitsNothing() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(null);

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.stream().filter(t -> t.date().equals(TODAY)).toList()).isEmpty();
    }

    @Test
    @DisplayName("tonight pill shows 'Elevated activity' when Kp is null")
    void detect_nullKp_showsElevatedActivity() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MINOR);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(null);
        stubDarkSkyLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        HotTopic tonight = topics.stream()
                .filter(t -> t.date().equals(TODAY))
                .findFirst().orElseThrow();
        assertThat(tonight.detail()).startsWith("Elevated activity tonight");
    }

    @Test
    @DisplayName("tonight pill omits clear-sky suffix when count is zero")
    void detect_zeroClearCount_omitsSuffix() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MINOR);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(4.0);
        when(auroraStateCache.getClearLocationCount()).thenReturn(0);
        stubDarkSkyLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        HotTopic tonight = topics.stream()
                .filter(t -> t.date().equals(TODAY))
                .findFirst().orElseThrow();
        assertThat(tonight.detail()).doesNotContain("dark-sky");
    }

    // ── Tomorrow detection ───────────────────────────────────────────────────

    @Test
    @DisplayName("tomorrow Kp >= 4 emits informational pill with priority 3")
    void detect_tomorrowKpAboveThreshold_emitsPill() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.QUIET);
        stubTomorrowKpForecast(4.5);
        stubDarkSkyLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        HotTopic tomorrow = topics.get(0);
        assertThat(tomorrow.date()).isEqualTo(TODAY.plusDays(1));
        assertThat(tomorrow.priority()).isEqualTo(3);
        assertThat(tomorrow.detail()).contains("Kp 5");
        assertThat(tomorrow.detail()).contains("tomorrow night");
    }

    @Test
    @DisplayName("tomorrow Kp < 4 emits no tomorrow pill")
    void detect_tomorrowKpBelowThreshold_noPill() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.QUIET);
        stubTomorrowKpForecast(2.0);

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("null cached forecast emits no tomorrow pill")
    void detect_nullForecast_noTomorrowPill() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.QUIET);
        when(noaaSwpcClient.getCachedKpForecast()).thenReturn(null);

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("both tonight and tomorrow can emit simultaneously")
    void detect_bothTonightAndTomorrow_twoTopics() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.0);
        stubTomorrowKpForecast(5.0);
        stubDarkSkyLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(2);
        assertThat(topics.get(0).date()).isEqualTo(TODAY);
        assertThat(topics.get(1).date()).isEqualTo(TODAY.plusDays(1));
    }

    @Test
    @DisplayName("no tomorrow pill when toDate equals fromDate")
    void detect_singleDayWindow_noTomorrowCheck() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.QUIET);

        List<HotTopic> topics = strategy.detect(TODAY, TODAY);

        assertThat(topics).isEmpty();
    }

    // ── Regions ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("aurora regions derived from dark-sky locations")
    void detect_regionsFromDarkSkyLocations() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MINOR);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(4.0);

        RegionEntity r1 = new RegionEntity();
        r1.setName("Northumberland");
        RegionEntity r2 = new RegionEntity();
        r2.setName("The Lake District");

        LocationEntity loc1 = LocationEntity.builder()
                .id(1L).name("Kielder").lat(55.2).lon(-2.6)
                .bortleClass(2).region(r1).enabled(true).build();
        LocationEntity loc2 = LocationEntity.builder()
                .id(2L).name("Haweswater").lat(54.5).lon(-2.8)
                .bortleClass(3).region(r2).enabled(true).build();

        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(List.of(loc1, loc2));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).regions())
                .containsExactly("Northumberland", "The Lake District");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubDarkSkyLocations(String regionName) {
        RegionEntity region = new RegionEntity();
        region.setName(regionName);
        LocationEntity location = LocationEntity.builder()
                .id(1L).name("Dark Sky").lat(55.2).lon(-2.6)
                .bortleClass(2).region(region).enabled(true).build();
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(List.of(location));
    }

    private void stubTomorrowKpForecast(double peakKp) {
        LocalDate tomorrow = TODAY.plusDays(1);
        ZonedDateTime evening = tomorrow.atTime(21, 0).atZone(ZoneOffset.UTC);
        when(noaaSwpcClient.getCachedKpForecast()).thenReturn(List.of(
                new KpForecast(evening, evening.plusHours(3), peakKp)));
    }
}
