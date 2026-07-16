package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScheduledForecastService}.
 *
 * <p>The service holds the tide-refresh and daily-briefing job targets plus the
 * admin tide backfill; forecast orchestration is tested in
 * {@link ForecastCommandExecutorTest}.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledForecastServiceTest {

    @Mock
    private TideService tideService;

    @Mock
    private LocationService locationService;

    @Mock
    private JobRunService jobRunService;

    @Mock
    private BriefingService briefingService;

    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    private ScheduledForecastService scheduledForecastService;

    @BeforeEach
    void setUp() {
        scheduledForecastService = new ScheduledForecastService(
                tideService, locationService, jobRunService, briefingService,
                dynamicSchedulerService);
    }

    // -------------------------------------------------------------------------
    // refreshTideExtremes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("refreshTideExtremes() calls tideService for each SEASCAPE coastal location")
    void refreshTideExtremes_callsTideService_forCoastalLocations() {
        LocationEntity durham = LocationEntity.builder().name("Durham UK")
                .locationType(Set.of(LocationType.SEASCAPE)).build();
        when(locationService.findAllEnabled()).thenReturn(List.of(durham));
        when(locationService.isCoastal(durham)).thenReturn(true);

        scheduledForecastService.refreshTideExtremes();

        ArgumentCaptor<LocationEntity> locCaptor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(tideService, times(1)).fetchAndStoreTideExtremes(locCaptor.capture(),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(locCaptor.getValue().getName()).isEqualTo("Durham UK");
    }

    @Test
    @DisplayName("refreshTideExtremes() skips non-SEASCAPE locations")
    void refreshTideExtremes_skipsNonCoastalLocations() {
        LocationEntity durham = LocationEntity.builder().name("Durham UK")
                .locationType(Set.of(LocationType.LANDSCAPE)).build();
        when(locationService.findAllEnabled()).thenReturn(List.of(durham));

        scheduledForecastService.refreshTideExtremes();

        verify(tideService, never()).fetchAndStoreTideExtremes(any(), any());
    }

    @Test
    @DisplayName("refreshTideExtremes() continues after a single location failure")
    void refreshTideExtremes_continuesAfterFailure() {
        LocationEntity durham = LocationEntity.builder().name("Durham UK")
                .locationType(Set.of(LocationType.SEASCAPE)).build();
        LocationEntity scarborough = LocationEntity.builder().name("Scarborough")
                .locationType(Set.of(LocationType.SEASCAPE)).build();
        when(locationService.findAllEnabled()).thenReturn(List.of(durham, scarborough));
        when(locationService.isCoastal(durham)).thenReturn(true);
        when(locationService.isCoastal(scarborough)).thenReturn(true);
        doThrow(new RuntimeException("API error"))
                .when(tideService).fetchAndStoreTideExtremes(
                        org.mockito.ArgumentMatchers.argThat(
                                loc -> "Durham UK".equals(loc.getName())),
                        org.mockito.ArgumentMatchers.isNull());

        scheduledForecastService.refreshTideExtremes();

        ArgumentCaptor<LocationEntity> locCaptor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(tideService, times(2)).fetchAndStoreTideExtremes(locCaptor.capture(),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(locCaptor.getAllValues()).extracting(LocationEntity::getName)
                .containsExactly("Durham UK", "Scarborough");
    }

    // -------------------------------------------------------------------------
    // backfillTideExtremes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("backfillTideExtremes() calls tideService.backfillTideExtremes for SEASCAPE locations")
    void backfillTideExtremes_callsBackfill_forSeascapeLocations() {
        LocationEntity bamburgh = LocationEntity.builder().name("Bamburgh")
                .locationType(Set.of(LocationType.SEASCAPE)).build();
        when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
        when(locationService.isCoastal(bamburgh)).thenReturn(true);
        when(tideService.backfillTideExtremes(org.mockito.ArgumentMatchers.eq(bamburgh),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(52);

        scheduledForecastService.backfillTideExtremes();

        ArgumentCaptor<LocationEntity> locCaptor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(tideService, times(1)).backfillTideExtremes(locCaptor.capture(),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(locCaptor.getValue().getName()).isEqualTo("Bamburgh");
    }

    @Test
    @DisplayName("backfillTideExtremes() skips LANDSCAPE locations")
    void backfillTideExtremes_skipsLandscapeLocations() {
        LocationEntity durham = LocationEntity.builder().name("Durham UK")
                .locationType(Set.of(LocationType.LANDSCAPE)).build();
        when(locationService.findAllEnabled()).thenReturn(List.of(durham));

        scheduledForecastService.backfillTideExtremes();

        verify(tideService, never()).backfillTideExtremes(any(), any());
    }

    @Test
    @DisplayName("backfillTideExtremes() continues after a single location failure")
    void backfillTideExtremes_continuesAfterFailure() {
        LocationEntity loc1 = LocationEntity.builder().name("Loc1")
                .locationType(Set.of(LocationType.SEASCAPE)).build();
        LocationEntity loc2 = LocationEntity.builder().name("Loc2")
                .locationType(Set.of(LocationType.SEASCAPE)).build();
        when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2));
        when(locationService.isCoastal(loc1)).thenReturn(true);
        when(locationService.isCoastal(loc2)).thenReturn(true);
        when(tideService.backfillTideExtremes(org.mockito.ArgumentMatchers.eq(loc1),
                org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new RuntimeException("API error"));
        when(tideService.backfillTideExtremes(org.mockito.ArgumentMatchers.eq(loc2),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(52);

        scheduledForecastService.backfillTideExtremes();

        ArgumentCaptor<LocationEntity> locCaptor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(tideService, times(2)).backfillTideExtremes(locCaptor.capture(),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(locCaptor.getAllValues()).extracting(LocationEntity::getName)
                .containsExactly("Loc1", "Loc2");
    }
}
