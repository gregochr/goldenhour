package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.GoldenHourType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    @Mock
    private JobRunService jobRunService;

    @Mock
    private ModelSelectionService modelSelectionService;

    @Mock
    private SolarService solarService;

    @Mock
    private ForecastEvaluationRepository forecastRepository;

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

    private static LocationEntity wildlifeReserve() {
        return LocationEntity.builder()
                .name("Wildlife Reserve")
                .lat(53.5)
                .lon(-1.2)
                .goldenHourType(GoldenHourType.BOTH_TIMES)
                .locationType(java.util.Set.of(LocationType.WILDLIFE))
                .build();
    }

    @BeforeEach
    void setUp() {
        lenient().when(locationService.findAll()).thenReturn(List.of(durham()));
        // lenient: only needed by forecast tests, not refreshTideExtremes tests
        lenient().when(locationService.shouldEvaluateSunrise(any())).thenReturn(true);
        lenient().when(locationService.shouldEvaluateSunset(any())).thenReturn(true);
        lenient().when(modelSelectionService.getActiveModel()).thenReturn(EvaluationModel.HAIKU);
        // Return MAX so shouldSkipEvent() never skips (now.isAfter(MAX) == false)
        lenient().when(solarService.sunriseUtc(anyDouble(), anyDouble(), any()))
                .thenReturn(LocalDateTime.MAX);
        lenient().when(solarService.sunsetUtc(anyDouble(), anyDouble(), any()))
                .thenReturn(LocalDateTime.MAX);
        // Return empty list so long-term forecasts are not skipped by default
        lenient().when(forecastRepository.findByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                any(), any(), any())).thenReturn(List.of());
        // Use a synchronous executor so tests run deterministically without thread pools
        scheduledForecastService = new ScheduledForecastService(
                forecastService, locationService, tideService, jobRunService, modelSelectionService,
                solarService, Runnable::run, forecastRepository);
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
                        eq(EvaluationModel.SONNET), any());
    }

    @Test
    @DisplayName("runNearTermForecasts() calls forecastService with HAIKU for T, T+1, T+2")
    void runNearTermForecasts_callsForecastService_withHaikuModel() {
        scheduledForecastService.runNearTermForecasts();

        int nearTermDays = 3; // T, T+1, T+2
        int expectedCalls = nearTermDays * EXPECTED_CALLS_PER_DAY;
        verify(forecastService, times(expectedCalls))
                .runForecasts(eq("Durham UK"), anyDouble(), anyDouble(),
                        any(), any(LocalDate.class), any(TargetType.class), any(),
                        eq(EvaluationModel.HAIKU), any());
    }

    @Test
    @DisplayName("runDistantForecasts() calls forecastService with HAIKU for T+3 through T+7")
    void runDistantForecasts_callsForecastService_withHaikuModel() {
        scheduledForecastService.runDistantForecasts();

        int distantDays = 5; // T+3, T+4, T+5, T+6, T+7
        // Distant forecasts only run once daily (no need to skip sunrise/sunset)
        // but shouldSkipEvent still applies to today's sunset
        int expectedCalls = distantDays * EXPECTED_CALLS_PER_DAY;
        verify(forecastService, times(expectedCalls))
                .runForecasts(eq("Durham UK"), anyDouble(), anyDouble(),
                        any(), any(LocalDate.class), any(TargetType.class), any(),
                        eq(EvaluationModel.HAIKU), any());
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
                        any(EvaluationModel.class), any());

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
                        any(), any(), any(TargetType.class), any(), any(EvaluationModel.class), any());

        scheduledForecastService.runSonnetForecasts();

        // London calls should still happen despite Durham failures
        int expectedLondonCalls =
                (ScheduledForecastService.FORECAST_HORIZON_DAYS + 1) * EXPECTED_CALLS_PER_DAY;
        verify(forecastService, times(expectedLondonCalls))
                .runForecasts(eq("London UK"), anyDouble(), anyDouble(),
                        any(), any(LocalDate.class), any(TargetType.class), any(),
                        any(EvaluationModel.class), any());
    }

    @Test
    @DisplayName("runWeatherForecasts() calls forecastService with WILDLIFE only for pure-WILDLIFE locations")
    void runWeatherForecasts_callsForecastService_withWildlifeModel_forWildlifeLocations() {
        when(locationService.findAll()).thenReturn(List.of(durham(), wildlifeReserve()));
        lenient().when(locationService.shouldEvaluateSunrise(any())).thenReturn(true);
        lenient().when(locationService.shouldEvaluateSunset(any())).thenReturn(true);
        lenient().when(modelSelectionService.getActiveModel()).thenReturn(EvaluationModel.HAIKU);
        lenient().when(forecastRepository.findByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                any(), any(), any())).thenReturn(List.of());
        scheduledForecastService = new ScheduledForecastService(
                forecastService, locationService, tideService, jobRunService, modelSelectionService,
                solarService, Runnable::run, forecastRepository);

        scheduledForecastService.runWeatherForecasts();

        // WILDLIFE model: one call per day (null targetType — hourly runs handled internally)
        int daysInHorizon = ScheduledForecastService.FORECAST_HORIZON_DAYS + 1;
        verify(forecastService, times(daysInHorizon))
                .runForecasts(eq("Wildlife Reserve"), anyDouble(), anyDouble(),
                        any(), any(LocalDate.class), org.mockito.ArgumentMatchers.isNull(),
                        any(), eq(EvaluationModel.WILDLIFE), any());
        // Durham (untyped = colour) must NOT receive WILDLIFE calls
        verify(forecastService, never())
                .runForecasts(eq("Durham UK"), anyDouble(), anyDouble(),
                        any(), any(LocalDate.class), any(),
                        any(), eq(EvaluationModel.WILDLIFE), any());
    }

    @Test
    @DisplayName("runSonnetForecasts() excludes pure-WILDLIFE locations")
    void runSonnetForecasts_excludesPureWildlifeLocations() {
        when(locationService.findAll()).thenReturn(List.of(durham(), wildlifeReserve()));
        lenient().when(locationService.shouldEvaluateSunrise(any())).thenReturn(true);
        lenient().when(locationService.shouldEvaluateSunset(any())).thenReturn(true);
        lenient().when(modelSelectionService.getActiveModel()).thenReturn(EvaluationModel.HAIKU);
        lenient().when(forecastRepository.findByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                any(), any(), any())).thenReturn(List.of());
        scheduledForecastService = new ScheduledForecastService(
                forecastService, locationService, tideService, jobRunService, modelSelectionService,
                solarService, Runnable::run, forecastRepository);

        scheduledForecastService.runSonnetForecasts();

        // Wildlife Reserve must NOT receive SONNET calls
        verify(forecastService, never())
                .runForecasts(eq("Wildlife Reserve"), anyDouble(), anyDouble(),
                        any(), any(LocalDate.class), any(TargetType.class), any(),
                        eq(EvaluationModel.SONNET), any());
    }

    @Test
    @DisplayName("hasColourTypes() returns true for untyped location")
    void hasColourTypes_untypedLocation_returnsTrue() {
        assertThat(scheduledForecastService.hasColourTypes(durham())).isTrue();
    }

    @Test
    @DisplayName("hasColourTypes() returns false for pure-WILDLIFE location")
    void hasColourTypes_pureWildlifeLocation_returnsFalse() {
        assertThat(scheduledForecastService.hasColourTypes(wildlifeReserve())).isFalse();
    }

    @Test
    @DisplayName("isPureWildlife() returns true for pure-WILDLIFE location")
    void isPureWildlife_pureWildlifeLocation_returnsTrue() {
        assertThat(scheduledForecastService.isPureWildlife(wildlifeReserve())).isTrue();
    }

    @Test
    @DisplayName("isPureWildlife() returns false for untyped location")
    void isPureWildlife_untypedLocation_returnsFalse() {
        assertThat(scheduledForecastService.isPureWildlife(durham())).isFalse();
    }

    @Test
    @DisplayName("isPureWildlife() returns false for mixed LANDSCAPE+WILDLIFE location")
    void isPureWildlife_mixedLocation_returnsFalse() {
        LocationEntity mixed = LocationEntity.builder()
                .name("Coastal Park")
                .lat(54.0)
                .lon(-1.5)
                .goldenHourType(GoldenHourType.BOTH_TIMES)
                .locationType(java.util.Set.of(LocationType.LANDSCAPE, LocationType.WILDLIFE))
                .build();
        assertThat(scheduledForecastService.isPureWildlife(mixed)).isFalse();
    }

    // -------------------------------------------------------------------------
    // refreshTideExtremes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("refreshTideExtremes() calls tideService for each coastal location")
    void refreshTideExtremes_callsTideService_forCoastalLocations() {
        when(locationService.isCoastal(any())).thenReturn(true);

        scheduledForecastService.refreshTideExtremes();

        verify(tideService, times(1)).fetchAndStoreTideExtremes(any(), any());
    }

    @Test
    @DisplayName("refreshTideExtremes() skips non-coastal locations")
    void refreshTideExtremes_skipsNonCoastalLocations() {
        when(locationService.isCoastal(any())).thenReturn(false);

        scheduledForecastService.refreshTideExtremes();

        verify(tideService, never()).fetchAndStoreTideExtremes(any(), any());
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
                        argThat(loc -> "Durham UK".equals(loc.getName())), any());

        scheduledForecastService.refreshTideExtremes();

        // Scarborough should still be processed despite Durham failure
        verify(tideService, times(2)).fetchAndStoreTideExtremes(any(), any());
    }

    // -------------------------------------------------------------------------
    // Long-term forecast optimization: skip T+3+ if exists, always run T, T+1, T+2
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("runNearTermForecasts() always runs for T, T+1, T+2 even if forecasts already exist")
    void runNearTermForecasts_alwaysRuns_evenIfExistingForecasts() {
        // No repository stubbing needed — shouldSkipLongTermForecast() exits early for daysAhead < 3
        // so the repository is never queried for near-term dates. This is the key assertion:
        // near-term forecasts bypass the existing-forecast check entirely.

        scheduledForecastService.runNearTermForecasts();

        // Despite existing forecasts, all 6 calls (3 days × 2 types) should happen
        int nearTermDays = 3; // T, T+1, T+2
        int expectedCalls = nearTermDays * EXPECTED_CALLS_PER_DAY;
        verify(forecastService, times(expectedCalls))
                .runForecasts(eq("Durham UK"), anyDouble(), anyDouble(),
                        any(), any(LocalDate.class), any(TargetType.class), any(),
                        eq(EvaluationModel.HAIKU), any());
    }

    @Test
    @DisplayName("runDistantForecasts() skips T+3+ dates where forecasts already exist")
    void runDistantForecasts_skipsExistingLongTermForecasts() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate t5 = today.plusDays(5);
        LocalDate t7 = today.plusDays(7);

        // Mock: T+5 and T+7 already exist, but T+3, T+4, T+6 do not
        when(forecastRepository.findByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                eq("Durham UK"), eq(t5), any()))
                .thenReturn(List.of(
                        ForecastEvaluationEntity.builder()
                                .id(1L)
                                .locationName("Durham UK")
                                .targetDate(t5)
                                .targetType(TargetType.SUNRISE)
                                .forecastRunAt(LocalDateTime.now().minusDays(1))
                                .build()
                ));
        when(forecastRepository.findByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                eq("Durham UK"), eq(t7), any()))
                .thenReturn(List.of(
                        ForecastEvaluationEntity.builder()
                                .id(2L)
                                .locationName("Durham UK")
                                .targetDate(t7)
                                .targetType(TargetType.SUNRISE)
                                .forecastRunAt(LocalDateTime.now().minusDays(1))
                                .build()
                ));
        // All other dates return empty (will be generated)
        when(forecastRepository.findByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                eq("Durham UK"), argThat(d -> !d.equals(t5) && !d.equals(t7)), any()))
                .thenReturn(List.of());

        scheduledForecastService.runDistantForecasts();

        // Should call forecastService 6 times:
        // T+3 (2), T+4 (2), T+5 SKIPPED (both events), T+6 (2), T+7 SKIPPED (both events) = 6 calls
        verify(forecastService, times(6))
                .runForecasts(eq("Durham UK"), anyDouble(), anyDouble(),
                        any(), any(LocalDate.class), any(TargetType.class), any(),
                        eq(EvaluationModel.HAIKU), any());
    }
}
