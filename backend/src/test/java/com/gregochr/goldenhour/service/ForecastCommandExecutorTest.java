package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.GoldenHourType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.evaluation.HaikuEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.NoOpEvaluationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

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
 * Unit tests for {@link ForecastCommandExecutor}.
 */
@ExtendWith(MockitoExtension.class)
class ForecastCommandExecutorTest {

    @Mock
    private ForecastService forecastService;

    @Mock
    private LocationService locationService;

    @Mock
    private JobRunService jobRunService;

    @Mock
    private SolarService solarService;

    @Mock
    private ForecastCommandFactory commandFactory;

    @Mock
    private ForecastEvaluationRepository forecastRepository;

    @Mock
    private HaikuEvaluationStrategy haikuStrategy;

    @Mock
    private NoOpEvaluationStrategy noOpStrategy;

    private ForecastCommandExecutor executor;

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
        lenient().when(locationService.shouldEvaluateSunrise(any())).thenReturn(true);
        lenient().when(locationService.shouldEvaluateSunset(any())).thenReturn(true);
        // Return MAX so shouldSkipEvent() never skips
        lenient().when(solarService.sunriseUtc(anyDouble(), anyDouble(), any()))
                .thenReturn(LocalDateTime.MAX);
        lenient().when(solarService.sunsetUtc(anyDouble(), anyDouble(), any()))
                .thenReturn(LocalDateTime.MAX);
        lenient().when(forecastRepository
                .findByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(commandFactory.resolveEvaluationModel(any())).thenReturn(EvaluationModel.HAIKU);

        // Use synchronous executor
        executor = new ForecastCommandExecutor(
                forecastService, locationService, jobRunService, solarService,
                commandFactory, Runnable::run, forecastRepository);
    }

    @Test
    @DisplayName("execute() calls forecastService once per target type per day for colour locations")
    void execute_colourLocations_callsForecastServiceForEachTargetTypePerDay() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today, today.plusDays(1), today.plusDays(2));
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, dates, List.of(durham()),
                haikuStrategy, true);

        executor.execute(cmd);

        int expectedCalls = dates.size() * EXPECTED_CALLS_PER_DAY;
        verify(forecastService, times(expectedCalls))
                .runForecasts(eq("Durham UK"), anyDouble(), anyDouble(),
                        any(), any(LocalDate.class), any(TargetType.class), any(),
                        eq(EvaluationModel.HAIKU), any());
    }

    @Test
    @DisplayName("execute() continues after a single location failure")
    void execute_continuesAfterFailure() {
        LocationEntity london = LocationEntity.builder()
                .name("London UK")
                .lat(51.5074)
                .lon(-0.1278)
                .goldenHourType(GoldenHourType.BOTH_TIMES)
                .build();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today);

        doThrow(new RuntimeException("API error"))
                .when(forecastService).runForecasts(eq("Durham UK"), anyDouble(), anyDouble(),
                        any(), any(), any(TargetType.class), any(), any(EvaluationModel.class), any());

        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, dates,
                List.of(durham(), london), haikuStrategy, true);

        executor.execute(cmd);

        // London calls should still happen despite Durham failures
        verify(forecastService, times(EXPECTED_CALLS_PER_DAY))
                .runForecasts(eq("London UK"), anyDouble(), anyDouble(),
                        any(), any(LocalDate.class), any(TargetType.class), any(),
                        any(EvaluationModel.class), any());
    }

    @Test
    @DisplayName("execute() with NoOp strategy calls forecastService with WILDLIFE model")
    void execute_wildlife_callsForecastServiceWithWildlife() {
        when(commandFactory.resolveEvaluationModel(any())).thenReturn(EvaluationModel.WILDLIFE);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today);

        ForecastCommand cmd = new ForecastCommand(RunType.WEATHER, dates,
                List.of(wildlifeReserve()), noOpStrategy, false);

        executor.execute(cmd);

        verify(forecastService, times(1))
                .runForecasts(eq("Wildlife Reserve"), anyDouble(), anyDouble(),
                        any(), any(LocalDate.class), org.mockito.ArgumentMatchers.isNull(),
                        any(), eq(EvaluationModel.WILDLIFE), any());
    }

    @Test
    @DisplayName("execute() with NoOp strategy does NOT call for colour locations")
    void execute_wildlife_excludesColourLocations() {
        when(commandFactory.resolveEvaluationModel(any())).thenReturn(EvaluationModel.WILDLIFE);
        when(locationService.findAll()).thenReturn(List.of(durham(), wildlifeReserve()));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today);

        // When locations are null, executor resolves from locationService.findAll()
        ForecastCommand cmd = new ForecastCommand(RunType.WEATHER, dates,
                null, noOpStrategy, false);

        executor.execute(cmd);

        verify(forecastService, never())
                .runForecasts(eq("Durham UK"), anyDouble(), anyDouble(),
                        any(), any(LocalDate.class), any(),
                        any(), eq(EvaluationModel.WILDLIFE), any());
    }

    @Test
    @DisplayName("hasColourTypes() returns true for untyped location")
    void hasColourTypes_untypedLocation_returnsTrue() {
        assertThat(executor.hasColourTypes(durham())).isTrue();
    }

    @Test
    @DisplayName("hasColourTypes() returns false for pure-WILDLIFE location")
    void hasColourTypes_pureWildlifeLocation_returnsFalse() {
        assertThat(executor.hasColourTypes(wildlifeReserve())).isFalse();
    }

    @Test
    @DisplayName("isPureWildlife() returns true for pure-WILDLIFE location")
    void isPureWildlife_pureWildlifeLocation_returnsTrue() {
        assertThat(executor.isPureWildlife(wildlifeReserve())).isTrue();
    }

    @Test
    @DisplayName("isPureWildlife() returns false for untyped location")
    void isPureWildlife_untypedLocation_returnsFalse() {
        assertThat(executor.isPureWildlife(durham())).isFalse();
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
        assertThat(executor.isPureWildlife(mixed)).isFalse();
    }

    // -------------------------------------------------------------------------
    // Long-term forecast optimization: skip T+3+ if exists
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("execute() skips T+3+ dates where forecasts already exist")
    void execute_skipsExistingLongTermForecasts() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate t3 = today.plusDays(3);
        LocalDate t4 = today.plusDays(4);
        LocalDate t5 = today.plusDays(5);

        // T+5 already exists
        when(forecastRepository.findByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                eq("Durham UK"), eq(t5), any()))
                .thenReturn(List.of(
                        ForecastEvaluationEntity.builder()
                                .id(1L).locationName("Durham UK").targetDate(t5)
                                .targetType(TargetType.SUNRISE)
                                .forecastRunAt(LocalDateTime.now().minusDays(1))
                                .build()
                ));
        when(forecastRepository.findByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                eq("Durham UK"), argThat(d -> !d.equals(t5)), any()))
                .thenReturn(List.of());

        ForecastCommand cmd = new ForecastCommand(RunType.LONG_TERM, List.of(t3, t4, t5),
                List.of(durham()), haikuStrategy, false);

        executor.execute(cmd);

        // T+3 and T+4 should run (2 target types each = 4), T+5 should be skipped
        verify(forecastService, times(4))
                .runForecasts(eq("Durham UK"), anyDouble(), anyDouble(),
                        any(), any(LocalDate.class), any(TargetType.class), any(),
                        eq(EvaluationModel.HAIKU), any());
    }

    // -------------------------------------------------------------------------
    // Opus optimisation: skip low-rated or missing prior evaluations
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Opus run proceeds when prior evaluation has rating >= 3")
    void opusRun_withHighPriorRating_doesNotSkip() {
        when(commandFactory.resolveEvaluationModel(any())).thenReturn(EvaluationModel.OPUS);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        ForecastEvaluationEntity prior = ForecastEvaluationEntity.builder()
                .id(1L).locationName("Durham UK").targetDate(today)
                .targetType(TargetType.SUNRISE).rating(4)
                .forecastRunAt(LocalDateTime.now().minusHours(2))
                .build();
        when(forecastRepository.findTopByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                eq("Durham UK"), eq(today), any()))
                .thenReturn(Optional.of(prior));

        ForecastCommand cmd = new ForecastCommand(RunType.VERY_SHORT_TERM, List.of(today),
                List.of(durham()), haikuStrategy, true);

        executor.execute(cmd);

        verify(forecastService, times(EXPECTED_CALLS_PER_DAY))
                .runForecasts(eq("Durham UK"), anyDouble(), anyDouble(),
                        any(), eq(today), any(TargetType.class), any(),
                        eq(EvaluationModel.OPUS), any());
    }

    @Test
    @DisplayName("Opus run skips when prior evaluation has rating < 3")
    void opusRun_withLowPriorRating_skips() {
        when(commandFactory.resolveEvaluationModel(any())).thenReturn(EvaluationModel.OPUS);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        ForecastEvaluationEntity prior = ForecastEvaluationEntity.builder()
                .id(1L).locationName("Durham UK").targetDate(today)
                .targetType(TargetType.SUNRISE).rating(2)
                .forecastRunAt(LocalDateTime.now().minusHours(2))
                .build();
        when(forecastRepository.findTopByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                eq("Durham UK"), eq(today), any()))
                .thenReturn(Optional.of(prior));

        ForecastCommand cmd = new ForecastCommand(RunType.VERY_SHORT_TERM, List.of(today),
                List.of(durham()), haikuStrategy, true);

        executor.execute(cmd);

        verify(forecastService, never())
                .runForecasts(eq("Durham UK"), anyDouble(), anyDouble(),
                        any(), eq(today), any(TargetType.class), any(),
                        eq(EvaluationModel.OPUS), any());
    }

    @Test
    @DisplayName("Opus run skips when no prior evaluation exists")
    void opusRun_withNoPriorEvaluation_skips() {
        when(commandFactory.resolveEvaluationModel(any())).thenReturn(EvaluationModel.OPUS);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        when(forecastRepository.findTopByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                eq("Durham UK"), eq(today), any()))
                .thenReturn(Optional.empty());

        ForecastCommand cmd = new ForecastCommand(RunType.VERY_SHORT_TERM, List.of(today),
                List.of(durham()), haikuStrategy, true);

        executor.execute(cmd);

        verify(forecastService, never())
                .runForecasts(eq("Durham UK"), anyDouble(), anyDouble(),
                        any(), eq(today), any(TargetType.class), any(),
                        eq(EvaluationModel.OPUS), any());
    }

    @Test
    @DisplayName("Haiku run never triggers Opus skip logic")
    void haikuRun_neverSkipsViaOpusLogic() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today, today.plusDays(1), today.plusDays(2));

        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, dates,
                List.of(durham()), haikuStrategy, false);

        executor.execute(cmd);

        int expectedCalls = dates.size() * EXPECTED_CALLS_PER_DAY;
        verify(forecastService, times(expectedCalls))
                .runForecasts(eq("Durham UK"), anyDouble(), anyDouble(),
                        any(), any(LocalDate.class), any(TargetType.class), any(),
                        eq(EvaluationModel.HAIKU), any());
        // findTop query should never be called for non-Opus
        verify(forecastRepository, never())
                .findTopByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                        any(), any(), any());
    }
}
