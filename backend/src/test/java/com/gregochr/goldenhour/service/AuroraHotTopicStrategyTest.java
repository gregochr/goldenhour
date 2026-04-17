package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.AuroraTonightSummary;
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
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Mock
    private BriefingAuroraSummaryBuilder auroraSummaryBuilder;

    private AuroraHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new AuroraHotTopicStrategy(auroraStateCache, noaaSwpcClient,
                locationRepository, auroraSummaryBuilder);
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

    // ── Tonight detail string ─────────────────────────────────────────────

    @Test
    @DisplayName("tonight pill omits clear-sky suffix when clearCount is null")
    void detect_nullClearCount_omitsSuffix() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MINOR);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(4.0);
        when(auroraStateCache.getClearLocationCount()).thenReturn(null);
        stubDarkSkyLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        HotTopic tonight = topics.stream()
                .filter(t -> t.date().equals(TODAY))
                .findFirst().orElseThrow();
        assertThat(tonight.detail()).isEqualTo("Kp 4 forecast tonight");
    }

    @Test
    @DisplayName("tonight pill filterAction is null (aurora has its own UI section)")
    void detect_tonightPill_filterActionIsNull() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(5.0);
        stubDarkSkyLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).filterAction()).isNull();
    }

    // ── Tomorrow pill field coverage ─────────────────────────────────────────

    @Test
    @DisplayName("tomorrow pill has type AURORA, label 'Aurora possible', null filterAction")
    void detect_tomorrowPill_fieldValues() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.QUIET);
        stubTomorrowKpForecast(6.0);
        stubDarkSkyLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        HotTopic tomorrow = topics.get(0);
        assertThat(tomorrow.type()).isEqualTo("AURORA");
        assertThat(tomorrow.label()).isEqualTo("Aurora possible");
        assertThat(tomorrow.filterAction()).isNull();
        assertThat(tomorrow.description()).contains("aurora borealis");
        assertThat(tomorrow.regions()).containsExactly("Northumberland");
    }

    // ── Kp threshold boundary ────────────────────────────────────────────────

    @Test
    @DisplayName("tomorrow Kp exactly 4.0 emits pill (boundary: >= 4.0)")
    void detect_tomorrowKpExactly4_emitsPill() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.QUIET);
        stubTomorrowKpForecast(4.0);
        stubDarkSkyLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).detail()).contains("Kp 4");
    }

    @Test
    @DisplayName("tomorrow Kp 3.9 does NOT emit pill (boundary: < 4.0)")
    void detect_tomorrowKp3point9_noPill() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.QUIET);
        stubTomorrowKpForecast(3.9);

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).isEmpty();
    }

    // ── Dark hours boundary ──────────────────────────────────────────────────

    @Test
    @DisplayName("Kp window at hour 18 (start of dark hours) IS included")
    void detect_kpAtHour18_included() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.QUIET);
        LocalDate tomorrow = TODAY.plusDays(1);
        ZonedDateTime hour18 = tomorrow.atTime(18, 0).atZone(ZoneOffset.UTC);
        when(noaaSwpcClient.getCachedKpForecast()).thenReturn(List.of(
                new KpForecast(hour18, hour18.plusHours(3), 5.0)));
        stubDarkSkyLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
    }

    @Test
    @DisplayName("Kp window at hour 17 (before dark hours) is excluded")
    void detect_kpAtHour17_excluded() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.QUIET);
        LocalDate tomorrow = TODAY.plusDays(1);
        ZonedDateTime hour17 = tomorrow.atTime(17, 0).atZone(ZoneOffset.UTC);
        when(noaaSwpcClient.getCachedKpForecast()).thenReturn(List.of(
                new KpForecast(hour17, hour17.plusHours(3), 7.0)));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("Kp window at hour 5 next morning IS included (early hours)")
    void detect_kpAtHour5NextMorning_included() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.QUIET);
        LocalDate tomorrow = TODAY.plusDays(1);
        // Early morning of tomorrow+1 = still "tomorrow night"
        ZonedDateTime hour5 = tomorrow.plusDays(1).atTime(5, 0).atZone(ZoneOffset.UTC);
        when(noaaSwpcClient.getCachedKpForecast()).thenReturn(List.of(
                new KpForecast(hour5, hour5.plusHours(1), 5.0)));
        stubDarkSkyLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
    }

    @Test
    @DisplayName("Kp window at hour 6 next morning is excluded (end of dark hours)")
    void detect_kpAtHour6NextMorning_excluded() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.QUIET);
        LocalDate tomorrow = TODAY.plusDays(1);
        ZonedDateTime hour6 = tomorrow.plusDays(1).atTime(6, 0).atZone(ZoneOffset.UTC);
        when(noaaSwpcClient.getCachedKpForecast()).thenReturn(List.of(
                new KpForecast(hour6, hour6.plusHours(3), 7.0)));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("daytime Kp window (hour 12) is excluded even when Kp is high")
    void detect_daytimeKp_excluded() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.QUIET);
        LocalDate tomorrow = TODAY.plusDays(1);
        ZonedDateTime noon = tomorrow.atTime(12, 0).atZone(ZoneOffset.UTC);
        when(noaaSwpcClient.getCachedKpForecast()).thenReturn(List.of(
                new KpForecast(noon, noon.plusHours(3), 8.0)));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).isEmpty();
    }

    // ── Kp forecast aggregation ──────────────────────────────────────────────

    @Test
    @DisplayName("forecast picks MAX Kp across multiple dark-hour windows")
    void detect_multipleWindows_picksMax() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.QUIET);
        LocalDate tomorrow = TODAY.plusDays(1);
        ZonedDateTime window1 = tomorrow.atTime(21, 0).atZone(ZoneOffset.UTC);
        ZonedDateTime window2 = tomorrow.plusDays(1).atTime(0, 0).atZone(ZoneOffset.UTC);
        ZonedDateTime window3 = tomorrow.plusDays(1).atTime(3, 0).atZone(ZoneOffset.UTC);
        when(noaaSwpcClient.getCachedKpForecast()).thenReturn(List.of(
                new KpForecast(window1, window1.plusHours(3), 3.0),
                new KpForecast(window2, window2.plusHours(3), 6.0),
                new KpForecast(window3, window3.plusHours(3), 2.0)));
        stubDarkSkyLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).detail()).contains("Kp 6");
    }

    @Test
    @DisplayName("forecast ignores daytime windows when picking max")
    void detect_mixedDayNight_ignoresDaytime() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.QUIET);
        LocalDate tomorrow = TODAY.plusDays(1);
        ZonedDateTime daytime = tomorrow.atTime(12, 0).atZone(ZoneOffset.UTC);
        ZonedDateTime nighttime = tomorrow.atTime(21, 0).atZone(ZoneOffset.UTC);
        when(noaaSwpcClient.getCachedKpForecast()).thenReturn(List.of(
                new KpForecast(daytime, daytime.plusHours(3), 8.0),
                new KpForecast(nighttime, nighttime.plusHours(3), 3.0)));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        // daytime Kp 8.0 is excluded; nighttime Kp 3.0 is below threshold
        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("empty forecast list emits no tomorrow pill")
    void detect_emptyForecastList_noTomorrowPill() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.QUIET);
        when(noaaSwpcClient.getCachedKpForecast()).thenReturn(List.of());

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).isEmpty();
    }

    // ── Region edge cases ────────────────────────────────────────────────────

    @Test
    @DisplayName("duplicate regions from multiple locations are deduplicated")
    void detect_duplicateRegions_deduplicated() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MINOR);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(4.0);

        RegionEntity region = new RegionEntity();
        region.setName("Northumberland");

        LocationEntity loc1 = LocationEntity.builder()
                .id(1L).name("Kielder").lat(55.2).lon(-2.6)
                .bortleClass(2).region(region).enabled(true).build();
        LocationEntity loc2 = LocationEntity.builder()
                .id(2L).name("Cawfields").lat(55.1).lon(-2.4)
                .bortleClass(3).region(region).enabled(true).build();

        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(List.of(loc1, loc2));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).regions()).containsExactly("Northumberland");
    }

    @Test
    @DisplayName("locations with null region are filtered out")
    void detect_nullRegion_filteredOut() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MINOR);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(4.0);

        RegionEntity validRegion = new RegionEntity();
        validRegion.setName("Northumberland");

        LocationEntity withRegion = LocationEntity.builder()
                .id(1L).name("Kielder").lat(55.2).lon(-2.6)
                .bortleClass(2).region(validRegion).enabled(true).build();
        LocationEntity noRegion = LocationEntity.builder()
                .id(2L).name("Orphan").lat(54.0).lon(-2.0)
                .bortleClass(4).region(null).enabled(true).build();

        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(List.of(withRegion, noRegion));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).regions()).containsExactly("Northumberland");
    }

    @Test
    @DisplayName("no dark-sky locations produces empty regions list")
    void detect_noDarkSkyLocations_emptyRegions() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MINOR);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(4.0);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(List.of());

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).regions()).isEmpty();
    }

    // ── Clear count consistency (briefing summary vs state cache) ────────────

    @Test
    @DisplayName("tonight pill uses briefing summary clear count when available")
    void detect_briefingSummaryAvailable_usesSummaryClearCount() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(6.0);
        when(auroraSummaryBuilder.buildAuroraTonightCached()).thenReturn(
                new AuroraTonightSummary(AlertLevel.MODERATE, 6.0, 72,
                        List.of(), null, null, null, null, null, null, null));
        stubDarkSkyLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        HotTopic tonight = topics.stream()
                .filter(t -> t.date().equals(TODAY))
                .findFirst().orElseThrow();
        assertThat(tonight.detail()).contains("72 dark-sky locations");
        assertThat(tonight.detail()).doesNotContain("214");
    }

    @Test
    @DisplayName("tonight pill falls back to state cache when briefing summary is null")
    void detect_briefingSummaryNull_fallsBackToStateCache() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(auroraStateCache.getLastTriggerKp()).thenReturn(6.0);
        when(auroraStateCache.getClearLocationCount()).thenReturn(42);
        when(auroraSummaryBuilder.buildAuroraTonightCached()).thenReturn(null);
        stubDarkSkyLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        HotTopic tonight = topics.stream()
                .filter(t -> t.date().equals(TODAY))
                .findFirst().orElseThrow();
        assertThat(tonight.detail()).contains("42 dark-sky locations");
    }

    // ── Interaction verification ─────────────────────────────────────────────

    @Test
    @DisplayName("QUIET alert does not query location repository or Kp forecast")
    void detect_quiet_noRepositoryOrForecastQueries() {
        when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.QUIET);
        when(noaaSwpcClient.getCachedKpForecast()).thenReturn(List.of());

        strategy.detect(TODAY, TO_DATE);

        verifyNoInteractions(locationRepository);
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
