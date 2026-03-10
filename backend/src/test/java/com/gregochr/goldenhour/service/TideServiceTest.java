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
                LocalDateTime.of(2026, 2, 24, 20, 45), BigDecimal.valueOf(0.30));
        assertFalse(tideService.calculateTideAligned(data, Set.of(TideType.MID)));
    }

    // -------------------------------------------------------------------------
    // getTideStats
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getTideStats() returns stats from high and low extremes")
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

        Optional<TideStats> result = tideService.getTideStats(1L);

        assertThat(result).isPresent();
        TideStats stats = result.get();
        assertThat(stats.avgHighMetres()).isEqualByComparingTo(BigDecimal.valueOf(1.400));
        assertThat(stats.maxHighMetres()).isEqualByComparingTo(BigDecimal.valueOf(1.800));
        assertThat(stats.avgLowMetres()).isEqualByComparingTo(BigDecimal.valueOf(-1.200));
        assertThat(stats.minLowMetres()).isEqualByComparingTo(BigDecimal.valueOf(-1.500));
        assertThat(stats.dataPoints()).isEqualTo(20);
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
    @DisplayName("getTideStats() handles location with only high extremes")
    void getTideStats_onlyHighData_returnsPartialStats() {
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.HIGH))
                .thenReturn(new Object[]{
                        BigDecimal.valueOf(1.300), BigDecimal.valueOf(1.600),
                        BigDecimal.valueOf(1.000), 5L
                });
        when(tideExtremeRepository.findHeightStatsByLocationIdAndType(1L, TideExtremeType.LOW))
                .thenReturn(new Object[]{null, null, null, 0L});

        Optional<TideStats> result = tideService.getTideStats(1L);

        assertThat(result).isPresent();
        TideStats stats = result.get();
        assertThat(stats.avgHighMetres()).isNotNull();
        assertThat(stats.avgLowMetres()).isNull();
        assertThat(stats.dataPoints()).isEqualTo(5);
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
                BigDecimal.valueOf(0.30));
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
