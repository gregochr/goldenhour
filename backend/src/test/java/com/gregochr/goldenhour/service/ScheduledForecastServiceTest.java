package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
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

    private static LocationEntity durham() {
        return LocationEntity.builder()
                .name("Durham UK")
                .lat(54.7753)
                .lon(-1.5849)
                .build();
    }

    @BeforeEach
    void setUp() {
        when(locationService.findAll()).thenReturn(List.of(durham()));
        scheduledForecastService = new ScheduledForecastService(forecastService, locationService);
    }

    @Test
    @DisplayName("runScheduledForecasts() calls forecastService once per day in the horizon")
    void runScheduledForecasts_callsForecastService_forEachDayInHorizon() {
        scheduledForecastService.runScheduledForecasts();

        int expectedCalls = ScheduledForecastService.FORECAST_HORIZON_DAYS + 1;
        verify(forecastService, times(expectedCalls))
                .runForecasts(eq("Durham UK"), anyDouble(), anyDouble(), any(LocalDate.class));
    }

    @Test
    @DisplayName("runScheduledForecasts() forecasts from today through today+7")
    void runScheduledForecasts_datesRangeFromTodayToHorizon() {
        scheduledForecastService.runScheduledForecasts();

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(forecastService, times(ScheduledForecastService.FORECAST_HORIZON_DAYS + 1))
                .runForecasts(any(), anyDouble(), anyDouble(), dateCaptor.capture());

        List<LocalDate> capturedDates = dateCaptor.getAllValues();
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        assertThat(capturedDates).contains(today);
        assertThat(capturedDates).contains(today.plusDays(ScheduledForecastService.FORECAST_HORIZON_DAYS));
        assertThat(capturedDates).hasSize(ScheduledForecastService.FORECAST_HORIZON_DAYS + 1);
    }

    @Test
    @DisplayName("runScheduledForecasts() continues after a single location failure")
    void runScheduledForecasts_continuesAfterFailure() {
        LocationEntity london = LocationEntity.builder()
                .name("London UK")
                .lat(51.5074)
                .lon(-0.1278)
                .build();
        when(locationService.findAll()).thenReturn(List.of(durham(), london));

        org.mockito.Mockito.doThrow(new RuntimeException("API error"))
                .when(forecastService).runForecasts(eq("Durham UK"), anyDouble(), anyDouble(), any());

        scheduledForecastService.runScheduledForecasts();

        // London calls should still happen despite Durham failures
        verify(forecastService, times(ScheduledForecastService.FORECAST_HORIZON_DAYS + 1))
                .runForecasts(eq("London UK"), anyDouble(), anyDouble(), any(LocalDate.class));
    }
}
