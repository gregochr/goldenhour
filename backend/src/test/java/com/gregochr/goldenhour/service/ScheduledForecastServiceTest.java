package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.ForecastProperties;
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

/**
 * Unit tests for {@link ScheduledForecastService}.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledForecastServiceTest {

    @Mock
    private ForecastService forecastService;

    private ForecastProperties forecastProperties;
    private ScheduledForecastService scheduledForecastService;

    @BeforeEach
    void setUp() {
        forecastProperties = new ForecastProperties();
        ForecastProperties.Location durham = new ForecastProperties.Location();
        durham.setName("Durham UK");
        durham.setLat(54.7753);
        durham.setLon(-1.5849);
        forecastProperties.setLocations(List.of(durham));
        scheduledForecastService = new ScheduledForecastService(forecastService, forecastProperties);
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
        ForecastProperties twoLocations = new ForecastProperties();
        ForecastProperties.Location loc1 = new ForecastProperties.Location();
        loc1.setName("Durham UK");
        loc1.setLat(54.7753);
        loc1.setLon(-1.5849);
        ForecastProperties.Location loc2 = new ForecastProperties.Location();
        loc2.setName("London UK");
        loc2.setLat(51.5074);
        loc2.setLon(-0.1278);
        twoLocations.setLocations(List.of(loc1, loc2));

        org.mockito.Mockito.doThrow(new RuntimeException("API error"))
                .when(forecastService).runForecasts(eq("Durham UK"), anyDouble(), anyDouble(), any());

        ScheduledForecastService service =
                new ScheduledForecastService(forecastService, twoLocations);
        service.runScheduledForecasts();

        // London calls should still happen despite Durham failures
        verify(forecastService, times(ScheduledForecastService.FORECAST_HORIZON_DAYS + 1))
                .runForecasts(eq("London UK"), anyDouble(), anyDouble(), any(LocalDate.class));
    }
}
