package com.gregochr.goldenhour.service;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * Unit tests for {@link ScheduledForecastService}.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledForecastServiceTest {

    @Mock
    private ForecastService forecastService;

    @Mock
    private LocationService locationService;

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
        when(locationService.shouldEvaluateSunrise(any())).thenReturn(true);
        when(locationService.shouldEvaluateSunset(any())).thenReturn(true);
        scheduledForecastService = new ScheduledForecastService(forecastService, locationService);
    }

    @Test
    @DisplayName("runScheduledForecasts() calls forecastService once per target type per day")
    void runScheduledForecasts_callsForecastService_forEachTargetTypeAndDay() {
        scheduledForecastService.runScheduledForecasts();

        int daysInHorizon = ScheduledForecastService.FORECAST_HORIZON_DAYS + 1;
        int expectedCalls = daysInHorizon * EXPECTED_CALLS_PER_DAY;
        verify(forecastService, times(expectedCalls))
                .runForecasts(eq("Durham UK"), anyDouble(), anyDouble(),
                        any(LocalDate.class), any(TargetType.class), any());
    }

    @Test
    @DisplayName("runScheduledForecasts() forecasts from today through today+7 for both target types")
    void runScheduledForecasts_datesRangeFromTodayToHorizon() {
        scheduledForecastService.runScheduledForecasts();

        int totalCalls = (ScheduledForecastService.FORECAST_HORIZON_DAYS + 1) * EXPECTED_CALLS_PER_DAY;
        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(forecastService, times(totalCalls))
                .runForecasts(any(), anyDouble(), anyDouble(), dateCaptor.capture(), any(TargetType.class), any());

        List<LocalDate> capturedDates = dateCaptor.getAllValues();
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        assertThat(capturedDates).contains(today);
        assertThat(capturedDates).contains(today.plusDays(ScheduledForecastService.FORECAST_HORIZON_DAYS));
        assertThat(capturedDates).hasSize(totalCalls);
    }

    @Test
    @DisplayName("runScheduledForecasts() continues after a single location failure")
    void runScheduledForecasts_continuesAfterFailure() {
        LocationEntity london = LocationEntity.builder()
                .name("London UK")
                .lat(51.5074)
                .lon(-0.1278)
                .goldenHourType(GoldenHourType.BOTH_TIMES)
                .build();
        when(locationService.findAll()).thenReturn(List.of(durham(), london));

        doThrow(new RuntimeException("API error"))
                .when(forecastService).runForecasts(eq("Durham UK"), anyDouble(), anyDouble(),
                        any(), any(TargetType.class), any());

        scheduledForecastService.runScheduledForecasts();

        // London calls should still happen despite Durham failures
        int expectedLondonCalls = (ScheduledForecastService.FORECAST_HORIZON_DAYS + 1) * EXPECTED_CALLS_PER_DAY;
        verify(forecastService, times(expectedLondonCalls))
                .runForecasts(eq("London UK"), anyDouble(), anyDouble(),
                        any(LocalDate.class), any(TargetType.class), any());
    }
}
