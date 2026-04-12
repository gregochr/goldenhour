package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.WorldTidesProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.TideData;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.model.WorldTidesResponse;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TideService}.
 */
@ExtendWith(MockitoExtension.class)
class TideServiceTest {

    @Mock
    private RestClient restClient;

    @Mock
    private TideExtremeRepository tideExtremeRepository;

    @Mock
    private WorldTidesProperties worldTidesProperties;

    @Mock
    private JobRunService jobRunService;

    private TideService tideService;

    @BeforeEach
    void setUp() {
        tideService = new TideService(restClient, tideExtremeRepository, worldTidesProperties, jobRunService);
    }

    // -------------------------------------------------------------------------
    // classifyTideState (List<TideExtremeEntity>)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("classifyTideState() returns HIGH when event is within 90 min of a HIGH extreme")
    void classifyTideState_withinThresholdOfHighExtreme_returnsHigh() {
        LocalDateTime highTime = LocalDateTime.of(2026, 2, 24, 14, 0);
        LocalDateTime event = LocalDateTime.of(2026, 2, 24, 13, 30); // 30 min before

        List<TideExtremeEntity> extremes = List.of(
                extreme(highTime, TideExtremeType.HIGH, 1.8));

        assertThat(tideService.classifyTideState(extremes, event)).isEqualTo(TideState.HIGH);
    }

    @Test
    @DisplayName("classifyTideState() returns LOW when event is within 90 min of a LOW extreme")
    void classifyTideState_withinThresholdOfLowExtreme_returnsLow() {
        LocalDateTime lowTime = LocalDateTime.of(2026, 2, 24, 8, 0);
        LocalDateTime event = LocalDateTime.of(2026, 2, 24, 8, 45); // 45 min after

        List<TideExtremeEntity> extremes = List.of(
                extreme(lowTime, TideExtremeType.LOW, 0.2));

        assertThat(tideService.classifyTideState(extremes, event)).isEqualTo(TideState.LOW);
    }

    @Test
    @DisplayName("classifyTideState() returns MID when event is between extremes")
    void classifyTideState_betweenExtremes_returnsMid() {
        LocalDateTime highTime = LocalDateTime.of(2026, 2, 24, 6, 0);
        LocalDateTime lowTime = LocalDateTime.of(2026, 2, 24, 12, 0);
        LocalDateTime event = LocalDateTime.of(2026, 2, 24, 9, 0); // midway, >90 min from both

        List<TideExtremeEntity> extremes = List.of(
                extreme(highTime, TideExtremeType.HIGH, 1.8),
                extreme(lowTime, TideExtremeType.LOW, 0.2));

        assertThat(tideService.classifyTideState(extremes, event)).isEqualTo(TideState.MID);
    }

    @Test
    @DisplayName("classifyTideState() returns HIGH when event is exactly at 60-minute boundary")
    void classifyTideState_exactlyAtBoundary_returnsHigh() {
        LocalDateTime highTime = LocalDateTime.of(2026, 2, 24, 14, 0);
        LocalDateTime event = LocalDateTime.of(2026, 2, 24, 13, 0); // exactly 60 min before

        List<TideExtremeEntity> extremes = List.of(
                extreme(highTime, TideExtremeType.HIGH, 1.8));

        // <= 60 min threshold: 60 == 60 → HIGH
        assertThat(tideService.classifyTideState(extremes, event)).isEqualTo(TideState.HIGH);
    }

    @Test
    @DisplayName("classifyTideState() returns MID when event is 61 minutes from nearest extreme")
    void classifyTideState_justOutsideBoundary_returnsMid() {
        LocalDateTime highTime = LocalDateTime.of(2026, 2, 24, 14, 0);
        LocalDateTime event = LocalDateTime.of(2026, 2, 24, 12, 59); // 61 min before

        List<TideExtremeEntity> extremes = List.of(
                extreme(highTime, TideExtremeType.HIGH, 1.8));

        assertThat(tideService.classifyTideState(extremes, event)).isEqualTo(TideState.MID);
    }

    @Test
    @DisplayName("classifyTideState() returns HIGH when event is 59 minutes from extreme (within threshold)")
    void classifyTideState_59MinutesFromExtreme_returnsHigh() {
        LocalDateTime highTime = LocalDateTime.of(2026, 2, 24, 14, 0);
        LocalDateTime event = LocalDateTime.of(2026, 2, 24, 13, 1); // 59 min before

        List<TideExtremeEntity> extremes = List.of(
                extreme(highTime, TideExtremeType.HIGH, 1.8));

        assertThat(tideService.classifyTideState(extremes, event)).isEqualTo(TideState.HIGH);
    }

    // -------------------------------------------------------------------------
    // buildTideData
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("buildTideData() returns correct state and next tide events")
    void buildTideData_returnsStateAndNextTides() {
        // High at 03:00, Low at 09:00, event at 07:00 (>90 min from both → MID)
        LocalDateTime highTime = LocalDateTime.of(2026, 2, 24, 3, 0);
        LocalDateTime lowTime = LocalDateTime.of(2026, 2, 24, 9, 0);
        LocalDateTime event = LocalDateTime.of(2026, 2, 24, 7, 0);

        List<TideExtremeEntity> extremes = List.of(
                extreme(highTime, TideExtremeType.HIGH, 1.9),
                extreme(lowTime, TideExtremeType.LOW, -0.4));

        TideData result = tideService.buildTideData(extremes, event);

        assertThat(result.tideState()).isEqualTo(TideState.MID);
        // Next high after 07:00 → none (03:00 is before event)
        assertThat(result.nextHighTideTime()).isNull();
        // Next low after 07:00 → 09:00
        assertThat(result.nextLowTideTime()).isEqualTo(lowTime);
        assertThat(result.nextLowTideHeightMetres()).isEqualByComparingTo("-0.400");
    }

    @Test
    @DisplayName("buildTideData() sets nearMidPoint=true when event is within 45 min of midpoint")
    void buildTideData_nearMidpoint_setsNearMidPointTrue() {
        // HIGH at 06:00, LOW at 12:00 → midpoint at 09:00; event at 09:30 (30 min after)
        LocalDateTime highTime = LocalDateTime.of(2026, 2, 24, 6, 0);
        LocalDateTime lowTime = LocalDateTime.of(2026, 2, 24, 12, 0);
        LocalDateTime event = LocalDateTime.of(2026, 2, 24, 9, 30);

        List<TideExtremeEntity> extremes = List.of(
                extreme(highTime, TideExtremeType.HIGH, 1.8),
                extreme(lowTime, TideExtremeType.LOW, 0.2));

        assertThat(tideService.buildTideData(extremes, event).nearMidPoint()).isTrue();
    }

    @Test
    @DisplayName("buildTideData() sets nearMidPoint=false when event is more than 45 min from midpoint")
    void buildTideData_farFromMidpoint_setsNearMidPointFalse() {
        // HIGH at 06:00, LOW at 12:00 → midpoint at 09:00; event at 07:00 (2hr before midpoint)
        LocalDateTime highTime = LocalDateTime.of(2026, 2, 24, 6, 0);
        LocalDateTime lowTime = LocalDateTime.of(2026, 2, 24, 12, 0);
        LocalDateTime event = LocalDateTime.of(2026, 2, 24, 7, 0);

        List<TideExtremeEntity> extremes = List.of(
                extreme(highTime, TideExtremeType.HIGH, 1.8),
                extreme(lowTime, TideExtremeType.LOW, 0.2));

        assertThat(tideService.buildTideData(extremes, event).nearMidPoint()).isFalse();
    }

    @Test
    @DisplayName("buildTideData() returns null nextHighTideTime when no future HIGH extreme exists")
    void buildTideData_noFutureHigh_nextHighIsNull() {
        LocalDateTime highTime = LocalDateTime.of(2026, 2, 24, 6, 0);
        LocalDateTime event = LocalDateTime.of(2026, 2, 24, 12, 0); // after the only high

        List<TideExtremeEntity> extremes = List.of(
                extreme(highTime, TideExtremeType.HIGH, 1.5));

        TideData result = tideService.buildTideData(extremes, event);

        assertThat(result.nextHighTideTime()).isNull();
        assertThat(result.nextHighTideHeightMetres()).isNull();
    }

    // -------------------------------------------------------------------------
    // getTidesForDate
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getTidesForDate() returns extremes within the given UTC calendar day")
    void getTidesForDate_returnsExtremesForDay() {
        Long locationId = 1L;
        java.time.LocalDate date = java.time.LocalDate.of(2026, 2, 24);
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.plusDays(1).atStartOfDay().minusNanos(1);

        List<TideExtremeEntity> expected = List.of(
                extreme(LocalDateTime.of(2026, 2, 24, 6, 20), TideExtremeType.HIGH, 1.8),
                extreme(LocalDateTime.of(2026, 2, 24, 12, 45), TideExtremeType.LOW, 0.2));

        when(tideExtremeRepository.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                eq(locationId), eq(from), eq(to))).thenReturn(expected);

        List<TideExtremeEntity> result = tideService.getTidesForDate(locationId, date);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getType()).isEqualTo(TideExtremeType.HIGH);
        assertThat(result.get(1).getType()).isEqualTo(TideExtremeType.LOW);
    }

    // -------------------------------------------------------------------------
    // deriveTideData
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deriveTideData() returns TideData when DB has extremes")
    void deriveTideData_extremesPresent_returnsTideData() {
        Long locationId = 1L;
        LocalDateTime event = LocalDateTime.of(2026, 2, 24, 7, 0);

        List<TideExtremeEntity> extremes = List.of(
                extreme(LocalDateTime.of(2026, 2, 24, 3, 0), TideExtremeType.HIGH, 1.9),
                extreme(LocalDateTime.of(2026, 2, 24, 9, 0), TideExtremeType.LOW, -0.4));

        when(tideExtremeRepository.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                eq(locationId), any(), any())).thenReturn(extremes);

        Optional<TideData> result = tideService.deriveTideData(locationId, event);

        assertThat(result).isPresent();
        assertThat(result.get().tideState()).isEqualTo(TideState.MID);
    }

    @Test
    @DisplayName("deriveTideData() returns empty when DB has no extremes")
    void deriveTideData_noExtremes_returnsEmpty() {
        when(tideExtremeRepository.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                anyLong(), any(), any())).thenReturn(List.of());

        Optional<TideData> result = tideService.deriveTideData(1L, LocalDateTime.now());

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // fetchAndStoreTideExtremes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("fetchAndStoreTideExtremes() stores entities when API returns valid response")
    void fetchAndStoreTideExtremes_validResponse_storesEntities() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");

        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.get().uri(any(java.util.function.Function.class))
                .retrieve().body(WorldTidesResponse.class))
                .thenReturn(buildWorldTidesResponse());

        TideService service = new TideService(
                mockClient, tideExtremeRepository, worldTidesProperties, jobRunService);

        LocationEntity location = locationEntity();
        service.fetchAndStoreTideExtremes(location);

        verify(tideExtremeRepository).deleteByLocationIdAndEventTimeBetween(
                eq(1L), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(tideExtremeRepository).saveAll(any());
    }

    @Test
    @DisplayName("fetchAndStoreTideExtremes() deletes only the 14-day fetch window, not all history")
    void fetchAndStoreTideExtremes_deletesOnlyFetchWindow() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");

        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.get().uri(any(java.util.function.Function.class))
                .retrieve().body(WorldTidesResponse.class))
                .thenReturn(buildWorldTidesResponse());

        TideService service = new TideService(
                mockClient, tideExtremeRepository, worldTidesProperties, jobRunService);

        service.fetchAndStoreTideExtremes(locationEntity());

        // Should NOT call deleteByLocationId (which would nuke all history)
        verify(tideExtremeRepository, never()).deleteByLocationId(anyLong());
        // Should call windowed delete
        var captor = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tideExtremeRepository).deleteByLocationIdAndEventTimeBetween(
                eq(1L), captor.capture(), captor.capture());
        List<LocalDateTime> args = captor.getAllValues();
        LocalDateTime from = args.get(0);
        LocalDateTime to = args.get(1);
        // Window should span roughly 14 days
        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(from, to);
        assertTrue(daysBetween >= 13 && daysBetween <= 14,
                "Fetch window should be ~14 days but was " + daysBetween);
    }

    @Test
    @DisplayName("fetchAndStoreTideExtremes() skips fetch when API key is blank")
    void fetchAndStoreTideExtremes_blankApiKey_skips() {
        when(worldTidesProperties.getApiKey()).thenReturn("");

        tideService.fetchAndStoreTideExtremes(locationEntity());

        verify(tideExtremeRepository, never()).deleteByLocationIdAndEventTimeBetween(
                anyLong(), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(tideExtremeRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("fetchAndStoreTideExtremes() skips save when API returns non-200 status")
    void fetchAndStoreTideExtremes_nonOkStatus_skips() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        WorldTidesResponse errorResponse = new WorldTidesResponse();
        errorResponse.setStatus(400);

        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.get().uri(any(java.util.function.Function.class))
                .retrieve().body(WorldTidesResponse.class))
                .thenReturn(errorResponse);

        TideService service = new TideService(
                mockClient, tideExtremeRepository, worldTidesProperties, jobRunService);

        service.fetchAndStoreTideExtremes(locationEntity());

        verify(tideExtremeRepository, never()).deleteByLocationIdAndEventTimeBetween(
                anyLong(), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(tideExtremeRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("fetchAndStoreTideExtremes() swallows exception without throwing")
    void fetchAndStoreTideExtremes_exceptionDuringFetch_doesNotThrow() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");

        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.get().uri(any(java.util.function.Function.class))
                .retrieve().body(WorldTidesResponse.class))
                .thenThrow(new RuntimeException("network error"));

        TideService service = new TideService(
                mockClient, tideExtremeRepository, worldTidesProperties, jobRunService);

        // Should not throw
        service.fetchAndStoreTideExtremes(locationEntity());

        verify(tideExtremeRepository, never()).deleteByLocationIdAndEventTimeBetween(
                anyLong(), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    // -------------------------------------------------------------------------
    // hasStoredExtremes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("hasStoredExtremes() returns true when repository has data for location")
    void hasStoredExtremes_whenExists_returnsTrue() {
        when(tideExtremeRepository.existsByLocationId(1L)).thenReturn(true);
        assertThat(tideService.hasStoredExtremes(1L)).isTrue();
    }

    @Test
    @DisplayName("hasStoredExtremes() returns false when repository has no data for location")
    void hasStoredExtremes_whenAbsent_returnsFalse() {
        when(tideExtremeRepository.existsByLocationId(1L)).thenReturn(false);
        assertThat(tideService.hasStoredExtremes(1L)).isFalse();
    }

    // -------------------------------------------------------------------------
    // backfillTideExtremes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("backfillTideExtremes() skips chunks where data already exists")
    void backfillTideExtremes_existingData_skipsChunk() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        // All chunks already have data
        when(tideExtremeRepository.existsByLocationIdAndEventTimeBetween(
                eq(1L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(true);

        int fetched = tideService.backfillTideExtremes(locationEntity(), null);

        assertThat(fetched).isZero();
        // Verify no API calls were made (restClient.get() never called for backfill)
        verify(tideExtremeRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("backfillTideExtremes() returns 0 when API key not configured")
    void backfillTideExtremes_noApiKey_returnsZero() {
        when(worldTidesProperties.getApiKey()).thenReturn("");

        int fetched = tideService.backfillTideExtremes(locationEntity(), null);

        assertThat(fetched).isZero();
    }

    @Test
    @DisplayName("backfillTideExtremes() fetches and stores data for missing chunks")
    void backfillTideExtremes_missingData_fetchesAndStores() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        // All chunks missing
        when(tideExtremeRepository.existsByLocationIdAndEventTimeBetween(
                eq(1L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(false);

        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.get().uri(any(java.util.function.Function.class))
                .retrieve().body(WorldTidesResponse.class))
                .thenReturn(buildWorldTidesResponse());

        TideService service = new TideService(
                mockClient, tideExtremeRepository, worldTidesProperties, jobRunService);

        int fetched = service.backfillTideExtremes(locationEntity(), null);

        assertThat(fetched).isGreaterThan(0);
        verify(tideExtremeRepository, org.mockito.Mockito.atLeastOnce()).saveAll(any());
    }

    // -------------------------------------------------------------------------
    // calculateTideAligned — single preferences
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("HIGH tide aligns with HIGH preference")
    void calculateTideAligned_highTide_highPreference_aligned() {
        assertTrue(tideService.calculateTideAligned(tideData(TideState.HIGH), Set.of(TideType.HIGH)));
    }

    @Test
    @DisplayName("LOW tide does not align with HIGH preference")
    void calculateTideAligned_lowTide_highPreference_notAligned() {
        assertFalse(tideService.calculateTideAligned(tideData(TideState.LOW), Set.of(TideType.HIGH)));
    }

    @Test
    @DisplayName("LOW tide aligns with LOW preference")
    void calculateTideAligned_lowTide_lowPreference_aligned() {
        assertTrue(tideService.calculateTideAligned(tideData(TideState.LOW), Set.of(TideType.LOW)));
    }

    @Test
    @DisplayName("MID tide aligns with MID preference")
    void calculateTideAligned_midTide_midPreference_aligned() {
        assertTrue(tideService.calculateTideAligned(tideData(TideState.MID), Set.of(TideType.MID)));
    }

    @Test
    @DisplayName("MID tide does not align with HIGH or LOW preference")
    void calculateTideAligned_midTide_highOrLowPreference_notAligned() {
        assertFalse(tideService.calculateTideAligned(
                tideData(TideState.MID), Set.of(TideType.HIGH, TideType.LOW)));
    }

    @Test
    @DisplayName("All three preferences always aligns with any tide state")
    void calculateTideAligned_allThreePreferences_alwaysAligned() {
        Set<TideType> allThree = Set.of(TideType.HIGH, TideType.MID, TideType.LOW);
        assertTrue(tideService.calculateTideAligned(tideData(TideState.LOW), allThree));
        assertTrue(tideService.calculateTideAligned(tideData(TideState.HIGH), allThree));
        assertTrue(tideService.calculateTideAligned(tideData(TideState.MID), allThree));
    }

    // -------------------------------------------------------------------------
    // calculateTideAligned — multiple preferences
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("HIGH tide aligns when HIGH is one of multiple preferences")
    void calculateTideAligned_multiplePreferences_highMatches() {
        assertTrue(tideService.calculateTideAligned(
                tideData(TideState.HIGH), Set.of(TideType.HIGH, TideType.LOW)));
    }

    @Test
    @DisplayName("MID tide aligns when MID is one of multiple preferences")
    void calculateTideAligned_multiplePreferences_midMatches() {
        assertTrue(tideService.calculateTideAligned(
                tideData(TideState.MID), Set.of(TideType.HIGH, TideType.MID)));
    }

    // -------------------------------------------------------------------------
    // calculateTideAligned — edge cases
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Empty preference set returns false")
    void calculateTideAligned_emptyPreferences_notAligned() {
        assertFalse(tideService.calculateTideAligned(tideData(TideState.HIGH), Set.of()));
    }

    @Test
    @DisplayName("MID preference returns false when tide is MID state but not near midpoint")
    void calculateTideAligned_midTide_notNearMidpoint_notAligned() {
        TideData data = new TideData(TideState.MID, false,
                LocalDateTime.of(2026, 2, 24, 14, 30), BigDecimal.valueOf(1.50),
                LocalDateTime.of(2026, 2, 24, 20, 45), BigDecimal.valueOf(0.30),
                null, null);
        assertFalse(tideService.calculateTideAligned(data, Set.of(TideType.MID)));
    }

    // -------------------------------------------------------------------------
    // getTideStats
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getTideStats() returns stats with percentiles from high and low extremes")
    void getTideStats_withData_returnsStats() {
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.HIGH))
                .thenReturn(new Object[]{
                        BigDecimal.valueOf(1.400), BigDecimal.valueOf(1.800),
                        BigDecimal.valueOf(1.100), 10L
                });
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.LOW))
                .thenReturn(new Object[]{
                        BigDecimal.valueOf(-1.200), BigDecimal.valueOf(-0.800),
                        BigDecimal.valueOf(-1.500), 10L
                });
        // 10 sorted HIGH heights for percentile calculation
        when(tideExtremeRepository.findHeightsByLocationIdAndTypeOrderByHeightAsc(1L, TideExtremeType.HIGH))
                .thenReturn(List.of(
                        BigDecimal.valueOf(1.100), BigDecimal.valueOf(1.200),
                        BigDecimal.valueOf(1.250), BigDecimal.valueOf(1.300),
                        BigDecimal.valueOf(1.350), BigDecimal.valueOf(1.400),
                        BigDecimal.valueOf(1.500), BigDecimal.valueOf(1.600),
                        BigDecimal.valueOf(1.700), BigDecimal.valueOf(1.800)));

        Optional<TideStats> result = tideService.getTideStats(1L);

        assertThat(result).isPresent();
        TideStats stats = result.get();
        assertThat(stats.avgHighMetres()).isEqualByComparingTo(BigDecimal.valueOf(1.400));
        assertThat(stats.maxHighMetres()).isEqualByComparingTo(BigDecimal.valueOf(1.800));
        assertThat(stats.avgLowMetres()).isEqualByComparingTo(BigDecimal.valueOf(-1.200));
        assertThat(stats.minLowMetres()).isEqualByComparingTo(BigDecimal.valueOf(-1.500));
        assertThat(stats.dataPoints()).isEqualTo(20);
        assertThat(stats.avgRangeMetres()).isEqualByComparingTo(BigDecimal.valueOf(2.600));
        assertThat(stats.p75HighMetres()).isNotNull();
        assertThat(stats.p90HighMetres()).isNotNull();
        assertThat(stats.p95HighMetres()).isNotNull();
        // 1.75 threshold (125% of 1.4); only 1.800 exceeds it
        assertThat(stats.springTideCount()).isEqualTo(1);
        assertThat(stats.springTideFrequency()).isEqualByComparingTo(BigDecimal.valueOf(0.100));
        assertThat(stats.springTideThreshold()).isEqualByComparingTo(BigDecimal.valueOf(1.750));
        assertThat(stats.kingTideThreshold()).isNotNull();
        assertThat(stats.kingTideCount()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("getTideStats() returns empty when no data stored")
    void getTideStats_noData_returnsEmpty() {
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.HIGH))
                .thenReturn(new Object[]{null, null, null, 0L});
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.LOW))
                .thenReturn(new Object[]{null, null, null, 0L});

        assertThat(tideService.getTideStats(1L)).isEmpty();
    }

    @Test
    @DisplayName("getTideStats() handles H2 returning single-element array when no rows match")
    void getTideStats_singleElementArray_returnsEmpty() {
        // H2 may return Object[]{null} instead of Object[]{null, null, null, 0L} when no rows
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.HIGH))
                .thenReturn(new Object[]{null});
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.LOW))
                .thenReturn(new Object[]{null});

        assertThat(tideService.getTideStats(1L)).isEmpty();
    }

    @Test
    @DisplayName("getTideStats() unwraps nested Object[1]{Object[4]} from H2 aggregate query")
    void getTideStats_nestedArray_unwrapsAndReturnsStats() {
        // H2 may wrap the result as Object[1] containing Object[4]{avg,max,min,count}
        Object[] innerHigh = new Object[]{
                BigDecimal.valueOf(1.400), BigDecimal.valueOf(1.800),
                BigDecimal.valueOf(0.500), 20L};
        Object[] innerLow = new Object[]{
                BigDecimal.valueOf(-1.200), BigDecimal.valueOf(-0.500),
                BigDecimal.valueOf(-1.500), 20L};

        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.HIGH))
                .thenReturn(new Object[]{innerHigh});
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.LOW))
                .thenReturn(new Object[]{innerLow});
        when(tideExtremeRepository.findHeightsByLocationIdAndTypeOrderByHeightAsc(
                1L, TideExtremeType.HIGH))
                .thenReturn(List.of(BigDecimal.valueOf(1.200), BigDecimal.valueOf(1.400),
                        BigDecimal.valueOf(1.800)));

        Optional<TideStats> result = tideService.getTideStats(1L);
        assertThat(result).isPresent();
        assertThat(result.get().avgHighMetres()).isEqualByComparingTo(BigDecimal.valueOf(1.400));
        assertThat(result.get().dataPoints()).isEqualTo(40);
    }

    @Test
    @DisplayName("getTideStats() handles H2 returning Double for AVG instead of BigDecimal")
    void getTideStats_doubleValues_convertsCorrectly() {
        // H2 returns Double for AVG() and BigDecimal for MAX()/MIN()
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.HIGH))
                .thenReturn(new Object[]{1.635, BigDecimal.valueOf(2.753), BigDecimal.valueOf(0.559), 722L});
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.LOW))
                .thenReturn(new Object[]{-1.625, BigDecimal.valueOf(-0.510), BigDecimal.valueOf(-2.906), 720L});
        when(tideExtremeRepository.findHeightsByLocationIdAndTypeOrderByHeightAsc(
                1L, TideExtremeType.HIGH))
                .thenReturn(List.of(BigDecimal.valueOf(0.559), BigDecimal.valueOf(1.600),
                        BigDecimal.valueOf(2.753)));

        Optional<TideStats> result = tideService.getTideStats(1L);
        assertThat(result).isPresent();
        assertThat(result.get().avgHighMetres()).isEqualByComparingTo(BigDecimal.valueOf(1.635));
        assertThat(result.get().maxHighMetres()).isEqualByComparingTo(BigDecimal.valueOf(2.753));
        assertThat(result.get().avgLowMetres()).isEqualByComparingTo(BigDecimal.valueOf(-1.625));
        assertThat(result.get().dataPoints()).isEqualTo(1442);
    }

    @Test
    @DisplayName("getTideStats() handles location with only high extremes")
    void getTideStats_onlyHighData_returnsPartialStats() {
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.HIGH))
                .thenReturn(new Object[]{
                        BigDecimal.valueOf(1.300), BigDecimal.valueOf(1.600),
                        BigDecimal.valueOf(1.000), 5L
                });
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.LOW))
                .thenReturn(new Object[]{null, null, null, 0L});
        when(tideExtremeRepository.findHeightsByLocationIdAndTypeOrderByHeightAsc(1L, TideExtremeType.HIGH))
                .thenReturn(List.of(
                        BigDecimal.valueOf(1.000), BigDecimal.valueOf(1.200),
                        BigDecimal.valueOf(1.300), BigDecimal.valueOf(1.400),
                        BigDecimal.valueOf(1.600)));

        Optional<TideStats> result = tideService.getTideStats(1L);

        assertThat(result).isPresent();
        TideStats stats = result.get();
        assertThat(stats.avgHighMetres()).isNotNull();
        assertThat(stats.avgLowMetres()).isNull();
        assertThat(stats.avgRangeMetres()).isNull();
        assertThat(stats.p75HighMetres()).isNotNull();
        assertThat(stats.p95HighMetres()).isNotNull();
        assertThat(stats.dataPoints()).isEqualTo(5);
    }

    @Test
    @DisplayName("getTideStats() spring tide: height exactly at 125% threshold is NOT counted")
    void getTideStats_heightExactlyAtSpringThreshold_notCounted() {
        // avgHigh = 2.000, threshold = 2.000 * 1.25 = 2.500
        // Height of exactly 2.500 should NOT count (compareTo > 0 required, not >=)
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.HIGH))
                .thenReturn(new Object[]{
                        BigDecimal.valueOf(2.000), BigDecimal.valueOf(2.500),
                        BigDecimal.valueOf(1.500), 3L});
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.LOW))
                .thenReturn(new Object[]{null, null, null, 0L});
        when(tideExtremeRepository.findHeightsByLocationIdAndTypeOrderByHeightAsc(
                1L, TideExtremeType.HIGH))
                .thenReturn(List.of(
                        BigDecimal.valueOf(1.500),
                        BigDecimal.valueOf(2.000),
                        BigDecimal.valueOf(2.500)));  // exactly at threshold

        Optional<TideStats> result = tideService.getTideStats(1L);

        assertThat(result).isPresent();
        // 125% of 2.000 = 2.500; 2.500 is NOT > 2.500 → springCount = 0
        assertThat(result.get().springTideThreshold())
                .isEqualByComparingTo(BigDecimal.valueOf(2.500));
        assertThat(result.get().springTideCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("getTideStats() spring tide: height one step above 125% threshold IS counted")
    void getTideStats_heightJustAboveSpringThreshold_counted() {
        // avgHigh = 2.000, threshold = 2.500
        // Height of 2.501 should count
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.HIGH))
                .thenReturn(new Object[]{
                        BigDecimal.valueOf(2.000), BigDecimal.valueOf(2.501),
                        BigDecimal.valueOf(1.500), 3L});
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.LOW))
                .thenReturn(new Object[]{null, null, null, 0L});
        when(tideExtremeRepository.findHeightsByLocationIdAndTypeOrderByHeightAsc(
                1L, TideExtremeType.HIGH))
                .thenReturn(List.of(
                        BigDecimal.valueOf(1.500),
                        BigDecimal.valueOf(2.000),
                        BigDecimal.valueOf(2.501)));

        Optional<TideStats> result = tideService.getTideStats(1L);

        assertThat(result).isPresent();
        assertThat(result.get().springTideCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("getTideStats() king tide: height exactly at P95 is NOT counted")
    void getTideStats_heightExactlyAtP95_kingTideNotCounted() {
        // 10 values where the top two are equal: P95 = 1.9 (no interpolation gap)
        // rank = 0.95 * (10-1) = 8.55 → lower=8, upper=9, value[8]=1.9, value[9]=1.9
        // P95 = 1.9 + (1.9-1.9)*0.55 = 1.900 exactly
        // No height is strictly > 1.900 → kingTideCount = 0
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.HIGH))
                .thenReturn(new Object[]{
                        BigDecimal.valueOf(1.540), BigDecimal.valueOf(1.900),
                        BigDecimal.valueOf(1.100), 10L});
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.LOW))
                .thenReturn(new Object[]{null, null, null, 0L});
        when(tideExtremeRepository.findHeightsByLocationIdAndTypeOrderByHeightAsc(
                1L, TideExtremeType.HIGH))
                .thenReturn(List.of(
                        BigDecimal.valueOf(1.100), BigDecimal.valueOf(1.200),
                        BigDecimal.valueOf(1.300), BigDecimal.valueOf(1.400),
                        BigDecimal.valueOf(1.500), BigDecimal.valueOf(1.600),
                        BigDecimal.valueOf(1.700), BigDecimal.valueOf(1.800),
                        BigDecimal.valueOf(1.900), BigDecimal.valueOf(1.900)));

        Optional<TideStats> result = tideService.getTideStats(1L);

        assertThat(result).isPresent();
        assertThat(result.get().p95HighMetres()).isEqualByComparingTo(BigDecimal.valueOf(1.900));
        assertThat(result.get().kingTideCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("percentile() returns correct values for known distribution")
    void percentile_knownDistribution_returnsCorrectValues() {
        List<BigDecimal> sorted = List.of(
                BigDecimal.valueOf(1.000), BigDecimal.valueOf(1.100),
                BigDecimal.valueOf(1.200), BigDecimal.valueOf(1.300),
                BigDecimal.valueOf(1.400), BigDecimal.valueOf(1.500),
                BigDecimal.valueOf(1.600), BigDecimal.valueOf(1.700),
                BigDecimal.valueOf(1.800), BigDecimal.valueOf(1.900),
                BigDecimal.valueOf(2.000));

        // p50 = median of 11 values (index 5) = 1.500
        assertThat(TideService.percentile(sorted, 50))
                .isEqualByComparingTo(BigDecimal.valueOf(1.500));
        // p0 = first element
        assertThat(TideService.percentile(sorted, 0))
                .isEqualByComparingTo(BigDecimal.valueOf(1.000));
        // p100 = last element
        assertThat(TideService.percentile(sorted, 100))
                .isEqualByComparingTo(BigDecimal.valueOf(2.000));
    }

    @Test
    @DisplayName("percentile() handles single-element list")
    void percentile_singleElement_returnsThatElement() {
        List<BigDecimal> single = List.of(BigDecimal.valueOf(1.500));
        assertThat(TideService.percentile(single, 95))
                .isEqualByComparingTo(BigDecimal.valueOf(1.500));
    }

    // -------------------------------------------------------------------------
    // findNearestExtreme
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findNearestExtreme() returns the closest HIGH extreme within ±12h")
    void findNearestExtreme_highWithinWindow_returnsClosest() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 0);
        // 30 min before event — within ±12h
        LocalDateTime nearHighTime = eventTime.minusMinutes(30);
        // 6h after event — also within ±12h but further
        LocalDateTime farHighTime = eventTime.plusHours(6);
        List<TideExtremeEntity> extremes = List.of(
                extreme(nearHighTime, TideExtremeType.HIGH, 4.5),
                extreme(farHighTime, TideExtremeType.HIGH, 4.2));

        LocalDateTime result = tideService.findNearestExtreme(extremes, TideExtremeType.HIGH,
                eventTime);

        assertThat(result).isEqualTo(nearHighTime);
    }

    @Test
    @DisplayName("findNearestExtreme() returns null when no HIGH extreme within ±12h")
    void findNearestExtreme_highOutsideWindow_returnsNull() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 0);
        // 13h before event — outside ±12h window
        List<TideExtremeEntity> extremes = List.of(
                extreme(eventTime.minusHours(13), TideExtremeType.HIGH, 4.5));

        LocalDateTime result = tideService.findNearestExtreme(extremes, TideExtremeType.HIGH,
                eventTime);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("findNearestExtreme() returns null when no extremes of the requested type exist")
    void findNearestExtreme_wrongType_returnsNull() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 0);
        List<TideExtremeEntity> extremes = List.of(
                extreme(eventTime.plusHours(1), TideExtremeType.LOW, 0.5));

        LocalDateTime result = tideService.findNearestExtreme(extremes, TideExtremeType.HIGH,
                eventTime);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("findNearestExtreme() includes extremes exactly at the ±12h boundary")
    void findNearestExtreme_exactlyAtBoundary_included() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 0);
        LocalDateTime atBoundary = eventTime.minusHours(12);
        List<TideExtremeEntity> extremes = List.of(
                extreme(atBoundary, TideExtremeType.HIGH, 4.0));

        LocalDateTime result = tideService.findNearestExtreme(extremes, TideExtremeType.HIGH,
                eventTime);

        assertThat(result).isEqualTo(atBoundary);
    }

    @Test
    @DisplayName("buildTideData() populates nearestHighTideTime and nearestLowTideTime within ±12h")
    void buildTideData_populatesNearestTides() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 0);
        // High tide 2h before — within ±12h
        LocalDateTime nearHigh = eventTime.minusHours(2);
        // Low tide 4h after — within ±12h
        LocalDateTime nearLow = eventTime.plusHours(4);
        // High tide 14h before — outside ±12h
        LocalDateTime farHigh = eventTime.minusHours(14);

        List<TideExtremeEntity> extremes = List.of(
                extreme(farHigh, TideExtremeType.HIGH, 4.8),
                extreme(nearHigh, TideExtremeType.HIGH, 4.5),
                extreme(eventTime.plusHours(2), TideExtremeType.HIGH, 4.3), // next high
                extreme(nearLow, TideExtremeType.LOW, 0.5));

        TideData data = tideService.buildTideData(extremes, eventTime);

        assertThat(data.nearestHighTideTime()).isEqualTo(nearHigh);
        assertThat(data.nearestLowTideTime()).isEqualTo(nearLow);
    }

    @Test
    @DisplayName("buildTideData() sets nearestHighTideTime to null when no high within ±12h")
    void buildTideData_noHighWithin12h_nearestHighIsNull() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 0);
        List<TideExtremeEntity> extremes = List.of(
                extreme(eventTime.minusHours(14), TideExtremeType.HIGH, 4.5),
                extreme(eventTime.plusHours(1), TideExtremeType.LOW, 0.5));

        TideData data = tideService.buildTideData(extremes, eventTime);

        assertThat(data.nearestHighTideTime()).isNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TideExtremeEntity extreme(LocalDateTime time, TideExtremeType type, double height) {
        return TideExtremeEntity.builder()
                .locationId(1L)
                .eventTime(time)
                .type(type)
                .heightMetres(BigDecimal.valueOf(height).setScale(3, java.math.RoundingMode.HALF_UP))
                .fetchedAt(LocalDateTime.of(2026, 2, 24, 2, 0))
                .build();
    }

    private TideData tideData(TideState state) {
        // nearMidPoint=true for MID state to keep existing alignment tests meaningful
        return new TideData(
                state,
                state == TideState.MID,
                LocalDateTime.of(2026, 2, 24, 14, 30),
                BigDecimal.valueOf(1.50),
                LocalDateTime.of(2026, 2, 24, 20, 45),
                BigDecimal.valueOf(0.30),
                null,
                null);
    }

    private LocationEntity locationEntity() {
        return LocationEntity.builder()
                .id(1L)
                .name("Berwick-Upon-Tweed")
                .lat(55.7702)
                .lon(-2.0054)
                .build();
    }

    private WorldTidesResponse buildWorldTidesResponse() {
        WorldTidesResponse.Extreme high = new WorldTidesResponse.Extreme();
        high.setDt(1771961798L); // some epoch
        high.setHeight(1.488);
        high.setType("High");

        WorldTidesResponse.Extreme low = new WorldTidesResponse.Extreme();
        low.setDt(1771984200L);
        low.setHeight(-1.359);
        low.setType("Low");

        WorldTidesResponse response = new WorldTidesResponse();
        response.setStatus(200);
        response.setExtremes(List.of(high, low));
        return response;
    }
}
