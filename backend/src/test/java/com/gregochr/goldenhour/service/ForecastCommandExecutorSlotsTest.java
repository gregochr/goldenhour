package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.OptimisationStrategyEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins how {@link ForecastCommandExecutor} treats an explicit slot list ({@link ForecastCommand#slots()}),
 * which is what "Retry failed" hands it: exactly the named (location, date, event) slots become tasks,
 * and sentinel sampling stands down.
 *
 * <p>Two places, two dates, both events is eight slots; the run is asked for two of them. The
 * assertions are literal task keys, because "exactly the failed slots" is the whole claim. A
 * contrast test runs the same places and dates with no slot list, which is what retry-failed built
 * before: all eight.
 */
@ExtendWith(MockitoExtension.class)
class ForecastCommandExecutorSlotsTest {

    /** Fixed so the "today" the executor derives never moves under the test. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 3);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 4);
    private static final long RUN_ID = 1L;

    @Mock private ForecastService forecastService;
    @Mock private LocationService locationService;
    @Mock private JobRunService jobRunService;
    @Mock private SolarService solarService;
    @Mock private ForecastCommandFactory commandFactory;
    @Mock private OptimisationStrategyService optimisationStrategyService;
    @Mock private RunProgressTracker progressTracker;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SentinelSelector sentinelSelector;
    @Mock private AstroConditionsService astroConditionsService;
    @Mock private ForecastStabilityClassifier stabilityClassifier;
    @Mock private OpenMeteoService openMeteoService;
    @Mock private StabilitySnapshotProvider stabilitySnapshotProvider;
    @Mock private EvaluationStrategy haikuStrategy;

    private ForecastCommandExecutor executor;
    private JobRunEntity jobRun;

    /** The (name|date|type) of every slot the triage phase was asked about, in call order per thread. */
    private final List<String> fetched = java.util.Collections.synchronizedList(new ArrayList<>());

    /** The (name|date|type) of every slot sent to Claude. */
    private final List<String> evaluated = java.util.Collections.synchronizedList(new ArrayList<>());

    private static LocationEntity place(long id, String name, RegionEntity region) {
        return LocationEntity.builder()
                .id(id)
                .name(name)
                .lat(54.0 + id)
                .lon(-1.5)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .locationType(Set.of(LocationType.LANDSCAPE))
                .region(region)
                .build();
    }

    private static ForecastSlot slot(String name, LocalDate date, TargetType type) {
        return new ForecastSlot(name, date, type);
    }

    @BeforeEach
    void setUp() {
        executor = new ForecastCommandExecutor(
                forecastService, locationService, jobRunService, solarService,
                commandFactory, Runnable::run,
                optimisationStrategyService, progressTracker, eventPublisher,
                sentinelSelector, astroConditionsService, stabilityClassifier,
                openMeteoService, stabilitySnapshotProvider, CLOCK);
        jobRun = new JobRunEntity();
        jobRun.setId(RUN_ID);
        when(locationService.shouldEvaluateSunrise(any())).thenReturn(true);
        when(locationService.shouldEvaluateSunset(any())).thenReturn(true);
        when(commandFactory.resolveEvaluationModel(any())).thenReturn(EvaluationModel.HAIKU);
        when(optimisationStrategyService.getEnabledStrategies(any())).thenReturn(List.of());
        when(optimisationStrategyService.serialiseEnabledStrategies(any())).thenReturn("");
        Map<String, WeatherExtractionResult> prefetchedWeather = new LinkedHashMap<>();
        when(openMeteoService.prefetchWeatherBatch(anyList(), any())).thenReturn(prefetchedWeather);
        when(openMeteoService.prefetchCloudBatch(anyList(), any())).thenReturn(new CloudPointCache(Map.of()));
    }

    /** Every slot survives triage, so a run that reaches the evaluation phase evaluates all of its tasks. */
    private void stubSurvivingTriage() {
        when(forecastService.fetchWeatherAndTriage(
                any(LocationEntity.class), any(LocalDate.class), any(TargetType.class),
                any(), any(EvaluationModel.class), anyBoolean(), any(JobRunEntity.class),
                any(), any()))
                .thenAnswer(inv -> {
                    LocationEntity loc = inv.getArgument(0);
                    LocalDate date = inv.getArgument(1);
                    TargetType type = inv.getArgument(2);
                    String key = loc.getName() + "|" + date + "|" + type;
                    fetched.add(key);
                    return new ForecastPreEvalResult(false, null, null,
                            loc, date, type, LocalDateTime.of(2026, 10, 3, 9, 0), 90, 1,
                            EvaluationModel.HAIKU, loc.getTideType(), key, null);
                });
    }

    private void stubEvaluation(int rating) {
        when(forecastService.evaluateAndPersist(any(ForecastPreEvalResult.class), any(JobRunEntity.class)))
                .thenAnswer(inv -> {
                    ForecastPreEvalResult task = inv.getArgument(0);
                    evaluated.add(task.taskKey());
                    return ForecastEvaluationEntity.builder().id(9L).rating(rating).build();
                });
    }

    private static OptimisationStrategyEntity sentinelStrategy() {
        OptimisationStrategyEntity entity = new OptimisationStrategyEntity();
        entity.setStrategyType(OptimisationStrategyType.SENTINEL_SAMPLING);
        entity.setEnabled(true);
        entity.setParamValue(2);
        return entity;
    }

    @SuppressWarnings("unchecked")
    private List<String> registeredTaskKeys() {
        ArgumentCaptor<List<String[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(progressTracker).initRun(eq(RUN_ID), captor.capture());
        return captor.getValue().stream().map(t -> t[0]).toList();
    }

    @Test
    @DisplayName("two named slots out of a two-places-by-two-dates-by-two-events product: exactly those two "
            + "are registered, triaged and evaluated")
    void explicitSlots_runExactlyTheNamedSlots() {
        stubSurvivingTriage();
        stubEvaluation(4);
        LocationEntity durham = place(1L, "Durham", null);
        LocationEntity bamburgh = place(2L, "Bamburgh", null);
        ForecastCommand cmd = new ForecastCommand(RunType.VERY_SHORT_TERM, List.of(SATURDAY, SUNDAY),
                List.of(durham, bamburgh), haikuStrategy, true, Set.of(), Set.of(),
                Set.of(slot("Durham", SATURDAY, TargetType.SUNSET),
                        slot("Bamburgh", SUNDAY, TargetType.SUNRISE)));

        executor.execute(cmd, jobRun);

        assertThat(registeredTaskKeys()).containsExactlyInAnyOrder(
                "Durham|2026-10-03|SUNSET", "Bamburgh|2026-10-04|SUNRISE");
        assertThat(fetched).containsExactlyInAnyOrder(
                "Durham|2026-10-03|SUNSET", "Bamburgh|2026-10-04|SUNRISE");
        assertThat(evaluated).containsExactlyInAnyOrder(
                "Durham|2026-10-03|SUNSET", "Bamburgh|2026-10-04|SUNRISE");
    }

    @Test
    @DisplayName("contrast: the same places and dates with no slot list make all eight slots, "
            + "which is what retry-failed used to build")
    void noSlots_runsTheWholeProduct() {
        stubSurvivingTriage();
        stubEvaluation(4);
        LocationEntity durham = place(1L, "Durham", null);
        LocationEntity bamburgh = place(2L, "Bamburgh", null);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(SATURDAY, SUNDAY),
                List.of(durham, bamburgh), haikuStrategy, true);

        executor.execute(cmd, jobRun);

        assertThat(registeredTaskKeys()).containsExactlyInAnyOrder(
                "Durham|2026-10-03|SUNRISE", "Durham|2026-10-03|SUNSET",
                "Durham|2026-10-04|SUNRISE", "Durham|2026-10-04|SUNSET",
                "Bamburgh|2026-10-03|SUNRISE", "Bamburgh|2026-10-03|SUNSET",
                "Bamburgh|2026-10-04|SUNRISE", "Bamburgh|2026-10-04|SUNSET");
        assertThat(evaluated).hasSize(8);
    }

    @Test
    @DisplayName("a null slot set (the canonical constructor) behaves as no slot list: the whole product runs")
    void nullSlots_runsTheWholeProduct() {
        stubSurvivingTriage();
        stubEvaluation(4);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(SATURDAY),
                List.of(place(1L, "Durham", null)), haikuStrategy, true, Set.of(), Set.of(), null);

        executor.execute(cmd, jobRun);

        assertThat(registeredTaskKeys()).containsExactlyInAnyOrder(
                "Durham|2026-10-03|SUNRISE", "Durham|2026-10-03|SUNSET");
    }

    @Test
    @DisplayName("a named slot whose event has since passed stays in the run as SKIPPED and is never "
            + "triaged or evaluated; its sibling slot still runs")
    void explicitSlots_aSlotWhoseEventHasPassed_isSkippedNotEvaluated() {
        stubSurvivingTriage();
        stubEvaluation(4);
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), eq(TODAY)))
                .thenReturn(LocalDateTime.of(2026, 10, 2, 6, 0));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), eq(TODAY)))
                .thenReturn(LocalDateTime.of(2026, 10, 2, 17, 0));
        LocationEntity durham = place(1L, "Durham", null);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(TODAY),
                List.of(durham), haikuStrategy, true, Set.of(), Set.of(),
                Set.of(slot("Durham", TODAY, TargetType.SUNRISE), slot("Durham", TODAY, TargetType.SUNSET)));

        executor.execute(cmd, jobRun);

        assertThat(registeredTaskKeys()).containsExactlyInAnyOrder(
                "Durham|2026-10-02|SUNRISE", "Durham|2026-10-02|SUNSET");
        ArgumentCaptor<ApplicationEvent> events = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(events.capture());
        List<String> skipped = events.getAllValues().stream()
                .filter(LocationTaskEvent.class::isInstance)
                .map(LocationTaskEvent.class::cast)
                .filter(e -> e.getState() == LocationTaskState.SKIPPED)
                .map(LocationTaskEvent::getTaskKey)
                .toList();
        assertThat(skipped).containsExactly("Durham|2026-10-02|SUNRISE");
        assertThat(fetched).containsExactly("Durham|2026-10-02|SUNSET");
        assertThat(evaluated).containsExactly("Durham|2026-10-02|SUNSET");
    }

    @Test
    @DisplayName("sentinel sampling is not applied to an explicit slot list: every named slot is evaluated "
            + "and none is given a canned result")
    void explicitSlots_sentinelSamplingStandsDown() {
        stubSurvivingTriage();
        stubEvaluation(1);
        when(optimisationStrategyService.getEnabledStrategies(any())).thenReturn(List.of(sentinelStrategy()));
        RegionEntity north = RegionEntity.builder().id(1L).name("North").enabled(true).build();
        LocationEntity durham = place(1L, "Durham", north);
        LocationEntity bamburgh = place(2L, "Bamburgh", north);
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(SATURDAY),
                List.of(durham, bamburgh), haikuStrategy, true, Set.of(), Set.of(),
                Set.of(slot("Durham", SATURDAY, TargetType.SUNSET),
                        slot("Bamburgh", SATURDAY, TargetType.SUNSET)));

        executor.execute(cmd, jobRun);

        assertThat(evaluated).containsExactlyInAnyOrder(
                "Durham|2026-10-03|SUNSET", "Bamburgh|2026-10-03|SUNSET");
        verify(sentinelSelector, never()).selectSentinels(anyList());
        verify(forecastService, never()).persistCannedResult(any(ForecastPreEvalResult.class), any(String.class),
                any(JobRunEntity.class));
    }

    @Test
    @DisplayName("contrast: with no slot list the same enabled sentinel strategy samples the region and "
            + "gives the non-sentinel place a canned result instead of a Claude call")
    void noSlots_sentinelSamplingStillApplies() {
        stubSurvivingTriage();
        stubEvaluation(1);
        when(optimisationStrategyService.getEnabledStrategies(any())).thenReturn(List.of(sentinelStrategy()));
        when(forecastService.persistCannedResult(any(ForecastPreEvalResult.class), any(String.class),
                any(JobRunEntity.class)))
                .thenReturn(ForecastEvaluationEntity.builder().id(2L).rating(1).build());
        RegionEntity north = RegionEntity.builder().id(1L).name("North").enabled(true).build();
        LocationEntity durham = place(1L, "Durham", north);
        LocationEntity bamburgh = place(2L, "Bamburgh", north);
        when(sentinelSelector.selectSentinels(anyList())).thenReturn(List.of(durham));
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(SATURDAY),
                List.of(durham, bamburgh), haikuStrategy, true);

        executor.execute(cmd, jobRun);

        assertThat(evaluated).containsExactlyInAnyOrder("Durham|2026-10-03|SUNRISE", "Durham|2026-10-03|SUNSET");
        ArgumentCaptor<ForecastPreEvalResult> canned = ArgumentCaptor.forClass(ForecastPreEvalResult.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(forecastService, times(2)).persistCannedResult(canned.capture(), reason.capture(), eq(jobRun));
        assertThat(canned.getAllValues()).extracting(ForecastPreEvalResult::taskKey).containsExactlyInAnyOrder(
                "Bamburgh|2026-10-03|SUNRISE", "Bamburgh|2026-10-03|SUNSET");
        assertThat(reason.getAllValues()).containsOnly("Region sentinel sampling — all sentinels rated 2 or below");
    }
}
