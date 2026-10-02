package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.exception.EvaluationFailedException;
import com.gregochr.goldenhour.exception.WeatherDataFetchException;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A hand-started run always reports completion: whatever escapes the pipeline, the admin panel is
 * sent {@code run-complete} exactly once and the {@code job_run} is closed.
 *
 * <p>Runs the real {@link RunProgressTracker} behind the executor, with a recording SSE emitter
 * subscribed before the run starts (the way the panel subscribes right after the 202), and a real
 * event path from the executor's {@link LocationTaskEvent}s into the tracker, so what is asserted
 * is what the admin would have seen.
 */
@ExtendWith(MockitoExtension.class)
class ForecastCommandExecutorCompletionTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate DAY = LocalDate.parse("2026-10-03");
    private static final List<LocalDate> DATES = List.of(DAY);
    private static final String DURHAM_SUNSET = "Durham UK|2026-10-03|SUNSET";
    private static final String WHITBY_SUNSET = "Whitby|2026-10-03|SUNSET";
    private static final String GENERIC = "The run stopped unexpectedly. See the server log.";
    private static final String OPEN_METEO = "Weather data (Open-Meteo) could not be fetched; nothing was updated.";
    private static final ObjectMapper JSON = new ObjectMapper();

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
    private OptimisationStrategyService optimisationStrategyService;
    @Mock
    private SentinelSelector sentinelSelector;
    @Mock
    private AstroConditionsService astroConditionsService;
    @Mock
    private ForecastStabilityClassifier stabilityClassifier;
    @Mock
    private OpenMeteoService openMeteoService;
    @Mock
    private StabilitySnapshotProvider stabilitySnapshotProvider;
    @Mock
    private EvaluationStrategy haikuStrategy;
    @Mock
    private DynamicSchedulerService dynamicSchedulerService;
    @Mock
    private ScheduledExecutorService graceScheduler;

    /** Records what a subscriber would receive as {@code name|payload}. */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> events = new ArrayList<>();

        RecordingEmitter() {
            super(0L);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            StringBuilder text = new StringBuilder();
            builder.build().forEach(part -> text.append(part.getData()));
            events.add(text.toString().replace("event:", "").replace("\ndata:", "|").replace("\n\n", ""));
            super.send(builder);
        }
    }

    private RunProgressTracker tracker;
    private RecordingEmitter panel;
    private final List<LocationTaskEvent> published = new ArrayList<>();
    private JobRunEntity jobRun;
    private LocationEntity durham;
    private LocationEntity whitby;

    @BeforeEach
    void setUp() {
        tracker = new RunProgressTracker(dynamicSchedulerService, graceScheduler, 5_000L) {
            @Override
            SseEmitter newEmitter() {
                return new RecordingEmitter();
            }
        };
        jobRun = new JobRunEntity();
        jobRun.setId(1L);
        RegionEntity north = RegionEntity.builder().id(1L).name("North").enabled(true).build();
        durham = location(1L, "Durham UK", north);
        whitby = location(2L, "Whitby", north);
        panel = (RecordingEmitter) tracker.subscribe(1L); // the panel subscribes before the run registers
    }

    private static LocationEntity location(long id, String name, RegionEntity region) {
        return LocationEntity.builder()
                .id(id).name(name).lat(54.0 + id).lon(-1.5)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .region(region)
                .build();
    }

    private ForecastCommandExecutor executor(Executor forecastExecutor) {
        return new ForecastCommandExecutor(forecastService, locationService, jobRunService, solarService,
                commandFactory, forecastExecutor, optimisationStrategyService, tracker, event -> {
                    if (event instanceof LocationTaskEvent taskEvent) {
                        published.add(taskEvent);
                        tracker.onTaskEvent(taskEvent);
                    }
                }, sentinelSelector, astroConditionsService, stabilityClassifier, openMeteoService,
                stabilitySnapshotProvider, CLOCK);
    }

    private ForecastCommandExecutor executor() {
        return executor(Runnable::run);
    }

    private void stubModelAndStrategies(List<OptimisationStrategyEntity> strategies) {
        when(commandFactory.resolveEvaluationModel(any())).thenReturn(EvaluationModel.HAIKU);
        when(optimisationStrategyService.getEnabledStrategies(any())).thenReturn(strategies);
        when(optimisationStrategyService.serialiseEnabledStrategies(any())).thenReturn("");
    }

    private void stubEvaluable() {
        when(locationService.shouldEvaluateSunrise(any())).thenReturn(true);
        when(locationService.shouldEvaluateSunset(any())).thenReturn(true);
    }

    /** Both locations, both sunrise slots excluded (so two tasks are SKIPPED and two are live SUNSETs). */
    private ForecastCommand twoLiveSunsets() {
        return new ForecastCommand(RunType.SHORT_TERM, DATES, List.of(durham, whitby), haikuStrategy, true,
                Set.of("2026-10-03|SUNRISE"));
    }

    private static OptimisationStrategyEntity sentinelStrategy() {
        OptimisationStrategyEntity entity = new OptimisationStrategyEntity();
        entity.setStrategyType(OptimisationStrategyType.SENTINEL_SAMPLING);
        entity.setEnabled(true);
        entity.setParamValue(2);
        return entity;
    }

    private void publish(LocationEntity loc, TargetType type, LocationTaskState state,
            String errorMessage, String failedStep) {
        LocationTaskEvent event = new LocationTaskEvent(this, 1L, loc.getName() + "|" + DAY + "|" + type,
                loc.getName(), DAY.toString(), type.name(), state, errorMessage, failedStep);
        published.add(event);
        tracker.onTaskEvent(event);
    }

    private static ForecastPreEvalResult preEval(LocationEntity loc, LocalDate date, TargetType type,
            boolean triaged) {
        return new ForecastPreEvalResult(triaged, triaged ? "Low cloud" : null, null, loc, date, type,
                LocalDateTime.parse("2026-10-03T17:30:00"), 200, 1, EvaluationModel.HAIKU, loc.getTideType(),
                loc.getName() + "|" + date + "|" + type, null);
    }

    /** Triage that lets every task through, leaving it in FETCHING_CLOUD as the real service would. */
    private void stubTriageSurvives() {
        when(forecastService.fetchWeatherAndTriage(any(LocationEntity.class), any(LocalDate.class),
                any(TargetType.class), any(), any(EvaluationModel.class), anyBoolean(), eq(jobRun),
                any(), any(CloudPointCache.class)))
                .thenAnswer(inv -> {
                    LocationEntity loc = inv.getArgument(0);
                    TargetType type = inv.getArgument(2);
                    publish(loc, type, LocationTaskState.FETCHING_CLOUD, null, null);
                    return preEval(loc, inv.getArgument(1), type, false);
                });
    }

    private List<JsonNode> runCompletes() {
        return panel.events.stream().filter(e -> e.startsWith("run-complete|"))
                .map(e -> {
                    try {
                        return JSON.readTree(e.substring("run-complete|".length()));
                    } catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                }).toList();
    }

    private JsonNode theOnlyRunComplete() {
        assertThat(runCompletes()).as("run-complete events seen by the panel").hasSize(1);
        return runCompletes().getFirst();
    }

    private List<LocationTaskEvent> failedEvents() {
        return published.stream().filter(e -> e.getState() == LocationTaskState.FAILED).toList();
    }

    // -------------------------------------------------------------------------
    // Prefetch degrades instead of aborting
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a failed weather prefetch does not abort the run: every live task fails individually, the "
            + "run completes once with the Open-Meteo reason, and the job_run is closed")
    void prefetchThrows_runCompletesWithEveryLiveTaskFailed() {
        stubModelAndStrategies(List.of());
        stubEvaluable();
        when(openMeteoService.prefetchWeatherBatch(anyList(), eq(jobRun)))
                .thenThrow(new ResourceAccessException("I/O error on GET request for open-meteo"));
        when(forecastService.fetchWeatherAndTriage(any(LocationEntity.class), any(LocalDate.class),
                any(TargetType.class), any(), any(EvaluationModel.class), anyBoolean(), eq(jobRun),
                argThat((Map<String, WeatherExtractionResult> m) -> m != null && m.isEmpty()),
                argThat((CloudPointCache c) -> c != null)))
                .thenAnswer(inv -> {
                    LocationEntity loc = inv.getArgument(0);
                    TargetType type = inv.getArgument(2);
                    publish(loc, type, LocationTaskState.FETCHING_WEATHER, null, null);
                    String msg = "Weather data fetch failed for " + loc.getName() + " " + type
                            + ": Weather data could not be fetched (Open-Meteo unavailable).";
                    publish(loc, type, LocationTaskState.FAILED, msg, "FETCHING_WEATHER");
                    throw new WeatherDataFetchException(msg, loc.getName(), type.name(), null);
                });

        List<ForecastEvaluationEntity> results = executor().execute(twoLiveSunsets(), jobRun);

        assertThat(results).isEmpty();
        assertThat(failedEvents()).extracting(LocationTaskEvent::getTaskKey)
                .containsExactlyInAnyOrder(DURHAM_SUNSET, WHITBY_SUNSET);
        assertThat(failedEvents()).allSatisfy(e -> {
            assertThat(e.getFailedStep()).isEqualTo("FETCHING_WEATHER");
            assertThat(e.getErrorMessage()).endsWith("Weather data could not be fetched (Open-Meteo unavailable).");
        });
        JsonNode complete = theOnlyRunComplete();
        assertThat(complete.get("status").asText()).isEqualTo("FAILED");
        assertThat(complete.get("reason").asText()).isEqualTo(OPEN_METEO);
        assertThat(complete.get("total").asInt()).isEqualTo(4);
        assertThat(complete.get("failed").asInt()).isEqualTo(2);
        assertThat(complete.get("skipped").asInt()).isEqualTo(2);
        assertThat(complete.get("completed").asInt()).isZero();
        verify(jobRunService).completeRun(jobRun, 0, 0, DATES);
        verify(openMeteoService, never()).prefetchCloudBatch(anyList(), any());
    }

    // -------------------------------------------------------------------------
    // The guard: every escape route completes the run
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("failing before the tracker knows the run (findAllEnabled throws): run-complete FAILED with the "
            + "generic reason and zero tasks, job_run closed, the raw message never published")
    void failureBeforeInitRun_panelToldRunFailed() {
        stubModelAndStrategies(List.of());
        when(locationService.findAllEnabled()).thenThrow(new IllegalStateException("db down password=hunter2"));
        ForecastCommand allEnabled = new ForecastCommand(RunType.SHORT_TERM, DATES, null, haikuStrategy, true);

        List<ForecastEvaluationEntity> results = executor().execute(allEnabled, jobRun);

        assertThat(results).isEmpty();
        JsonNode complete = theOnlyRunComplete();
        assertThat(complete.get("status").asText()).isEqualTo("FAILED");
        assertThat(complete.get("reason").asText()).isEqualTo(GENERIC);
        assertThat(complete.get("total").asInt()).isZero();
        assertThat(complete.get("failed").asInt()).isZero();
        assertThat(panel.events).noneMatch(e -> e.contains("hunter2"));
        assertThat(panel.events).extracting(e -> e.substring(0, e.indexOf('|')))
                .doesNotContain("run-expired", "task-update");
        assertThat(published).isEmpty();
        verify(jobRunService).completeRun(jobRun, 0, 0);
    }

    @Test
    @DisplayName("sentinel selection throwing: the unfinished task is failed from the step it was stuck in, "
            + "the triaged and skipped ones are left alone, and the run completes PARTIAL once")
    void sentinelSelectionThrows_unfinishedTaskFailedFromItsStuckState() {
        stubModelAndStrategies(List.of(sentinelStrategy()));
        stubEvaluable();
        when(openMeteoService.prefetchWeatherBatch(anyList(), eq(jobRun))).thenReturn(Map.of());
        when(openMeteoService.prefetchCloudBatch(anyList(), eq(jobRun))).thenReturn(new CloudPointCache(Map.of()));
        when(forecastService.fetchWeatherAndTriage(any(LocationEntity.class), any(LocalDate.class),
                any(TargetType.class), any(), any(EvaluationModel.class), anyBoolean(), eq(jobRun),
                any(), any(CloudPointCache.class)))
                .thenAnswer(inv -> {
                    LocationEntity loc = inv.getArgument(0);
                    TargetType type = inv.getArgument(2);
                    boolean triaged = loc == whitby;
                    publish(loc, type, triaged ? LocationTaskState.TRIAGED : LocationTaskState.FETCHING_CLOUD,
                            null, null);
                    return preEval(loc, inv.getArgument(1), type, triaged);
                });
        when(sentinelSelector.selectSentinels(List.of(durham)))
                .thenThrow(new IllegalStateException("selector blew up"));

        executor().execute(twoLiveSunsets(), jobRun);

        assertThat(failedEvents()).singleElement().satisfies(e -> {
            assertThat(e.getTaskKey()).isEqualTo(DURHAM_SUNSET);
            assertThat(e.getFailedStep()).isEqualTo("FETCHING_CLOUD");
            assertThat(e.getErrorMessage())
                    .isEqualTo("Did not finish: the run stopped before this place was evaluated.");
        });
        JsonNode complete = theOnlyRunComplete();
        assertThat(complete.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(complete.get("reason").asText()).isEqualTo(GENERIC);
        assertThat(complete.get("failed").asInt()).isEqualTo(1);
        assertThat(complete.get("triaged").asInt()).isEqualTo(1);
        assertThat(complete.get("skipped").asInt()).isEqualTo(2);
        verify(jobRunService).completeRun(jobRun, 0, 1);
    }

    @Test
    @DisplayName("an executor that rejects the work (future creation fails): tasks still PENDING are failed "
            + "from PENDING and the run completes FAILED once")
    void futureCreationFails_pendingTasksFailedFromPending() {
        stubModelAndStrategies(List.of());
        stubEvaluable();
        when(openMeteoService.prefetchWeatherBatch(anyList(), eq(jobRun))).thenReturn(Map.of());
        when(openMeteoService.prefetchCloudBatch(anyList(), eq(jobRun))).thenReturn(new CloudPointCache(Map.of()));
        Executor rejecting = command -> {
            throw new RejectedExecutionException("pool shut down");
        };

        executor(rejecting).execute(twoLiveSunsets(), jobRun);

        assertThat(failedEvents()).extracting(LocationTaskEvent::getTaskKey)
                .containsExactlyInAnyOrder(DURHAM_SUNSET, WHITBY_SUNSET);
        assertThat(failedEvents()).extracting(LocationTaskEvent::getFailedStep).containsOnly("PENDING");
        JsonNode complete = theOnlyRunComplete();
        assertThat(complete.get("status").asText()).isEqualTo("FAILED");
        assertThat(complete.get("reason").asText()).isEqualTo(GENERIC);
        assertThat(complete.get("failed").asInt()).isEqualTo(2);
        assertThat(complete.get("skipped").asInt()).isEqualTo(2);
        verify(jobRunService).completeRun(jobRun, 0, 2);
    }

    @Test
    @DisplayName("an Open-Meteo style exception reaching the guard gets the Open-Meteo reason")
    void weatherExceptionReachesGuard_getsOpenMeteoReason() {
        stubModelAndStrategies(List.of());
        when(locationService.shouldEvaluateSunrise(any()))
                .thenThrow(new WeatherDataFetchException("x", "Durham UK", "SUNRISE", null));

        executor().execute(twoLiveSunsets(), jobRun);

        assertThat(theOnlyRunComplete().get("reason").asText()).isEqualTo(OPEN_METEO);
    }

    @Test
    @DisplayName("jobRunService.completeRun throwing on the normal path: the tracker still completes, once, "
            + "with the status the tasks earned; the guard retries closing the job_run")
    void jobRunCloseThrowsOnNormalPath_trackerStillCompletedOnce() {
        stubModelAndStrategies(List.of());
        stubEvaluable();
        when(openMeteoService.prefetchWeatherBatch(anyList(), eq(jobRun))).thenReturn(Map.of());
        when(openMeteoService.prefetchCloudBatch(anyList(), eq(jobRun))).thenReturn(new CloudPointCache(Map.of()));
        stubTriageSurvives();
        when(forecastService.evaluateAndPersist(any(ForecastPreEvalResult.class), eq(jobRun)))
                .thenAnswer(inv -> {
                    ForecastPreEvalResult pre = inv.getArgument(0);
                    publish(pre.location(), pre.targetType(), LocationTaskState.COMPLETE, null, null);
                    return ForecastEvaluationEntity.builder().id(1L).rating(3).build();
                });
        doThrow(new IllegalStateException("db blip"))
                .when(jobRunService).completeRun(jobRun, 2, 0, DATES);

        executor().execute(twoLiveSunsets(), jobRun);

        JsonNode complete = theOnlyRunComplete();
        assertThat(complete.get("status").asText()).isEqualTo("COMPLETE");
        assertThat(complete.get("completed").asInt()).isEqualTo(2);
        assertThat(complete.get("reason").isNull()).isTrue();
        verify(jobRunService).completeRun(jobRun, 2, 0, DATES);
        verify(jobRunService).completeRun(jobRun, 2, 0); // the guard's retry, with the tracker's counts
        assertThat(failedEvents()).isEmpty();
    }

    @Test
    @DisplayName("an Error is rethrown after the same best-effort completion")
    void errorIsRethrownAfterCompletion() {
        stubModelAndStrategies(List.of());
        StackOverflowError error = new StackOverflowError("boom");
        when(locationService.shouldEvaluateSunrise(any())).thenThrow(error);

        assertThatThrownBy(() -> executor().execute(twoLiveSunsets(), jobRun)).isSameAs(error);

        JsonNode complete = theOnlyRunComplete();
        assertThat(complete.get("status").asText()).isEqualTo("FAILED");
        assertThat(complete.get("reason").asText()).isEqualTo(GENERIC);
        verify(jobRunService).completeRun(jobRun, 0, 0);
    }

    @Test
    @DisplayName("when no job run exists yet there is nothing to complete: the exception propagates")
    void noJobRunYet_exceptionPropagatesAndNothingIsCompleted() {
        stubModelAndStrategies(List.of());
        when(jobRunService.startRun(any(), any(boolean.class), any(), any()))
                .thenThrow(new IllegalStateException("cannot start"));

        assertThatThrownBy(() -> executor().execute(twoLiveSunsets(), null))
                .isInstanceOf(IllegalStateException.class).hasMessage("cannot start");

        assertThat(runCompletes()).isEmpty();
        verify(jobRunService, never()).completeRun(any(JobRunEntity.class), any(int.class), any(int.class));
    }

    @Test
    @DisplayName("a job run the executor created itself is completed too when the pipeline throws")
    void internallyCreatedJobRun_completedWhenPipelineThrows() {
        stubModelAndStrategies(List.of());
        when(jobRunService.startRun(any(), any(boolean.class), any(), any())).thenReturn(jobRun);
        when(locationService.shouldEvaluateSunrise(any())).thenThrow(new IllegalStateException("boom"));

        executor().execute(twoLiveSunsets(), null);

        assertThat(theOnlyRunComplete().get("status").asText()).isEqualTo("FAILED");
        verify(jobRunService).completeRun(jobRun, 0, 0);
    }

    // -------------------------------------------------------------------------
    // Normal completion: no task is left in progress, and completion is idempotent
    // -------------------------------------------------------------------------

    private void stubEvaluationLeavingDurhamEvaluating(boolean whitbyAlsoFails) {
        stubModelAndStrategies(List.of());
        stubEvaluable();
        when(openMeteoService.prefetchWeatherBatch(anyList(), eq(jobRun))).thenReturn(Map.of());
        when(openMeteoService.prefetchCloudBatch(anyList(), eq(jobRun))).thenReturn(new CloudPointCache(Map.of()));
        stubTriageSurvives();
        when(forecastService.evaluateAndPersist(any(ForecastPreEvalResult.class), eq(jobRun)))
                .thenAnswer(inv -> {
                    ForecastPreEvalResult pre = inv.getArgument(0);
                    publish(pre.location(), pre.targetType(), LocationTaskState.EVALUATING, null, null);
                    if (pre.location() == durham || whitbyAlsoFails) {
                        // What evaluateAndPersist does on an errored evaluation: EVALUATING, then throws,
                        // publishing no FAILED.
                        throw new EvaluationFailedException("anthropic_401", "invalid x-api-key",
                                pre.location().getName(), pre.targetType(), pre.date());
                    }
                    publish(pre.location(), pre.targetType(), LocationTaskState.COMPLETE, null, null);
                    return ForecastEvaluationEntity.builder().id(1L).rating(3).build();
                });
    }

    @Test
    @DisplayName("a task left EVALUATING on an otherwise normal run ends FAILED with the evaluation reason, and "
            + "the run reports PARTIAL, never RUNNING")
    void taskLeftEvaluating_failedWithEvaluationReason() {
        stubEvaluationLeavingDurhamEvaluating(false);

        executor().execute(twoLiveSunsets(), jobRun);

        assertThat(failedEvents()).singleElement().satisfies(e -> {
            assertThat(e.getTaskKey()).isEqualTo(DURHAM_SUNSET);
            assertThat(e.getFailedStep()).isEqualTo("EVALUATING");
            assertThat(e.getErrorMessage()).isEqualTo("Evaluation failed (see server log).");
        });
        JsonNode complete = theOnlyRunComplete();
        assertThat(complete.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(complete.get("completed").asInt()).isEqualTo(1);
        assertThat(complete.get("failed").asInt()).isEqualTo(1);
        assertThat(complete.get("reason").isNull()).isTrue();
        verify(jobRunService).completeRun(jobRun, 1, 1, DATES);
    }

    @Test
    @DisplayName("when every evaluation fails the run reports FAILED, never RUNNING, and Retry has places to retry")
    void everyEvaluationFails_runReportsFailed() {
        stubEvaluationLeavingDurhamEvaluating(true);

        executor().execute(twoLiveSunsets(), jobRun);

        assertThat(failedEvents()).extracting(LocationTaskEvent::getTaskKey)
                .containsExactlyInAnyOrder(DURHAM_SUNSET, WHITBY_SUNSET);
        JsonNode complete = theOnlyRunComplete();
        assertThat(complete.get("status").asText()).isEqualTo("FAILED");
        assertThat(complete.get("failed").asInt()).isEqualTo(2);
        assertThat(complete.get("skipped").asInt()).isEqualTo(2);
        assertThat(complete.get("failedTasks")).hasSize(2);
        verify(jobRunService).completeRun(jobRun, 0, 2, DATES);
    }

    @Test
    @DisplayName("a clean run emits run-complete exactly once, closes the job_run exactly once, and the guard "
            + "does nothing")
    void cleanRun_completesOnceAndTheGuardIsInert() {
        stubModelAndStrategies(List.of());
        stubEvaluable();
        when(openMeteoService.prefetchWeatherBatch(anyList(), eq(jobRun))).thenReturn(Map.of());
        when(openMeteoService.prefetchCloudBatch(anyList(), eq(jobRun))).thenReturn(new CloudPointCache(Map.of()));
        stubTriageSurvives();
        when(forecastService.evaluateAndPersist(any(ForecastPreEvalResult.class), eq(jobRun)))
                .thenAnswer(inv -> {
                    ForecastPreEvalResult pre = inv.getArgument(0);
                    publish(pre.location(), pre.targetType(), LocationTaskState.COMPLETE, null, null);
                    return ForecastEvaluationEntity.builder().id(1L).rating(3).build();
                });
        doNothing().when(jobRunService).completeRun(jobRun, 2, 0, DATES);

        executor().execute(twoLiveSunsets(), jobRun);

        JsonNode complete = theOnlyRunComplete();
        assertThat(complete.get("status").asText()).isEqualTo("COMPLETE");
        assertThat(complete.get("reason").isNull()).isTrue();
        assertThat(complete.get("failed").asInt()).isZero();
        assertThat(failedEvents()).isEmpty();
        verify(jobRunService, times(1)).completeRun(jobRun, 2, 0, DATES);
        verify(jobRunService, never()).completeRun(any(JobRunEntity.class), any(int.class), any(int.class));
    }
}
