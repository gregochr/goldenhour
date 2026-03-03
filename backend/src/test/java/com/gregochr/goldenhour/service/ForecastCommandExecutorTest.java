package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.GoldenHourType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.OptimisationStrategyEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
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
    private OptimisationSkipEvaluator optimisationSkipEvaluator;

    @Mock
    private OptimisationStrategyService optimisationStrategyService;

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
        lenient().when(locationService.findAllEnabled()).thenReturn(List.of(durham()));
        lenient().when(locationService.shouldEvaluateSunrise(any())).thenReturn(true);
        lenient().when(locationService.shouldEvaluateSunset(any())).thenReturn(true);
        // Return MAX so shouldSkipEvent() never skips
        lenient().when(solarService.sunriseUtc(anyDouble(), anyDouble(), any()))
                .thenReturn(LocalDateTime.MAX);
        lenient().when(solarService.sunsetUtc(anyDouble(), anyDouble(), any()))
                .thenReturn(LocalDateTime.MAX);
        lenient().when(commandFactory.resolveEvaluationModel(any())).thenReturn(EvaluationModel.HAIKU);
        lenient().when(optimisationSkipEvaluator.shouldSkip(any(), anyString(), any(), any()))
                .thenReturn(false);
        lenient().when(optimisationStrategyService.getEnabledStrategies(any())).thenReturn(List.of());
        lenient().when(optimisationStrategyService.serialiseEnabledStrategies(any())).thenReturn("");

        // Use synchronous executor
        executor = new ForecastCommandExecutor(
                forecastService, locationService, jobRunService, solarService,
                commandFactory, Runnable::run, optimisationSkipEvaluator,
                optimisationStrategyService);
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
        when(locationService.findAllEnabled()).thenReturn(List.of(durham(), wildlifeReserve()));
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
    // Optimisation skip delegation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("execute() delegates skip decision to OptimisationSkipEvaluator")
    void execute_delegatesToSkipEvaluator() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today);

        var strategies = List.of(
                OptimisationStrategyEntity.builder()
                        .strategyType(OptimisationStrategyType.SKIP_LOW_RATED)
                        .enabled(true).paramValue(3).build());
        when(optimisationStrategyService.getEnabledStrategies(RunType.VERY_SHORT_TERM))
                .thenReturn(strategies);

        ForecastCommand cmd = new ForecastCommand(RunType.VERY_SHORT_TERM, dates,
                List.of(durham()), haikuStrategy, true);

        executor.execute(cmd);

        // Verify evaluator was called for each target type
        verify(optimisationSkipEvaluator, times(EXPECTED_CALLS_PER_DAY))
                .shouldSkip(eq(strategies), eq("Durham UK"), eq(today), any(TargetType.class));
    }

    @Test
    @DisplayName("execute() skips when OptimisationSkipEvaluator returns true")
    void execute_skipsWhenEvaluatorSaysSkip() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today);

        when(optimisationSkipEvaluator.shouldSkip(any(), anyString(), any(), any()))
                .thenReturn(true);

        ForecastCommand cmd = new ForecastCommand(RunType.VERY_SHORT_TERM, dates,
                List.of(durham()), haikuStrategy, true);

        executor.execute(cmd);

        // forecastService should never be called since evaluator says skip
        verify(forecastService, never())
                .runForecasts(any(), anyDouble(), anyDouble(), any(),
                        any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("execute() captures active strategies on job run")
    void execute_capturesStrategiesOnJobRun() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today);
        when(optimisationStrategyService.serialiseEnabledStrategies(RunType.SHORT_TERM))
                .thenReturn("SKIP_LOW_RATED(3),REQUIRE_PRIOR");

        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, dates,
                List.of(durham()), haikuStrategy, true);

        executor.execute(cmd);

        verify(jobRunService).startRun(
                eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU),
                eq("SKIP_LOW_RATED(3),REQUIRE_PRIOR"));
    }

    @Test
    @DisplayName("Wildlife run does not load optimisation strategies")
    void execute_wildlife_noStrategies() {
        when(commandFactory.resolveEvaluationModel(any())).thenReturn(EvaluationModel.WILDLIFE);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        ForecastCommand cmd = new ForecastCommand(RunType.WEATHER, List.of(today),
                List.of(wildlifeReserve()), noOpStrategy, false);

        executor.execute(cmd);

        verify(optimisationStrategyService, never()).getEnabledStrategies(any());
    }
}
