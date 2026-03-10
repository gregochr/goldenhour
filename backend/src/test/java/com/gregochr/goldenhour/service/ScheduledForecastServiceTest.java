package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RunType;

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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScheduledForecastService}.
 *
 * <p>The service is a thin scheduling wrapper — all orchestration logic is
 * tested in {@link ForecastCommandExecutorTest}.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledForecastServiceTest {

    @Mock
    private ForecastCommandFactory commandFactory;

    @Mock
    private ForecastCommandExecutor commandExecutor;

    @Mock
    private TideService tideService;

    @Mock
    private LocationService locationService;

    @Mock
    private JobRunService jobRunService;

    @Mock
    private ExchangeRateService exchangeRateService;

    private ScheduledForecastService scheduledForecastService;

    @BeforeEach
    void setUp() {
        scheduledForecastService = new ScheduledForecastService(
                commandFactory, commandExecutor, tideService, locationService,
                jobRunService, exchangeRateService);
        lenient().when(commandFactory.create(any(RunType.class), any(boolean.class)))
                .thenReturn(new ForecastCommand(RunType.SHORT_TERM, List.of(), null, null, false));
    }

    @Test
    @DisplayName("runNearTermForecasts() creates SHORT_TERM command and executes it")
    void runNearTermForecasts_createsShortTermCommand() {
        scheduledForecastService.runNearTermForecasts();

        ArgumentCaptor<RunType> rtCaptor = ArgumentCaptor.forClass(RunType.class);
        ArgumentCaptor<Boolean> manualCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(commandFactory).create(rtCaptor.capture(), manualCaptor.capture());
        assertThat(rtCaptor.getValue()).isEqualTo(RunType.SHORT_TERM);
        assertThat(manualCaptor.getValue()).isFalse();
        verify(commandExecutor).execute(any());
    }

    @Test
    @DisplayName("runDistantForecasts() creates LONG_TERM command and executes it")
    void runDistantForecasts_createsLongTermCommand() {
        scheduledForecastService.runDistantForecasts();

        ArgumentCaptor<RunType> rtCaptor = ArgumentCaptor.forClass(RunType.class);
        verify(commandFactory).create(rtCaptor.capture(), any(boolean.class));
        assertThat(rtCaptor.getValue()).isEqualTo(RunType.LONG_TERM);
        verify(commandExecutor).execute(any());
    }

    @Test
    @DisplayName("runWeatherForecasts() creates WEATHER command and executes it")
    void runWeatherForecasts_createsWeatherCommand() {
        scheduledForecastService.runWeatherForecasts();

        ArgumentCaptor<RunType> rtCaptor = ArgumentCaptor.forClass(RunType.class);
        verify(commandFactory).create(rtCaptor.capture(), any(boolean.class));
        assertThat(rtCaptor.getValue()).isEqualTo(RunType.WEATHER);
        verify(commandExecutor).execute(any());
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
        when(locationService.isCoastal(any())).thenReturn(true);

        scheduledForecastService.refreshTideExtremes();

        verify(tideService, times(1)).fetchAndStoreTideExtremes(any(), any());
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
        when(locationService.isCoastal(any())).thenReturn(true);
        doThrow(new RuntimeException("API error"))
                .when(tideService).fetchAndStoreTideExtremes(
                        org.mockito.ArgumentMatchers.argThat(
                                loc -> "Durham UK".equals(loc.getName())), any());

        scheduledForecastService.refreshTideExtremes();

        verify(tideService, times(2)).fetchAndStoreTideExtremes(any(), any());
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
        when(locationService.isCoastal(any())).thenReturn(true);
        when(tideService.backfillTideExtremes(any(), any())).thenReturn(52);

        scheduledForecastService.backfillTideExtremes();

        verify(tideService, times(1)).backfillTideExtremes(any(), any());
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
        when(locationService.isCoastal(any())).thenReturn(true);
        when(tideService.backfillTideExtremes(any(), any()))
                .thenThrow(new RuntimeException("API error"))
                .thenReturn(52);

        scheduledForecastService.backfillTideExtremes();

        verify(tideService, times(2)).backfillTideExtremes(any(), any());
    }
}
