package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.GoldenHourType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScheduledForecastService}.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledForecastServiceTest {

    @Mock
    private ForecastService forecastService;

    @Mock
    private LocationService locationService;

    @Mock
    private TideService tideService;

    private ScheduledForecastService scheduledForecastService;

    private static final int EXPECTED_CALLS_PER_DAY = 2; // SUNRISE + SUNSET for BOTH_TIMES

    private static LocationEntity durham() {
        return LocationEntity.builder()
                .name("Durham UK")
                .lat(54.7753)
                .lon(-1.5849)
                .goldenHourType(GoldenHourType.BOTH_TIMES)
                .build();
    }

    @BeforeEach
    void setUp() {
        when(locationService.findAll()).thenReturn(List.of(durham()));
        // lenient: only needed by forecast tests, not refreshTideExtremes tests
        lenient().when(locationService.shouldEvaluateSunrise(any())).thenReturn(true);
        lenient().when(locationService.shouldEvaluateSunset(any())).thenReturn(true);
        scheduledForecastService = new ScheduledForecastService(
                forecastService, locationService, tideService);
    }

    @Test
    @DisplayName("runSonnetForecasts() calls forecastService with SONNET once per target type per day")
    void runSonnetForecasts_callsForecastService_withSonnetModel() {
        scheduledForecastService.runSonnetForecasts();

        int daysInHorizon = ScheduledForecastService.FORECAST_HORIZON_DAYS + 1;
        int expectedCalls = daysInHorizon * EXPECTED_CALLS_PER_DAY;
        verify(forecastService, times(expectedCalls))
                .runForecasts(eq("Durham UK"), anyDouble(), anyDouble(),
                        any(), any(LocalDate.class), any(TargetType.class), any(),
                        eq(EvaluationModel.SONNET));
    }

    @Test
    @DisplayName("runHaikuForecasts() calls forecastService with HAIKU once per target type per day")
    void runHaikuForecasts_callsForecastService_withHaikuModel() {
        scheduledForecastService.runHaikuForecasts();

        int daysInHorizon = ScheduledForecastService.FORECAST_HORIZON_DAYS + 1;
        int expectedCalls = daysInHorizon * EXPECTED_CALLS_PER_DAY;
        verify(forecastService, times(expectedCalls))
                .runForecasts(eq("Durham UK"), anyDouble(), anyDouble(),
                        any(), any(LocalDate.class), any(TargetType.class), any(),
                        eq(EvaluationModel.HAIKU));
    }

    @Test
    @DisplayName("runSonnetForecasts() forecasts from today through today+7 for both target types")
    void runSonnetForecasts_datesRangeFromTodayToHorizon() {
        scheduledForecastService.runSonnetForecasts();

        int totalCalls = (ScheduledForecastService.FORECAST_HORIZON_DAYS + 1) * EXPECTED_CALLS_PER_DAY;
        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(forecastService, times(totalCalls))
                .runForecasts(any(), anyDouble(), anyDouble(), any(),
                        dateCaptor.capture(), any(TargetType.class), any(),
                        any(EvaluationModel.class));

        List<LocalDate> capturedDates = dateCaptor.getAllValues();
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        assertThat(capturedDates).contains(today);
        assertThat(capturedDates).contains(today.plusDays(ScheduledForecastService.FORECAST_HORIZON_DAYS));
        assertThat(capturedDates).hasSize(totalCalls);
    }

    @Test
    @DisplayName("runSonnetForecasts() continues after a single location failure")
    void runSonnetForecasts_continuesAfterFailure() {
        LocationEntity london = LocationEntity.builder()
                .name("London UK")
                .lat(51.5074)
                .lon(-0.1278)
                .goldenHourType(GoldenHourType.BOTH_TIMES)
                .build();
        when(locationService.findAll()).thenReturn(List.of(durham(), london));

        doThrow(new RuntimeException("API error"))
                .when(forecastService).runForecasts(eq("Durham UK"), anyDouble(), anyDouble(),
                        any(), any(), any(TargetType.class), any(), any(EvaluationModel.class));

        scheduledForecastService.runSonnetForecasts();

        // London calls should still happen despite Durham failures
        int expectedLondonCalls =
                (ScheduledForecastService.FORECAST_HORIZON_DAYS + 1) * EXPECTED_CALLS_PER_DAY;
        verify(forecastService, times(expectedLondonCalls))
                .runForecasts(eq("London UK"), anyDouble(), anyDouble(),
                        any(), any(LocalDate.class), any(TargetType.class), any(),
                        any(EvaluationModel.class));
    }

    // -------------------------------------------------------------------------
    // refreshTideExtremes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("refreshTideExtremes() calls tideService for each coastal location")
    void refreshTideExtremes_callsTideService_forCoastalLocations() {
        when(locationService.isCoastal(any())).thenReturn(true);

        scheduledForecastService.refreshTideExtremes();

        verify(tideService, times(1)).fetchAndStoreTideExtremes(any());
    }

    @Test
    @DisplayName("refreshTideExtremes() skips non-coastal locations")
    void refreshTideExtremes_skipsNonCoastalLocations() {
        when(locationService.isCoastal(any())).thenReturn(false);

        scheduledForecastService.refreshTideExtremes();

        verify(tideService, never()).fetchAndStoreTideExtremes(any());
    }

    @Test
    @DisplayName("refreshTideExtremes() continues after a single location failure")
    void refreshTideExtremes_continuesAfterFailure() {
        LocationEntity scarborough = LocationEntity.builder()
                .name("Scarborough")
                .lat(54.28)
                .lon(-0.40)
                .build();
        when(locationService.findAll()).thenReturn(List.of(durham(), scarborough));
        when(locationService.isCoastal(any())).thenReturn(true);
        doThrow(new RuntimeException("API error"))
                .when(tideService).fetchAndStoreTideExtremes(
                        argThat(loc -> "Durham UK".equals(loc.getName())));

        scheduledForecastService.refreshTideExtremes();

        // Scarborough should still be processed despite Durham failure
        verify(tideService, times(2)).fetchAndStoreTideExtremes(any());
    }
}
