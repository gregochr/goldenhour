package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
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

    private ScheduledForecastService scheduledForecastService;

    @BeforeEach
    void setUp() {
        scheduledForecastService = new ScheduledForecastService(
                commandFactory, commandExecutor, tideService, locationService, jobRunService);
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
    @DisplayName("refreshTideExtremes() calls tideService for each coastal location")
    void refreshTideExtremes_callsTideService_forCoastalLocations() {
        LocationEntity durham = LocationEntity.builder().name("Durham UK").build();
        when(locationService.findAll()).thenReturn(List.of(durham));
        when(locationService.isCoastal(any())).thenReturn(true);

        scheduledForecastService.refreshTideExtremes();

        verify(tideService, times(1)).fetchAndStoreTideExtremes(any(), any());
    }

    @Test
    @DisplayName("refreshTideExtremes() skips non-coastal locations")
    void refreshTideExtremes_skipsNonCoastalLocations() {
        LocationEntity durham = LocationEntity.builder().name("Durham UK").build();
        when(locationService.findAll()).thenReturn(List.of(durham));
        when(locationService.isCoastal(any())).thenReturn(false);

        scheduledForecastService.refreshTideExtremes();

        verify(tideService, never()).fetchAndStoreTideExtremes(any(), any());
    }

    @Test
    @DisplayName("refreshTideExtremes() continues after a single location failure")
    void refreshTideExtremes_continuesAfterFailure() {
        LocationEntity durham = LocationEntity.builder().name("Durham UK").build();
        LocationEntity scarborough = LocationEntity.builder().name("Scarborough").build();
        when(locationService.findAll()).thenReturn(List.of(durham, scarborough));
        when(locationService.isCoastal(any())).thenReturn(true);
        doThrow(new RuntimeException("API error"))
                .when(tideService).fetchAndStoreTideExtremes(
                        org.mockito.ArgumentMatchers.argThat(
                                loc -> "Durham UK".equals(loc.getName())), any());

        scheduledForecastService.refreshTideExtremes();

        verify(tideService, times(2)).fetchAndStoreTideExtremes(any(), any());
    }
}
