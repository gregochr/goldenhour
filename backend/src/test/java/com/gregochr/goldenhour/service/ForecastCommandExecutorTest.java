package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.OptimisationStrategyEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.GridCellStabilityResult;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.NoOpEvaluationStrategy;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
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
    private RunProgressTracker progressTracker;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Mock
    private SentinelSelector sentinelSelector;

    @Mock
    private AstroConditionsService astroConditionsService;

    @Mock
    private ForecastStabilityClassifier stabilityClassifier;

    @Mock
    private OpenMeteoService openMeteoService;

    @Mock
    private EvaluationStrategy haikuStrategy;

    private NoOpEvaluationStrategy noOpStrategy;

    private ForecastCommandExecutor executor;
    private Map<String, WeatherExtractionResult> stubPrefetchedWeather;
    private CloudPointCache stubCloudCache;

    private JobRunEntity stubJobRun;

    private static final int EXPECTED_CALLS_PER_DAY = 2; // SUNRISE + SUNSET

    private static LocationEntity durham() {
        return LocationEntity.builder()
                .id(1L)
                .name("Durham UK")
                .lat(54.7753)
                .lon(-1.5849)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .build();
    }

    private static LocationEntity wildlifeReserve() {
        return LocationEntity.builder()
                .id(2L)
                .name("Wildlife Reserve")
                .lat(53.5)
                .lon(-1.2)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .locationType(java.util.Set.of(LocationType.WILDLIFE))
                .build();
    }

    @BeforeEach
    void setUp() {
        noOpStrategy = new NoOpEvaluationStrategy();
        lenient().when(locationService.findAllEnabled()).thenReturn(List.of(durham()));
        lenient().when(locationService.shouldEvaluateSunrise(any())).thenReturn(true);
        lenient().when(locationService.shouldEvaluateSunset(any())).thenReturn(true);
        // Return MAX so shouldSkipEvent() never skips
        lenient().when(solarService.sunriseUtc(anyDouble(), anyDouble(), any()))
                .thenReturn(LocalDateTime.MAX);
        lenient().when(solarService.sunsetUtc(anyDouble(), anyDouble(), any()))
                .thenReturn(LocalDateTime.MAX);
        lenient().when(commandFactory.resolveEvaluationModel(any())).thenReturn(EvaluationModel.HAIKU);
        lenient().when(optimisationSkipEvaluator.shouldSkip(any(), any(LocationEntity.class), any(), any()))
                .thenReturn(false);
        lenient().when(optimisationStrategyService.getEnabledStrategies(any())).thenReturn(List.of());
        lenient().when(optimisationStrategyService.serialiseEnabledStrategies(any())).thenReturn("");

        // Return a stub job run entity from startRun()
        stubJobRun = new JobRunEntity();
        stubJobRun.setId(1L);
        lenient().when(jobRunService.startRun(any(), any(boolean.class), any(), any()))
                .thenReturn(stubJobRun);

        // Default: prefetchWeatherBatch returns a shared map that we can verify was passed through
        stubPrefetchedWeather = new java.util.LinkedHashMap<>();
        lenient().when(openMeteoService.prefetchWeatherBatch(anyList(), any()))
                .thenReturn(stubPrefetchedWeather);

        // Default: prefetchCloudBatch returns a shared cache
        stubCloudCache = new CloudPointCache(java.util.Map.of());
        lenient().when(openMeteoService.prefetchCloudBatch(anyList(), any()))
                .thenReturn(stubCloudCache);

        // Default: fetchWeatherAndTriage returns non-triaged result
        lenient().when(forecastService.fetchWeatherAndTriage(
                any(LocationEntity.class), any(LocalDate.class), any(TargetType.class),
                any(), any(EvaluationModel.class), anyBoolean(), any(JobRunEntity.class),
                eq(stubPrefetchedWeather), eq(stubCloudCache)))
                .thenAnswer(invocation -> {
                    LocationEntity loc = invocation.getArgument(0);
                    LocalDate date = invocation.getArgument(1);
                    TargetType type = invocation.getArgument(2);
                    EvaluationModel model = invocation.getArgument(4);
                    return new ForecastPreEvalResult(false, null, null,
                            loc, date, type, LocalDateTime.now(), 90, 0,
                            model, loc.getTideType(),
                            loc.getName() + "|" + date + "|" + type, null);
                });

        // Default: evaluateAndPersist returns a stub entity
        lenient().when(forecastService.evaluateAndPersist(
                any(ForecastPreEvalResult.class), any(JobRunEntity.class)))
                .thenReturn(ForecastEvaluationEntity.builder().id(1L).rating(3).build());

        // Use synchronous executor
        executor = new ForecastCommandExecutor(
                forecastService, locationService, jobRunService, solarService,
                commandFactory, Runnable::run, optimisationSkipEvaluator,
                optimisationStrategyService, progressTracker, eventPublisher,
                sentinelSelector, astroConditionsService, stabilityClassifier,
                openMeteoService);
    }

    @Test
    @DisplayName("execute() calls fetchWeatherAndTriage once per target type per day for colour locations")
    void execute_colourLocations_callsTriageForEachTargetTypePerDay() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today, today.plusDays(1), today.plusDays(2));
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, dates, List.of(durham()),
                haikuStrategy, true);

        executor.execute(cmd);

        int expectedCalls = dates.size() * EXPECTED_CALLS_PER_DAY;
        verify(forecastService, times(expectedCalls))
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(
                                loc -> "Durham UK".equals(loc.getName())),
                        any(LocalDate.class), any(TargetType.class), any(),
                        eq(EvaluationModel.HAIKU), anyBoolean(), any(),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
        // Both target types received
        verify(forecastService, times(dates.size()))
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(
                                loc -> "Durham UK".equals(loc.getName())),
                        any(LocalDate.class), eq(TargetType.SUNRISE), any(),
                        eq(EvaluationModel.HAIKU), anyBoolean(), any(),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
        verify(forecastService, times(dates.size()))
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(
                                loc -> "Durham UK".equals(loc.getName())),
                        any(LocalDate.class), eq(TargetType.SUNSET), any(),
                        eq(EvaluationModel.HAIKU), anyBoolean(), any(),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
    }

    @Test
    @DisplayName("execute() calls evaluateAndPersist for non-triaged tasks")
    void execute_colourLocations_callsEvaluateForNonTriaged() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, dates, List.of(durham()),
                haikuStrategy, true);

        executor.execute(cmd);

        ArgumentCaptor<ForecastPreEvalResult> preEvalCaptor =
                ArgumentCaptor.forClass(ForecastPreEvalResult.class);
        verify(forecastService, times(EXPECTED_CALLS_PER_DAY))
                .evaluateAndPersist(preEvalCaptor.capture(), eq(stubJobRun));
        assertThat(preEvalCaptor.getAllValues())
                .allMatch(pe -> "Durham UK".equals(pe.location().getName()));
    }

    @Test
    @DisplayName("execute() skips evaluation for triaged tasks")
    void execute_triagedTasks_skipEvaluation() {
        // All tasks triaged
        when(forecastService.fetchWeatherAndTriage(
                any(LocationEntity.class), any(LocalDate.class), any(TargetType.class),
                any(), any(EvaluationModel.class), anyBoolean(), any(JobRunEntity.class),
                eq(stubPrefetchedWeather), eq(stubCloudCache)))
                .thenAnswer(invocation -> {
                    LocationEntity loc = invocation.getArgument(0);
                    LocalDate date = invocation.getArgument(1);
                    TargetType type = invocation.getArgument(2);
                    return new ForecastPreEvalResult(true, "Low cloud cover 85%", null,
                            loc, date, type, LocalDateTime.now(), 90, 0,
                            EvaluationModel.HAIKU, loc.getTideType(),
                            loc.getName() + "|" + date + "|" + type, null);
                });

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                List.of(durham()), haikuStrategy, true);

        executor.execute(cmd);

        // No evaluations should happen
        verify(forecastService, never())
                .evaluateAndPersist(any(), any());
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
                .runForecasts(any(LocationEntity.class), any(LocalDate.class),
                        org.mockito.ArgumentMatchers.isNull(),
                        any(), eq(EvaluationModel.WILDLIFE), any());
    }

    @Test
    @DisplayName("execute() with NoOp strategy does NOT call for colour locations")
    void execute_wildlife_excludesColourLocations() {
        when(commandFactory.resolveEvaluationModel(any())).thenReturn(EvaluationModel.WILDLIFE);
        when(locationService.findAllEnabled()).thenReturn(List.of(durham(), wildlifeReserve()));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today);

        ForecastCommand cmd = new ForecastCommand(RunType.WEATHER, dates,
                null, noOpStrategy, false);

        executor.execute(cmd);

        verify(forecastService, never())
                .runForecasts(eq(durham()), any(LocalDate.class), any(),
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
    @DisplayName("hasColourTypes() returns true for WATERFALL location")
    void hasColourTypes_waterfallLocation_returnsTrue() {
        LocationEntity waterfall = LocationEntity.builder()
                .name("High Force")
                .lat(54.6)
                .lon(-2.1)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .locationType(java.util.Set.of(LocationType.WATERFALL))
                .build();
        assertThat(executor.hasColourTypes(waterfall)).isTrue();
    }

    @Test
    @DisplayName("isPureWildlife() returns false for WATERFALL location")
    void isPureWildlife_waterfallLocation_returnsFalse() {
        LocationEntity waterfall = LocationEntity.builder()
                .name("High Force")
                .lat(54.6)
                .lon(-2.1)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .locationType(java.util.Set.of(LocationType.WATERFALL))
                .build();
        assertThat(executor.isPureWildlife(waterfall)).isFalse();
    }

    @Test
    @DisplayName("isPureWildlife() returns false for mixed LANDSCAPE+WILDLIFE location")
    void isPureWildlife_mixedLocation_returnsFalse() {
        LocationEntity mixed = LocationEntity.builder()
                .name("Coastal Park")
                .lat(54.0)
                .lon(-1.5)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .locationType(java.util.Set.of(LocationType.LANDSCAPE, LocationType.WILDLIFE))
                .build();
        assertThat(executor.isPureWildlife(mixed)).isFalse();
    }

    // -------------------------------------------------------------------------
    // Drive-time excluded locations
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("execute() skips locations in excludedLocations set")
    void execute_excludedLocations_skipsThoseLocations() {
        LocationEntity whitley = LocationEntity.builder()
                .id(3L)
                .name("Whitley Bay")
                .lat(55.04)
                .lon(-1.44)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .build();
        when(locationService.findAllEnabled()).thenReturn(List.of(durham(), whitley));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        // Exclude Whitley Bay — only Durham should be processed
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                null, haikuStrategy, true, Set.of(), Set.of("Whitley Bay"));

        executor.execute(cmd);

        // fetchWeatherAndTriage should be called only for Durham (2 target types), not Whitley
        verify(forecastService, times(EXPECTED_CALLS_PER_DAY))
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> "Durham UK".equals(loc.getName())),
                        any(LocalDate.class), any(TargetType.class), any(),
                        any(EvaluationModel.class), anyBoolean(), any(),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
        verify(forecastService, never())
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> "Whitley Bay".equals(loc.getName())),
                        any(), any(), any(), any(), anyBoolean(), any(),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
    }

    @Test
    @DisplayName("execute() with empty excludedLocations processes all locations normally")
    void execute_emptyExcludedLocations_processesAll() {
        LocationEntity whitley = LocationEntity.builder()
                .id(3L)
                .name("Whitley Bay")
                .lat(55.04)
                .lon(-1.44)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .build();
        when(locationService.findAllEnabled()).thenReturn(List.of(durham(), whitley));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                null, haikuStrategy, true, Set.of(), Set.of());

        executor.execute(cmd);

        verify(forecastService, times(EXPECTED_CALLS_PER_DAY * 2))
                .fetchWeatherAndTriage(any(LocationEntity.class), any(LocalDate.class),
                        any(TargetType.class), any(), any(EvaluationModel.class), anyBoolean(), any(),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
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
                .shouldSkip(eq(strategies), any(LocationEntity.class), eq(today), any(TargetType.class));
    }

    @Test
    @DisplayName("execute() skips when OptimisationSkipEvaluator returns true")
    void execute_skipsWhenEvaluatorSaysSkip() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today);

        when(optimisationSkipEvaluator.shouldSkip(any(), any(LocationEntity.class), any(), any()))
                .thenReturn(true);

        ForecastCommand cmd = new ForecastCommand(RunType.VERY_SHORT_TERM, dates,
                List.of(durham()), haikuStrategy, true);

        executor.execute(cmd);

        // forecastService should never be called since evaluator says skip
        verify(forecastService, never())
                .fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                        anyBoolean(), any(), eq(stubPrefetchedWeather), eq(stubCloudCache));
        verify(forecastService, never())
                .evaluateAndPersist(any(), any());
    }

    @Test
    @DisplayName("execute() captures active strategies on job run")
    void execute_capturesStrategiesOnJobRun() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today);
        when(optimisationStrategyService.serialiseEnabledStrategies(RunType.SHORT_TERM))
                .thenReturn("SKIP_LOW_RATED(3),FORCE_IMMINENT");

        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, dates,
                List.of(durham()), haikuStrategy, true);

        executor.execute(cmd);

        verify(jobRunService).startRun(
                eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU),
                eq("SKIP_LOW_RATED(3),FORCE_IMMINENT"));
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

    // -------------------------------------------------------------------------
    // Tide alignment optimisation strategy
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("execute() passes tideAlignmentEnabled=true when TIDE_ALIGNMENT strategy is active")
    void execute_tideAlignmentStrategyEnabled_passesTrue() {
        OptimisationStrategyEntity tideAlignmentStrategy = OptimisationStrategyEntity.builder()
                .strategyType(OptimisationStrategyType.TIDE_ALIGNMENT)
                .enabled(true)
                .build();
        when(optimisationStrategyService.getEnabledStrategies(any()))
                .thenReturn(List.of(tideAlignmentStrategy));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                List.of(durham()), haikuStrategy, true);

        executor.execute(cmd);

        verify(forecastService, org.mockito.Mockito.atLeastOnce())
                .fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                        eq(true), any(),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
    }

    @Test
    @DisplayName("execute() passes tideAlignmentEnabled=false when TIDE_ALIGNMENT strategy is inactive")
    void execute_tideAlignmentStrategyDisabled_passesFalse() {
        when(optimisationStrategyService.getEnabledStrategies(any())).thenReturn(List.of());

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                List.of(durham()), haikuStrategy, true);

        executor.execute(cmd);

        verify(forecastService, org.mockito.Mockito.atLeastOnce())
                .fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                        eq(false), any(),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
    }

    // -------------------------------------------------------------------------
    // Sentinel sampling
    // -------------------------------------------------------------------------

    private OptimisationStrategyEntity sentinelStrategy(int threshold) {
        OptimisationStrategyEntity entity = new OptimisationStrategyEntity();
        entity.setStrategyType(OptimisationStrategyType.SENTINEL_SAMPLING);
        entity.setEnabled(true);
        entity.setParamValue(threshold);
        return entity;
    }

    @Test
    @DisplayName("Sentinel early-stop skips remainder when all sentinels rate ≤ threshold")
    void execute_sentinelEarlyStop_skipsRemainder() {
        RegionEntity northRegion = RegionEntity.builder().id(1L).name("North").enabled(true).build();
        LocationEntity sentinel = LocationEntity.builder()
                .id(10L).name("Sentinel Loc").lat(55.0).lon(-1.0)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .region(northRegion).build();
        LocationEntity nonSentinel = LocationEntity.builder()
                .id(11L).name("Non-Sentinel Loc").lat(54.0).lon(-1.5)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .region(northRegion).build();

        // Enable SENTINEL_SAMPLING strategy with threshold 2
        when(optimisationStrategyService.getEnabledStrategies(any()))
                .thenReturn(List.of(sentinelStrategy(2)));

        when(sentinelSelector.selectSentinels(any())).thenReturn(List.of(sentinel));

        // Sentinel evaluation returns rating 2 (≤ threshold)
        when(forecastService.evaluateAndPersist(any(ForecastPreEvalResult.class), any()))
                .thenReturn(ForecastEvaluationEntity.builder().id(1L).rating(2).build());

        when(forecastService.persistCannedResult(any(), any(String.class), any()))
                .thenReturn(ForecastEvaluationEntity.builder().id(2L).rating(1).build());

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                List.of(sentinel, nonSentinel), haikuStrategy, true);

        executor.execute(cmd);

        ArgumentCaptor<ForecastPreEvalResult> cannedCaptor =
                ArgumentCaptor.forClass(ForecastPreEvalResult.class);
        verify(forecastService, times(EXPECTED_CALLS_PER_DAY))
                .persistCannedResult(
                        cannedCaptor.capture(), any(String.class), eq(stubJobRun));
        assertThat(cannedCaptor.getAllValues())
                .allMatch(pe -> "Non-Sentinel Loc".equals(pe.location().getName()));
    }

    @Test
    @DisplayName("Sentinel passes when any sentinel rates above threshold, remainder evaluated")
    void execute_sentinelPasses_remainderEvaluated() {
        RegionEntity northRegion = RegionEntity.builder().id(1L).name("North").enabled(true).build();
        LocationEntity sentinel = LocationEntity.builder()
                .id(10L).name("Sentinel Loc").lat(55.0).lon(-1.0)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .region(northRegion).build();
        LocationEntity nonSentinel = LocationEntity.builder()
                .id(11L).name("Non-Sentinel Loc").lat(54.0).lon(-1.5)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .region(northRegion).build();

        when(optimisationStrategyService.getEnabledStrategies(any()))
                .thenReturn(List.of(sentinelStrategy(2)));
        when(sentinelSelector.selectSentinels(any())).thenReturn(List.of(sentinel));

        // Sentinel returns rating 4 (> threshold)
        when(forecastService.evaluateAndPersist(any(ForecastPreEvalResult.class), any()))
                .thenReturn(ForecastEvaluationEntity.builder().id(1L).rating(4).build());

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                List.of(sentinel, nonSentinel), haikuStrategy, true);

        executor.execute(cmd);

        verify(forecastService, never()).persistCannedResult(any(), any(String.class), any());
    }

    @Test
    @DisplayName("Null-region locations bypass sentinel logic and go directly to full eval")
    void execute_nullRegion_bypassesSentinel() {
        when(optimisationStrategyService.getEnabledStrategies(any()))
                .thenReturn(List.of(sentinelStrategy(2)));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                List.of(durham()), haikuStrategy, true);

        executor.execute(cmd);

        verify(sentinelSelector, never()).selectSentinels(any());
        verify(forecastService, times(EXPECTED_CALLS_PER_DAY))
                .evaluateAndPersist(
                        org.mockito.ArgumentMatchers.argThat(
                                pe -> "Durham UK".equals(pe.location().getName())),
                        eq(stubJobRun));
    }

    @Test
    @DisplayName("Sentinel disabled — all survivors go directly to full evaluation, no sentinel calls")
    void execute_sentinelDisabled_allGoToFullEval() {
        RegionEntity northRegion = RegionEntity.builder().id(1L).name("North").enabled(true).build();
        LocationEntity loc1 = LocationEntity.builder()
                .id(10L).name("Loc A").lat(55.0).lon(-1.0)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .region(northRegion).build();
        LocationEntity loc2 = LocationEntity.builder()
                .id(11L).name("Loc B").lat(54.0).lon(-1.5)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .region(northRegion).build();

        // No SENTINEL_SAMPLING in enabled strategies
        when(optimisationStrategyService.getEnabledStrategies(any())).thenReturn(List.of());

        when(forecastService.evaluateAndPersist(any(ForecastPreEvalResult.class), any()))
                .thenReturn(ForecastEvaluationEntity.builder().id(1L).rating(3).build());

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                List.of(loc1, loc2), haikuStrategy, true);

        executor.execute(cmd);

        // Sentinel selector should never be called
        verify(sentinelSelector, never()).selectSentinels(any());
        // All 4 tasks (2 locations × 2 target types) should go to evaluateAndPersist
        ArgumentCaptor<ForecastPreEvalResult> fullEvalCaptor =
                ArgumentCaptor.forClass(ForecastPreEvalResult.class);
        verify(forecastService, times(4))
                .evaluateAndPersist(fullEvalCaptor.capture(), eq(stubJobRun));
        assertThat(fullEvalCaptor.getAllValues())
                .extracting(pe -> pe.location().getName())
                .containsExactlyInAnyOrder("Loc A", "Loc A", "Loc B", "Loc B");
        verify(forecastService, never())
                .persistCannedResult(any(), any(String.class), any());
    }

    @Test
    @DisplayName("Sentinel with custom threshold 3 — skips when all sentinels rate ≤3")
    void execute_sentinelCustomThreshold_usesParamValue() {
        RegionEntity northRegion = RegionEntity.builder().id(1L).name("North").enabled(true).build();
        LocationEntity sentinel = LocationEntity.builder()
                .id(10L).name("Sentinel Loc").lat(55.0).lon(-1.0)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .region(northRegion).build();
        LocationEntity nonSentinel = LocationEntity.builder()
                .id(11L).name("Non-Sentinel Loc").lat(54.0).lon(-1.5)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .region(northRegion).build();

        // Custom threshold 3 — sentinels rating 3 should trigger skip
        when(optimisationStrategyService.getEnabledStrategies(any()))
                .thenReturn(List.of(sentinelStrategy(3)));
        when(sentinelSelector.selectSentinels(any())).thenReturn(List.of(sentinel));

        // Sentinel returns rating 3 (≤ threshold 3)
        when(forecastService.evaluateAndPersist(any(ForecastPreEvalResult.class), any()))
                .thenReturn(ForecastEvaluationEntity.builder().id(1L).rating(3).build());
        when(forecastService.persistCannedResult(any(), any(String.class), any()))
                .thenReturn(ForecastEvaluationEntity.builder().id(2L).rating(1).build());

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                List.of(sentinel, nonSentinel), haikuStrategy, true);

        executor.execute(cmd);

        // Non-sentinel tasks should be skipped (rating 3 ≤ threshold 3)
        ArgumentCaptor<ForecastPreEvalResult> customCaptor =
                ArgumentCaptor.forClass(ForecastPreEvalResult.class);
        verify(forecastService, times(EXPECTED_CALLS_PER_DAY))
                .persistCannedResult(
                        customCaptor.capture(), any(String.class), eq(stubJobRun));
        assertThat(customCaptor.getAllValues())
                .allMatch(pe -> "Non-Sentinel Loc".equals(pe.location().getName()));
    }

    // ── Stability filter tests ──

    private static LocationEntity durhamWithGrid() {
        LocationEntity loc = durham();
        loc.setGridLat(54.7500);
        loc.setGridLng(-1.6250);
        return loc;
    }

    @Test
    @DisplayName("Stability filter: UNSETTLED skips T+2 tasks, keeps T+0")
    void stabilityFilter_unsettled_skipsT2() {
        LocationEntity loc = durhamWithGrid();

        OpenMeteoForecastResponse resp = new OpenMeteoForecastResponse();
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(),
                eq(stubPrefetchedWeather), eq(stubCloudCache)))
                .thenAnswer(inv -> {
                    LocalDate date = inv.getArgument(1);
                    int daysAhead = (int) java.time.temporal.ChronoUnit.DAYS.between(
                            LocalDate.now(ZoneOffset.UTC), date);
                    return new ForecastPreEvalResult(false, null, null,
                            loc, date, inv.getArgument(2), LocalDateTime.now(), 90,
                            daysAhead, EvaluationModel.HAIKU, loc.getTideType(),
                            loc.getName() + "|" + date + "|" + inv.getArgument(2), resp);
                });

        lenient().when(stabilityClassifier.classify(any(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc.gridCellKey(), 54.75, -1.625,
                        ForecastStability.UNSETTLED, "Deep low", 0));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM,
                List.of(today, today.plusDays(1), today.plusDays(2)),
                List.of(loc), haikuStrategy, false);

        executor.execute(cmd);

        // Only T+0 tasks should reach Claude (2 = sunrise + sunset)
        ArgumentCaptor<ForecastPreEvalResult> evalCaptor =
                ArgumentCaptor.forClass(ForecastPreEvalResult.class);
        verify(forecastService, times(EXPECTED_CALLS_PER_DAY))
                .evaluateAndPersist(evalCaptor.capture(), eq(stubJobRun));
        assertThat(evalCaptor.getAllValues())
                .extracting(ForecastPreEvalResult::date)
                .containsOnly(today);
    }

    @Test
    @DisplayName("Stability filter: SETTLED allows all tasks through")
    void stabilityFilter_settled_allowsAll() {
        LocationEntity loc = durhamWithGrid();

        OpenMeteoForecastResponse resp = new OpenMeteoForecastResponse();
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(),
                eq(stubPrefetchedWeather), eq(stubCloudCache)))
                .thenAnswer(inv -> {
                    LocalDate date = inv.getArgument(1);
                    int daysAhead = (int) java.time.temporal.ChronoUnit.DAYS.between(
                            LocalDate.now(ZoneOffset.UTC), date);
                    return new ForecastPreEvalResult(false, null, null,
                            loc, date, inv.getArgument(2), LocalDateTime.now(), 90,
                            daysAhead, EvaluationModel.HAIKU, loc.getTideType(),
                            loc.getName() + "|" + date + "|" + inv.getArgument(2), resp);
                });

        lenient().when(stabilityClassifier.classify(any(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc.gridCellKey(), 54.75, -1.625,
                        ForecastStability.SETTLED, "High pressure", 3));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM,
                List.of(today, today.plusDays(1), today.plusDays(2)),
                List.of(loc), haikuStrategy, false);

        executor.execute(cmd);

        // All 3 days × 2 types = 6 Claude calls
        ArgumentCaptor<ForecastPreEvalResult> settledCaptor =
                ArgumentCaptor.forClass(ForecastPreEvalResult.class);
        verify(forecastService, times(3 * EXPECTED_CALLS_PER_DAY))
                .evaluateAndPersist(settledCaptor.capture(), eq(stubJobRun));
        assertThat(settledCaptor.getAllValues())
                .extracting(ForecastPreEvalResult::date)
                .containsAll(List.of(today, today.plusDays(1), today.plusDays(2)));
    }

    @Test
    @DisplayName("Stability filter: location without grid cell defaults to T+1 window")
    void stabilityFilter_noGridCell_defaultsToT1() {
        LocationEntity loc = durham(); // no gridLat/gridLng

        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(),
                eq(stubPrefetchedWeather), eq(stubCloudCache)))
                .thenAnswer(inv -> {
                    LocalDate date = inv.getArgument(1);
                    int daysAhead = (int) java.time.temporal.ChronoUnit.DAYS.between(
                            LocalDate.now(ZoneOffset.UTC), date);
                    return new ForecastPreEvalResult(false, null, null,
                            loc, date, inv.getArgument(2), LocalDateTime.now(), 90,
                            daysAhead, EvaluationModel.HAIKU, loc.getTideType(),
                            loc.getName() + "|" + date + "|" + inv.getArgument(2), null);
                });

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM,
                List.of(today, today.plusDays(1), today.plusDays(2)),
                List.of(loc), haikuStrategy, false);

        executor.execute(cmd);

        // T+0 and T+1 pass (4 tasks), T+2 filtered (2 tasks skipped)
        ArgumentCaptor<ForecastPreEvalResult> noGridCaptor =
                ArgumentCaptor.forClass(ForecastPreEvalResult.class);
        verify(forecastService, times(2 * EXPECTED_CALLS_PER_DAY))
                .evaluateAndPersist(noGridCaptor.capture(), eq(stubJobRun));
        assertThat(noGridCaptor.getAllValues())
                .extracting(ForecastPreEvalResult::date)
                .containsOnly(today, today.plusDays(1));
    }

    @Test
    @DisplayName("Stability filter: TRANSITIONAL allows T+0 and T+1, skips T+2")
    void stabilityFilter_transitional_allowsT0T1() {
        LocationEntity loc = durhamWithGrid();

        OpenMeteoForecastResponse resp = new OpenMeteoForecastResponse();
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(),
                eq(stubPrefetchedWeather), eq(stubCloudCache)))
                .thenAnswer(inv -> {
                    LocalDate date = inv.getArgument(1);
                    int daysAhead = (int) java.time.temporal.ChronoUnit.DAYS.between(
                            LocalDate.now(ZoneOffset.UTC), date);
                    return new ForecastPreEvalResult(false, null, null,
                            loc, date, inv.getArgument(2), LocalDateTime.now(), 90,
                            daysAhead, EvaluationModel.HAIKU, loc.getTideType(),
                            loc.getName() + "|" + date + "|" + inv.getArgument(2), resp);
                });

        lenient().when(stabilityClassifier.classify(any(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc.gridCellKey(), 54.75, -1.625,
                        ForecastStability.TRANSITIONAL, "Mixed signals", 1));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM,
                List.of(today, today.plusDays(1), today.plusDays(2)),
                List.of(loc), haikuStrategy, false);

        executor.execute(cmd);

        // T+0 and T+1 pass (4 tasks), T+2 filtered
        ArgumentCaptor<ForecastPreEvalResult> transCaptor =
                ArgumentCaptor.forClass(ForecastPreEvalResult.class);
        LocalDate todayTrans = LocalDate.now(ZoneOffset.UTC);
        verify(forecastService, times(2 * EXPECTED_CALLS_PER_DAY))
                .evaluateAndPersist(transCaptor.capture(), eq(stubJobRun));
        assertThat(transCaptor.getAllValues())
                .extracting(ForecastPreEvalResult::date)
                .containsOnly(todayTrans, todayTrans.plusDays(1));
    }

    // -------------------------------------------------------------------------
    // Batch prefetch deduplication and data flow
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("prefetchWeather deduplicates same location across multiple dates")
    void prefetchWeather_deduplicatesSameLocation() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today, today.plusDays(1));
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, dates,
                List.of(durham()), haikuStrategy, true);

        executor.execute(cmd);

        // 4 tasks (1 location × 2 dates × 2 target types) but only 1 unique coordinate
        org.mockito.ArgumentCaptor<java.util.List<double[]>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(openMeteoService).prefetchWeatherBatch(captor.capture(), any());
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("prefetchWeather deduplicates two locations with identical lat/lon")
    void prefetchWeather_sameLatLon_differentNames_deduplicates() {
        LocationEntity durham2 = LocationEntity.builder()
                .id(5L).name("Durham Castle")
                .lat(54.7753).lon(-1.5849)
                .solarEventType(new HashSet<>(
                        Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .build();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                List.of(durham(), durham2), haikuStrategy, true);

        executor.execute(cmd);

        org.mockito.ArgumentCaptor<java.util.List<double[]>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(openMeteoService).prefetchWeatherBatch(captor.capture(), any());
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("triagePhase passes exact prefetched map object to fetchWeatherAndTriage")
    void triagePhase_passesExactPrefetchedMap() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                List.of(durham()), haikuStrategy, true);

        executor.execute(cmd);

        verify(forecastService, times(EXPECTED_CALLS_PER_DAY))
                .fetchWeatherAndTriage(any(LocationEntity.class), any(LocalDate.class),
                        any(TargetType.class), any(), any(EvaluationModel.class),
                        anyBoolean(), any(), eq(stubPrefetchedWeather), eq(stubCloudCache));
    }
}
