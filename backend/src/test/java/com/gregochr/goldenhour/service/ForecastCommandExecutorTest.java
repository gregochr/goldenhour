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
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
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
        stubJobRun = new JobRunEntity();
        stubJobRun.setId(1L);
        stubPrefetchedWeather = new java.util.LinkedHashMap<>();
        stubCloudCache = new CloudPointCache(java.util.Map.of());

        // Use synchronous executor
        executor = new ForecastCommandExecutor(
                forecastService, locationService, jobRunService, solarService,
                commandFactory, Runnable::run, optimisationSkipEvaluator,
                optimisationStrategyService, progressTracker, eventPublisher,
                sentinelSelector, astroConditionsService, stabilityClassifier,
                openMeteoService);
    }

    /**
     * Stubs for the "infrastructure" layer shared by all colour execute() tests:
     * job run, prefetch, model resolution, strategy list, and solar-event guards.
     * Does NOT stub {@code findAllEnabled()} — add that inline when the command has null locations.
     * Does NOT stub {@code shouldSkip()} — add that inline when testing skip-evaluator behaviour.
     * Does NOT stub {@code fetchWeatherAndTriage} or {@code evaluateAndPersist} —
     * call {@link #stubDefaultTriage()} when you need the pass-through default, or
     * add your own triage stub when the test controls triage outcomes directly.
     */
    private void stubExecuteDefaults() {
        when(locationService.shouldEvaluateSunrise(any())).thenReturn(true);
        when(locationService.shouldEvaluateSunset(any())).thenReturn(true);
        when(commandFactory.resolveEvaluationModel(any())).thenReturn(EvaluationModel.HAIKU);
        when(optimisationStrategyService.getEnabledStrategies(any())).thenReturn(List.of());
        when(optimisationStrategyService.serialiseEnabledStrategies(any())).thenReturn("");
        when(jobRunService.startRun(any(), any(boolean.class), any(), any()))
                .thenReturn(stubJobRun);
        when(openMeteoService.prefetchWeatherBatch(anyList(), any()))
                .thenReturn(stubPrefetchedWeather);
        when(openMeteoService.prefetchCloudBatch(anyList(), any()))
                .thenReturn(stubCloudCache);
    }

    /**
     * Stubs solar-service calls so {@code shouldSkipEvent()} never drops a slot.
     * Add this to tests that run against today's date and both SUNRISE + SUNSET are active.
     */
    private void stubSolarNotPast() {
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any()))
                .thenReturn(LocalDateTime.MAX);
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any()))
                .thenReturn(LocalDateTime.MAX);
    }

    /**
     * Default pass-through stub for {@code fetchWeatherAndTriage} (returns triaged=false,
     * daysAhead=0, rating placeholder). Call this when the test does not control triage outcomes.
     */
    private void stubDefaultFetch() {
        when(forecastService.fetchWeatherAndTriage(
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
    }

    /**
     * Default stub for {@code evaluateAndPersist} (returns rating 3).
     * Call this in tests that verify evaluation happens but do not assert the specific rating.
     */
    private void stubDefaultEval() {
        when(forecastService.evaluateAndPersist(
                any(ForecastPreEvalResult.class), any(JobRunEntity.class)))
                .thenReturn(ForecastEvaluationEntity.builder().id(1L).rating(3).build());
    }

    /**
     * Minimal stubs for wildlife (NoOp strategy) execute() tests.
     * Wildlife tests only reach {@code runForecasts()}, so colour-pipeline stubs are unused.
     */
    private void stubWildlifeDefaults() {
        when(commandFactory.resolveEvaluationModel(any())).thenReturn(EvaluationModel.WILDLIFE);
        when(jobRunService.startRun(any(), any(boolean.class), any(), any()))
                .thenReturn(stubJobRun);
    }

    @Test
    @DisplayName("execute() calls fetchWeatherAndTriage once per target type per day for colour locations")
    void execute_colourLocations_callsTriageForEachTargetTypePerDay() {
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
        stubDefaultEval();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today, today.plusDays(1), today.plusDays(2));
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, dates, List.of(durham()),
                haikuStrategy, true);

        executor.execute(cmd);

        for (LocalDate date : dates) {
            verify(forecastService)
                    .fetchWeatherAndTriage(
                            org.mockito.ArgumentMatchers.argThat(
                                    (LocationEntity loc) -> loc != null && "Durham UK".equals(loc.getName())),
                            eq(date), eq(TargetType.SUNRISE), eq(Set.of()),
                            eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                            eq(stubPrefetchedWeather), eq(stubCloudCache));
            verify(forecastService)
                    .fetchWeatherAndTriage(
                            org.mockito.ArgumentMatchers.argThat(
                                    (LocationEntity loc) -> loc != null && "Durham UK".equals(loc.getName())),
                            eq(date), eq(TargetType.SUNSET), eq(Set.of()),
                            eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                            eq(stubPrefetchedWeather), eq(stubCloudCache));
        }
    }

    @Test
    @DisplayName("execute() calls evaluateAndPersist for non-triaged tasks")
    void execute_colourLocations_callsEvaluateForNonTriaged() {
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
        stubDefaultEval();
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
        stubExecuteDefaults();
        stubSolarNotPast();
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
                .evaluateAndPersist(any(ForecastPreEvalResult.class), eq(stubJobRun));
    }

    @Test
    @DisplayName("execute() with NoOp strategy calls forecastService with WILDLIFE model")
    void execute_wildlife_callsForecastServiceWithWildlife() {
        stubWildlifeDefaults();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today);

        ForecastCommand cmd = new ForecastCommand(RunType.WEATHER, dates,
                List.of(wildlifeReserve()), noOpStrategy, false);

        executor.execute(cmd);

        verify(forecastService)
                .runForecasts(
                        org.mockito.ArgumentMatchers.argThat(loc -> "Wildlife Reserve".equals(loc.getName())),
                        eq(today), org.mockito.ArgumentMatchers.isNull(),
                        eq(Set.of()), eq(EvaluationModel.WILDLIFE), eq(stubJobRun));
    }

    @Test
    @DisplayName("execute() with NoOp strategy does NOT call for colour locations")
    void execute_wildlife_excludesColourLocations() {
        stubWildlifeDefaults();
        when(locationService.findAllEnabled()).thenReturn(List.of(durham(), wildlifeReserve()));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today);

        ForecastCommand cmd = new ForecastCommand(RunType.WEATHER, dates,
                null, noOpStrategy, false);

        executor.execute(cmd);

        verify(forecastService, never())
                .runForecasts(
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), org.mockito.ArgumentMatchers.isNull(),
                        eq(Set.of()), eq(EvaluationModel.WILDLIFE), eq(stubJobRun));
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
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
        stubDefaultEval();
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
        verify(forecastService)
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNRISE), eq(Set.of()),
                        eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
        verify(forecastService)
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNSET), eq(Set.of()),
                        eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
        verify(forecastService, never())
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> "Whitley Bay".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNRISE), eq(Set.of()),
                        eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
        verify(forecastService, never())
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> "Whitley Bay".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNSET), eq(Set.of()),
                        eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
    }

    @Test
    @DisplayName("execute() with empty excludedLocations processes all locations normally")
    void execute_emptyExcludedLocations_processesAll() {
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
        stubDefaultEval();
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

        ArgumentCaptor<LocationEntity> locCaptor = ArgumentCaptor.forClass(LocationEntity.class);
        ArgumentCaptor<TargetType> typeCaptor = ArgumentCaptor.forClass(TargetType.class);
        verify(forecastService, times(EXPECTED_CALLS_PER_DAY * 2))
                .fetchWeatherAndTriage(
                        locCaptor.capture(), eq(today), typeCaptor.capture(), eq(Set.of()),
                        eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
        assertThat(locCaptor.getAllValues()).extracting(LocationEntity::getName)
                .containsExactlyInAnyOrder("Durham UK", "Durham UK", "Whitley Bay", "Whitley Bay");
        assertThat(typeCaptor.getAllValues())
                .containsExactlyInAnyOrder(
                        TargetType.SUNRISE, TargetType.SUNSET,
                        TargetType.SUNRISE, TargetType.SUNSET);
    }

    // -------------------------------------------------------------------------
    // Solar event timing skip (shouldSkipEvent)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Past sunrise today is dropped before triage; sunset still proceeds")
    void execute_pastSunriseToday_sunriseDropped_sunsetProceeds() {
        stubExecuteDefaults();
        stubDefaultFetch();
        stubDefaultEval();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDateTime twoHoursAgo = LocalDateTime.now(ZoneOffset.UTC).minusHours(2);

        // Sunrise happened 2 h ago — should be silently dropped before triage
        when(solarService.sunriseUtc(eq(durham().getLat()), eq(durham().getLon()), eq(today)))
                .thenReturn(twoHoursAgo);
        // Sunset is far future — should proceed normally
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any()))
                .thenReturn(LocalDateTime.MAX);

        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                List.of(durham()), haikuStrategy, true);
        executor.execute(cmd);

        verify(forecastService, never())
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNRISE), eq(Set.of()),
                        eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
        verify(forecastService)
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNSET), eq(Set.of()),
                        eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
    }

    @Test
    @DisplayName("Future-date slots are never dropped by solar timing — shouldSkipEvent only applies to today")
    void execute_futureDateSlots_neverDroppedBySolarTiming() {
        stubExecuteDefaults();
        stubDefaultFetch();
        stubDefaultEval();
        // shouldSkipEvent short-circuits on targetDate != today without calling solarService
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);

        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(tomorrow),
                List.of(durham()), haikuStrategy, true);
        executor.execute(cmd);

        verify(forecastService)
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(tomorrow), eq(TargetType.SUNRISE), eq(Set.of()),
                        eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
        verify(forecastService)
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(tomorrow), eq(TargetType.SUNSET), eq(Set.of()),
                        eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
    }

    // -------------------------------------------------------------------------
    // Excluded slots
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Excluded SUNRISE slot is dropped before triage; SUNSET still proceeds")
    void execute_excludedSunriseSlot_onlySunsetPassedToTriage() {
        stubExecuteDefaults();
        stubDefaultFetch();
        stubDefaultEval();
        // SUNRISE is excluded before shouldSkipEvent — only sunsetUtc is checked
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any())).thenReturn(LocalDateTime.MAX);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String excludedSlot = today + "|SUNRISE";

        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                List.of(durham()), haikuStrategy, true, Set.of(excludedSlot), Set.of());
        executor.execute(cmd);

        verify(forecastService, never())
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNRISE), eq(Set.of()),
                        eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
        verify(forecastService)
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNSET), eq(Set.of()),
                        eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
    }

    @Test
    @DisplayName("Excluded SUNSET slot is dropped before triage; SUNRISE still proceeds")
    void execute_excludedSunsetSlot_onlySunrisePassedToTriage() {
        stubExecuteDefaults();
        stubDefaultFetch();
        stubDefaultEval();
        // SUNSET is excluded before shouldSkipEvent — only sunriseUtc is checked
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any())).thenReturn(LocalDateTime.MAX);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String excludedSlot = today + "|SUNSET";

        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                List.of(durham()), haikuStrategy, true, Set.of(excludedSlot), Set.of());
        executor.execute(cmd);

        verify(forecastService)
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNRISE), eq(Set.of()),
                        eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
        verify(forecastService, never())
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNSET), eq(Set.of()),
                        eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
    }

    // -------------------------------------------------------------------------
    // Triage phase outcomes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("All tasks triaged: persistCannedResult and evaluateAndPersist never called")
    void execute_allTasksTriaged_noPersistAndNoEvaluate() {
        stubExecuteDefaults();
        stubSolarNotPast();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        // Both SUNRISE and SUNSET come back triaged
        when(forecastService.fetchWeatherAndTriage(
                org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                eq(today), eq(TargetType.SUNRISE), eq(Set.of()),
                eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                eq(stubPrefetchedWeather), eq(stubCloudCache)))
                .thenAnswer(inv -> new ForecastPreEvalResult(true, "Low cloud 88%", null,
                        durham(), today, TargetType.SUNRISE, LocalDateTime.now(), 90, 0,
                        EvaluationModel.HAIKU, Set.of(), "Durham UK|" + today + "|SUNRISE", null));
        when(forecastService.fetchWeatherAndTriage(
                org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                eq(today), eq(TargetType.SUNSET), eq(Set.of()),
                eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                eq(stubPrefetchedWeather), eq(stubCloudCache)))
                .thenAnswer(inv -> new ForecastPreEvalResult(true, "Precipitation 3mm", null,
                        durham(), today, TargetType.SUNSET, LocalDateTime.now(), 90, 0,
                        EvaluationModel.HAIKU, Set.of(), "Durham UK|" + today + "|SUNSET", null));

        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                List.of(durham()), haikuStrategy, false);
        executor.execute(cmd);

        verify(forecastService, never())
                .persistCannedResult(any(ForecastPreEvalResult.class),
                        org.mockito.ArgumentMatchers.anyString(), eq(stubJobRun));
        verify(forecastService, never())
                .evaluateAndPersist(any(ForecastPreEvalResult.class), eq(stubJobRun));
    }

    @Test
    @DisplayName("Mixed triage: survivor proceeds to evaluateAndPersist; triaged location does not")
    void execute_mixedTriage_survivorEvaluated_triagedLocationNot() {
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
        stubDefaultEval();
        LocationEntity whitley = LocationEntity.builder()
                .id(3L).name("Whitley Bay").lat(55.04).lon(-1.44)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .build();
        when(locationService.findAllEnabled()).thenReturn(List.of(durham(), whitley));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        // Durham triaged for both event types
        when(forecastService.fetchWeatherAndTriage(
                org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                eq(today), eq(TargetType.SUNRISE), eq(Set.of()),
                eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                eq(stubPrefetchedWeather), eq(stubCloudCache)))
                .thenAnswer(inv -> new ForecastPreEvalResult(true, "Low cloud 90%", null,
                        durham(), today, TargetType.SUNRISE, LocalDateTime.now(), 90, 0,
                        EvaluationModel.HAIKU, Set.of(), "Durham UK|" + today + "|SUNRISE", null));
        when(forecastService.fetchWeatherAndTriage(
                org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                eq(today), eq(TargetType.SUNSET), eq(Set.of()),
                eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                eq(stubPrefetchedWeather), eq(stubCloudCache)))
                .thenAnswer(inv -> new ForecastPreEvalResult(true, "Low cloud 90%", null,
                        durham(), today, TargetType.SUNSET, LocalDateTime.now(), 90, 0,
                        EvaluationModel.HAIKU, Set.of(), "Durham UK|" + today + "|SUNSET", null));
        // Whitley Bay survives triage (default stub returns triaged=false)

        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                null, haikuStrategy, false);
        executor.execute(cmd);

        // Whitley Bay evaluated for both event types
        ArgumentCaptor<ForecastPreEvalResult> captor = ArgumentCaptor.forClass(ForecastPreEvalResult.class);
        verify(forecastService, times(EXPECTED_CALLS_PER_DAY))
                .evaluateAndPersist(captor.capture(), eq(stubJobRun));
        assertThat(captor.getAllValues())
                .allMatch(pe -> "Whitley Bay".equals(pe.location().getName()));
    }

    @Test
    @DisplayName("Optimisation skip produces no persistCannedResult call — no phantom DB write")
    void execute_scheduledOptimisationSkip_persistCannedResultNeverCalled() {
        stubExecuteDefaults();
        stubSolarNotPast();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(optimisationSkipEvaluator.shouldSkip(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                eq(today), eq(TargetType.SUNRISE)))
                .thenReturn(true);
        when(optimisationSkipEvaluator.shouldSkip(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                eq(today), eq(TargetType.SUNSET)))
                .thenReturn(true);

        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                List.of(durham()), haikuStrategy, false); // scheduled
        executor.execute(cmd);

        verify(forecastService, never())
                .persistCannedResult(any(ForecastPreEvalResult.class),
                        org.mockito.ArgumentMatchers.anyString(), eq(stubJobRun));
        verify(forecastService, never())
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNRISE), eq(Set.of()),
                        eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
        verify(forecastService, never())
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNSET), eq(Set.of()),
                        eq(EvaluationModel.HAIKU), eq(false), eq(stubJobRun),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
    }

    // -------------------------------------------------------------------------
    // Optimisation skip delegation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scheduled run delegates skip decision to OptimisationSkipEvaluator for each event type")
    void execute_scheduledRun_delegatesToSkipEvaluatorForEachEventType() {
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
        stubDefaultEval();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today);

        var strategies = List.of(
                OptimisationStrategyEntity.builder()
                        .strategyType(OptimisationStrategyType.SKIP_LOW_RATED)
                        .enabled(true).paramValue(3).build());
        when(optimisationStrategyService.getEnabledStrategies(RunType.VERY_SHORT_TERM))
                .thenReturn(strategies);

        ForecastCommand cmd = new ForecastCommand(RunType.VERY_SHORT_TERM, dates,
                List.of(durham()), haikuStrategy, false); // scheduled — not manual

        executor.execute(cmd);

        verify(optimisationSkipEvaluator)
                .shouldSkip(eq(strategies),
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNRISE));
        verify(optimisationSkipEvaluator)
                .shouldSkip(eq(strategies),
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNSET));
    }

    @Test
    @DisplayName("Scheduled run skips evaluation when OptimisationSkipEvaluator returns true")
    void execute_scheduledRun_skipsWhenEvaluatorSaysSkip() {
        stubExecuteDefaults();
        stubSolarNotPast();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today);

        when(optimisationSkipEvaluator.shouldSkip(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                eq(today), org.mockito.ArgumentMatchers.any(TargetType.class)))
                .thenReturn(true);

        ForecastCommand cmd = new ForecastCommand(RunType.VERY_SHORT_TERM, dates,
                List.of(durham()), haikuStrategy, false); // scheduled — not manual

        executor.execute(cmd);

        verify(forecastService, never())
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNRISE), any(),
                        eq(EvaluationModel.HAIKU), anyBoolean(), any(),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
        verify(forecastService, never())
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNSET), any(),
                        eq(EvaluationModel.HAIKU), anyBoolean(), any(),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
        verify(forecastService, never()).evaluateAndPersist(any(ForecastPreEvalResult.class), any(JobRunEntity.class));
    }

    @Test
    @DisplayName("Manual run bypasses OptimisationSkipEvaluator and always proceeds to triage")
    void execute_manualRun_bypassesOptimisationSkipEvaluator() {
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
        stubDefaultEval();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today);

        var strategies = List.of(
                OptimisationStrategyEntity.builder()
                        .strategyType(OptimisationStrategyType.SKIP_LOW_RATED)
                        .enabled(true).paramValue(3).build());
        when(optimisationStrategyService.getEnabledStrategies(RunType.SHORT_TERM))
                .thenReturn(strategies);

        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, dates,
                List.of(durham()), haikuStrategy, true); // manual

        executor.execute(cmd);

        verify(optimisationSkipEvaluator, never())
                .shouldSkip(eq(strategies),
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNRISE));
        verify(optimisationSkipEvaluator, never())
                .shouldSkip(eq(strategies),
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNSET));
        verify(forecastService)
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNRISE), any(),
                        eq(EvaluationModel.HAIKU), anyBoolean(), any(),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
        verify(forecastService)
                .fetchWeatherAndTriage(
                        org.mockito.ArgumentMatchers.argThat(loc -> loc != null && "Durham UK".equals(loc.getName())),
                        eq(today), eq(TargetType.SUNSET), any(),
                        eq(EvaluationModel.HAIKU), anyBoolean(), any(),
                        eq(stubPrefetchedWeather), eq(stubCloudCache));
    }

    @Test
    @DisplayName("execute() captures active strategies on job run")
    void execute_capturesStrategiesOnJobRun() {
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
        stubDefaultEval();
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
        stubWildlifeDefaults();
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
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
        stubDefaultEval();
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
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
        stubDefaultEval();
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
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
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
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
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
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
        stubDefaultEval();
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
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
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
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
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
        stubExecuteDefaults();
        stubSolarNotPast();
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

        when(stabilityClassifier.classify(any(), anyDouble(), anyDouble(), any()))
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
        stubExecuteDefaults();
        stubSolarNotPast();
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

        when(stabilityClassifier.classify(any(), anyDouble(), anyDouble(), any()))
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
        stubExecuteDefaults();
        stubSolarNotPast();
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
        stubExecuteDefaults();
        stubSolarNotPast();
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

        when(stabilityClassifier.classify(any(), anyDouble(), anyDouble(), any()))
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
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
        stubDefaultEval();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today, today.plusDays(1));
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, dates,
                List.of(durham()), haikuStrategy, true);

        executor.execute(cmd);

        // 4 tasks (1 location × 2 dates × 2 target types) but only 1 unique coordinate
        org.mockito.ArgumentCaptor<java.util.List<double[]>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(openMeteoService).prefetchWeatherBatch(captor.capture(), eq(stubJobRun));
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("prefetchWeather deduplicates two locations with identical lat/lon")
    void prefetchWeather_sameLatLon_differentNames_deduplicates() {
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
        stubDefaultEval();
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
        verify(openMeteoService).prefetchWeatherBatch(captor.capture(), eq(stubJobRun));
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("triagePhase passes exact prefetched map object to fetchWeatherAndTriage")
    void triagePhase_passesExactPrefetchedMap() {
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
        stubDefaultEval();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(today),
                List.of(durham()), haikuStrategy, true);

        executor.execute(cmd);

        verify(forecastService, times(EXPECTED_CALLS_PER_DAY))
                .fetchWeatherAndTriage(any(LocationEntity.class), any(LocalDate.class),
                        any(TargetType.class), any(), any(EvaluationModel.class),
                        anyBoolean(), any(), eq(stubPrefetchedWeather), eq(stubCloudCache));
    }

    // -------------------------------------------------------------------------
    // Stability snapshot cache
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Stability snapshot: scheduled run populates getLatestStabilitySummary()")
    void stabilitySnapshot_populatedAfterScheduledRun() {
        stubExecuteDefaults();
        stubSolarNotPast();
        LocationEntity loc = durhamWithGrid();
        OpenMeteoForecastResponse resp = new OpenMeteoForecastResponse();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(forecastService.fetchWeatherAndTriage(
                any(), eq(today), any(), any(), any(), anyBoolean(), any(),
                eq(stubPrefetchedWeather), eq(stubCloudCache)))
                .thenReturn(new ForecastPreEvalResult(false, null, null,
                        loc, today, TargetType.SUNSET, LocalDateTime.now(), 270,
                        0, EvaluationModel.HAIKU, loc.getTideType(),
                        loc.getName() + "|" + today + "|SUNSET", resp));

        when(stabilityClassifier.classify(any(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc.gridCellKey(), 54.75, -1.625,
                        ForecastStability.SETTLED, "High pressure dominant (1025 hPa)", 3));

        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM,
                List.of(today), List.of(loc), haikuStrategy, false);
        executor.execute(cmd);

        StabilitySummaryResponse summary = executor.getLatestStabilitySummary();
        assertThat(summary).isNotNull();
        assertThat(summary.totalGridCells()).isEqualTo(1);
        assertThat(summary.cells()).hasSize(1);
        StabilitySummaryResponse.GridCellDetail cell = summary.cells().get(0);
        assertThat(cell.gridCellKey()).isEqualTo("54.7500,-1.6250");
        assertThat(cell.stability()).isEqualTo(ForecastStability.SETTLED);
        assertThat(cell.evaluationWindowDays()).isEqualTo(3);
        assertThat(cell.locationNames()).containsExactly("Durham UK");
    }

    @Test
    @DisplayName("Stability snapshot: manual run does not update getLatestStabilitySummary()")
    void stabilitySnapshot_notUpdatedByManualRun() {
        stubExecuteDefaults();
        stubSolarNotPast();
        stubDefaultFetch();
        stubDefaultEval();
        LocationEntity loc = durhamWithGrid();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM,
                List.of(today), List.of(loc), haikuStrategy, true);
        executor.execute(cmd);

        assertThat(executor.getLatestStabilitySummary()).isNull();
    }
}
