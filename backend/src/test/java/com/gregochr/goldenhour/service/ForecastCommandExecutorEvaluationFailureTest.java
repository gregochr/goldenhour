package com.gregochr.goldenhour.service;

import com.anthropic.errors.AnthropicServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.config.ResilienceConfig;
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
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;
import com.gregochr.goldenhour.service.evaluation.EvaluationResult;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import com.gregochr.goldenhour.service.evaluation.SlotAtmosphereWriter;
import com.gregochr.goldenhour.service.notification.NotificationDispatcher;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a hand-started run tells the admin when Claude evaluations fail, end to end: the REAL
 * {@link ForecastService} (so its failure publication, its stop check and its bulkhead-position
 * semantics are the ones under test) under the real {@link ForecastCommandExecutor}, a real
 * {@link RunProgressTracker} with a recording SSE subscriber, and only the Claude engine, the
 * repository and the weather leaves mocked.
 *
 * <p>The executor runs sequentially ({@code Runnable::run}) unless a test says otherwise, so the
 * number of Claude calls in a stopped run is exact: one failing call, then every remaining place
 * is not attempted.
 */
@ExtendWith(MockitoExtension.class)
class ForecastCommandExecutorEvaluationFailureTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate DAY = LocalDate.parse("2026-10-03");
    private static final List<LocalDate> DATES = List.of(DAY);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RAW_SECRET = "401 invalid x-api-key sk-ant-api03-HUNTER2 jdbc:postgresql://secret/db";

    private static final String KEY_REJECTED = "Claude rejected the API key.";
    private static final String NOT_ATTEMPTED = "Not attempted: the run stopped because Claude rejected the API key.";
    private static final String RUN_STOPPED =
            "Claude rejected the API key. The run was stopped; no further places were attempted.";
    private static final String FALLBACK = "Evaluation failed (see server log).";

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
    @Mock
    private ForecastDataAugmentor augmentor;
    @Mock
    private EvaluationService legacyEvaluationService;
    @Mock
    private com.gregochr.goldenhour.service.evaluation.EvaluationService engine;
    @Mock
    private ForecastEvaluationRepository repository;
    @Mock
    private NotificationDispatcher notificationDispatcher;
    @Mock
    private WeatherTriageEvaluator weatherTriageEvaluator;
    @Mock
    private TideAlignmentEvaluator tideAlignmentEvaluator;
    @Mock
    private SlotAtmosphereWriter slotAtmosphereWriter;

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
    private final List<LocationTaskEvent> published = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger claudeCalls = new AtomicInteger();
    private JobRunEntity jobRun;
    private ForecastService forecastService;

    @BeforeEach
    void setUp() {
        tracker = new RunProgressTracker(dynamicSchedulerService, graceScheduler, 5_000L) {
            @Override
            SseEmitter newEmitter() {
                return new RecordingEmitter();
            }
        };
        jobRun = run(1L);
        panel = (RecordingEmitter) tracker.subscribe(1L);

        forecastService = new ForecastService(solarService, openMeteoService, augmentor, legacyEvaluationService,
                engine, repository, notificationDispatcher,
                event -> {
                    if (event instanceof LocationTaskEvent taskEvent) {
                        published.add(taskEvent);
                        tracker.onTaskEvent(taskEvent);
                    }
                },
                weatherTriageEvaluator, tideAlignmentEvaluator, slotAtmosphereWriter, tracker, CLOCK);
    }

    /** Stubs exactly what a pipeline run through the real {@link ForecastService} touches; no strategies. */
    private void stubPipeline() {
        stubPipeline(List.of());
    }

    private void stubPipeline(List<OptimisationStrategyEntity> strategies) {
        when(commandFactory.resolveEvaluationModel(any())).thenReturn(EvaluationModel.HAIKU);
        when(optimisationStrategyService.getEnabledStrategies(any())).thenReturn(strategies);
        when(optimisationStrategyService.serialiseEnabledStrategies(any())).thenReturn("");
        when(locationService.shouldEvaluateSunrise(any())).thenReturn(false);
        when(locationService.shouldEvaluateSunset(any())).thenReturn(true);
        when(openMeteoService.prefetchWeatherBatch(anyList(), any())).thenReturn(new java.util.LinkedHashMap<>());
        when(openMeteoService.prefetchCloudBatch(anyList(), any()))
                .thenReturn(new CloudPointCache(java.util.Map.of()));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any()))
                .thenReturn(LocalDateTime.parse("2026-10-03T17:30:00"));
        when(solarService.sunsetAzimuthDeg(anyDouble(), anyDouble(), any())).thenReturn(250);
        when(augmentor.augmentWithDirectionalCloud(any(), anyDouble(), anyDouble(),
                anyInt(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(augmentor.augmentWithCloudApproach(any(), anyDouble(), anyDouble(),
                anyInt(), any(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(augmentor.augmentWithTideData(any(), any(), any(), any(), anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(augmentor.augmentWithLocationOrientation(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(augmentor.augmentWithStormSurge(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(augmentor.augmentWithInversionScore(any(), any(), anyBoolean())).thenAnswer(inv -> inv.getArgument(0));
        when(augmentor.augmentWithBluebellConditions(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        AtmosphericData data = TestAtmosphericData.builder().targetType(TargetType.SUNSET).build();
        when(openMeteoService.getAtmosphericDataFromCache(any(), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());
    }

    /** For the tests in which an evaluation is scored and saved. */
    private void stubSave() {
        when(repository.save(any())).thenReturn(ForecastEvaluationEntity.builder().id(1L).rating(3).build());
    }

    private static JobRunEntity run(long id) {
        JobRunEntity entity = new JobRunEntity();
        entity.setId(id);
        return entity;
    }

    private static LocationEntity location(long id, RegionEntity region) {
        return LocationEntity.builder()
                .id(id).name("Place " + id).lat(54.0 + id / 10.0).lon(-1.5)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .region(region)
                .build();
    }

    private static List<LocationEntity> places(int count) {
        List<LocationEntity> places = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            places.add(location(id, null));
        }
        return places;
    }

    private ForecastCommand command(List<LocationEntity> locations) {
        return new ForecastCommand(RunType.SHORT_TERM, DATES, locations, haikuStrategy, true);
    }

    private ForecastCommandExecutor executor(java.util.concurrent.Executor forecastExecutor) {
        return new ForecastCommandExecutor(forecastService, locationService, jobRunService, solarService,
                commandFactory, forecastExecutor, optimisationStrategyService, tracker,
                event -> {
                    if (event instanceof LocationTaskEvent taskEvent) {
                        published.add(taskEvent);
                        tracker.onTaskEvent(taskEvent);
                    }
                },
                sentinelSelector, astroConditionsService, stabilityClassifier, openMeteoService,
                stabilitySnapshotProvider, CLOCK);
    }

    private static EvaluationResult scored() {
        return new EvaluationResult.Scored(new SunsetEvaluation(null, 70, 75, "Good"));
    }

    private static EvaluationResult errored(String errorType) {
        return new EvaluationResult.Errored(errorType, RAW_SECRET);
    }

    /** Every Claude call is counted; the n-th call (from 0) answers {@code script[n]}, the last repeats. */
    private void claudeAnswers(EvaluationResult... script) {
        when(engine.evaluateNow(any(EvaluationTask.class), any(BatchTriggerSource.class))).thenAnswer(inv -> {
            int n = claudeCalls.getAndIncrement();
            return script[Math.min(n, script.length - 1)];
        });
    }

    private static AnthropicServiceException serviceError(int status) {
        AnthropicServiceException ex = mock(AnthropicServiceException.class);
        when(ex.statusCode()).thenReturn(status);
        return ex;
    }

    private List<LocationTaskEvent> failedEvents() {
        synchronized (published) {
            return published.stream().filter(e -> e.getState() == LocationTaskState.FAILED).toList();
        }
    }

    private List<LocationTaskEvent> eventsFor(String taskKey) {
        synchronized (published) {
            return published.stream().filter(e -> e.getTaskKey().equals(taskKey)).toList();
        }
    }

    private static String sunsetKey(long id) {
        return "Place " + id + "|" + DAY + "|SUNSET";
    }

    private JsonNode theOnlyRunComplete() {
        List<JsonNode> completes = panel.events.stream().filter(e -> e.startsWith("run-complete|"))
                .map(e -> {
                    try {
                        return JSON.readTree(e.substring("run-complete|".length()));
                    } catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                }).toList();
        assertThat(completes).as("run-complete events seen by the panel").hasSize(1);
        return completes.getFirst();
    }

    private void assertNothingSecretWasPublished() {
        assertThat(published.stream().map(LocationTaskEvent::getErrorMessage).filter(m -> m != null))
                .noneMatch(m -> m.contains("HUNTER2") || m.contains("sk-ant") || m.contains("jdbc"));
        assertThat(String.join("\n", panel.events)).doesNotContain("HUNTER2").doesNotContain("sk-ant")
                .doesNotContain("jdbc");
    }

    // -------------------------------------------------------------------------
    // 1. Each failure kind is published at the moment it fails, once, with its phrase
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "anthropic_429|Claude's rate limit was reached.",
            "anthropic_529|Claude was overloaded.",
            "anthropic_500|Claude returned a server error.",
            "anthropic_503|Claude returned a server error.",
            "AnthropicIoException|Claude could not be reached.",
            "parse_error|Claude's reply could not be read.",
            "reply_unreadable|Claude's reply could not be read.",
            "refusal|Claude declined to evaluate this place.",
            "content_filter|Claude declined to evaluate this place.",
            "circuit_open|Not attempted: Claude calls are paused after repeated failures. Try again in a minute.",
            "bulkhead_full|Not attempted: too many Claude calls were already waiting.",
            "SomethingElse|Evaluation failed (see server log).",
    })
    @DisplayName("an errored evaluation publishes FAILED once with its fixed phrase and the EVALUATING step, "
            + "and the run is NOT stopped: every place is still attempted")
    void erroredEvaluation_publishedOnceWithItsPhrase_runNotStopped(String errorType, String phrase) {
        stubPipeline();
        claudeAnswers(errored(errorType));

        executor(Runnable::run).execute(command(places(3)), jobRun);

        for (long id = 1; id <= 3; id++) {
            assertThat(eventsFor(sunsetKey(id)).stream().filter(e -> e.getState() == LocationTaskState.FAILED))
                    .as("FAILED events for place %d", id).singleElement().satisfies(e -> {
                        assertThat(e.getErrorMessage()).isEqualTo(phrase);
                        assertThat(e.getFailedStep()).isEqualTo("EVALUATING");
                    });
        }
        assertThat(claudeCalls).hasValue(3);
        JsonNode complete = theOnlyRunComplete();
        assertThat(complete.get("status").asText()).isEqualTo("FAILED");
        assertThat(complete.get("failed").asInt()).isEqualTo(3);
        assertThat(complete.get("reason").isNull()).isTrue();
        assertThat(complete.get("retryable").asBoolean()).isTrue();
        assertThat(tracker.isStopped(1L)).isFalse();
        verify(jobRunService).completeRun(jobRun, 0, 3, DATES);
        assertNothingSecretWasPublished();
    }

    @Test
    @DisplayName("an exception thrown by the evaluation call publishes FAILED once with the fallback phrase, "
            + "never the exception's message, and does not wait for the completion sweep")
    void exceptionFromEvaluationCall_publishedOnce() {
        stubPipeline();
        when(engine.evaluateNow(any(EvaluationTask.class), any(BatchTriggerSource.class)))
                .thenThrow(new IllegalStateException(RAW_SECRET));

        executor(Runnable::run).execute(command(places(2)), jobRun);

        assertThat(failedEvents()).hasSize(2).allSatisfy(e -> {
            assertThat(e.getErrorMessage()).isEqualTo(FALLBACK);
            assertThat(e.getFailedStep()).isEqualTo("EVALUATING");
        });
        // Published before the sweep could: the sweep's own events carry the task's state as the step
        // and would have been a second FAILED event for the same task.
        assertThat(eventsFor(sunsetKey(1)).stream().filter(e -> e.getState() == LocationTaskState.FAILED)).hasSize(1);
        assertThat(tracker.isStopped(1L)).isFalse();
        assertNothingSecretWasPublished();
    }

    @Test
    @DisplayName("a failure persisting a successfully scored result publishes FAILED once, and the place is "
            + "not left COMPLETE")
    void failurePersistingResult_publishedOnce() {
        stubPipeline();
        claudeAnswers(scored());
        when(repository.save(any())).thenThrow(new IllegalStateException(RAW_SECRET));

        executor(Runnable::run).execute(command(places(1)), jobRun);

        assertThat(eventsFor(sunsetKey(1)).stream().filter(e -> e.getState() == LocationTaskState.FAILED))
                .singleElement().satisfies(e -> {
                    assertThat(e.getErrorMessage()).isEqualTo(FALLBACK);
                    assertThat(e.getFailedStep()).isEqualTo("EVALUATING");
                });
        assertThat(eventsFor(sunsetKey(1)).stream().filter(e -> e.getState() == LocationTaskState.COMPLETE))
                .isEmpty();
        assertNothingSecretWasPublished();
    }

    // -------------------------------------------------------------------------
    // 2. A rejected key stops the run
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(strings = {"anthropic_401", "anthropic_403"})
    @DisplayName("a rejected key (401 or 403) stops the run: ONE Claude call out of eight places, the first place "
            + "reads the key reason, the other seven the not-attempted phrase, and nothing completed means FAILED")
    void rejectedKey_stopsTheRun_nothingCompleted(String errorType) {
        stubPipeline();
        claudeAnswers(errored(errorType));

        executor(Runnable::run).execute(command(places(8)), jobRun);

        assertThat(claudeCalls).hasValue(1);
        ArgumentCaptor<EvaluationTask> sent = ArgumentCaptor.forClass(EvaluationTask.class);
        verify(engine).evaluateNow(sent.capture(), eq(BatchTriggerSource.ADMIN));
        assertThat(((EvaluationTask.Forecast) sent.getValue()).location().getName()).isEqualTo("Place 1");
        assertThat(eventsFor(sunsetKey(1)).stream().filter(e -> e.getState() == LocationTaskState.FAILED))
                .singleElement().satisfies(e -> {
                    assertThat(e.getErrorMessage()).isEqualTo(KEY_REJECTED);
                    assertThat(e.getFailedStep()).isEqualTo("EVALUATING");
                });
        for (long id = 2; id <= 8; id++) {
            List<LocationTaskEvent> events = eventsFor(sunsetKey(id));
            assertThat(events.stream().filter(e -> e.getState() == LocationTaskState.FAILED))
                    .as("FAILED events for place %d", id).singleElement().satisfies(e -> {
                        assertThat(e.getErrorMessage()).isEqualTo(NOT_ATTEMPTED);
                        assertThat(e.getFailedStep()).isEqualTo("EVALUATING");
                    });
            assertThat(events.stream().filter(e -> e.getState() == LocationTaskState.EVALUATING))
                    .as("a place that was never attempted never shows as evaluating").isEmpty();
        }
        JsonNode complete = theOnlyRunComplete();
        assertThat(complete.get("status").asText()).isEqualTo("FAILED");
        assertThat(complete.get("reason").asText()).isEqualTo(RUN_STOPPED);
        assertThat(complete.get("retryable").asBoolean()).isFalse();
        assertThat(complete.get("completed").asInt()).isZero();
        assertThat(complete.get("failed").asInt()).isEqualTo(8);
        assertThat(complete.get("phase").asText()).isEqualTo("EARLY_STOP");
        verify(jobRunService).completeRun(jobRun, 0, 8, DATES);
        assertThat(jobRun.getNotes()).isEqualTo(RUN_STOPPED);
        assertNothingSecretWasPublished();
    }

    @Test
    @DisplayName("a rejected key after some places had completed is PARTIAL (stopped early) and still not retryable")
    void rejectedKey_afterSomeCompleted_isPartial() {
        stubPipeline();
        stubSave();
        claudeAnswers(scored(), scored(), errored("anthropic_401"));

        executor(Runnable::run).execute(command(places(8)), jobRun);

        assertThat(claudeCalls).hasValue(3);
        JsonNode complete = theOnlyRunComplete();
        assertThat(complete.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(complete.get("reason").asText()).isEqualTo(RUN_STOPPED);
        assertThat(complete.get("retryable").asBoolean()).isFalse();
        assertThat(complete.get("completed").asInt()).isEqualTo(2);
        assertThat(complete.get("failed").asInt()).isEqualTo(6);
        assertThat(failedEvents().stream().filter(e -> NOT_ATTEMPTED.equals(e.getErrorMessage()))).hasSize(5);
        verify(jobRunService).completeRun(jobRun, 2, 6, DATES);
    }

    @Test
    @DisplayName("a 401 thrown (not returned) by the evaluation call stops the run just the same")
    void rejectedKey_thrownRatherThanReturned_stopsTheRun() {
        stubPipeline();
        AnthropicServiceException rejected = serviceError(401);
        when(engine.evaluateNow(any(EvaluationTask.class), any(BatchTriggerSource.class))).thenAnswer(inv -> {
            claudeCalls.incrementAndGet();
            throw rejected;
        });

        executor(Runnable::run).execute(command(places(5)), jobRun);

        assertThat(claudeCalls).hasValue(1);
        assertThat(theOnlyRunComplete().get("reason").asText()).isEqualTo(RUN_STOPPED);
        assertNothingSecretWasPublished();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"anthropic_429", "anthropic_529", "anthropic_500"})
    @DisplayName("a rate limit, an overload or a server error does NOT stop the run")
    void transientFailures_doNotStopTheRun(String errorType) {
        stubPipeline();
        claudeAnswers(errored(errorType));

        executor(Runnable::run).execute(command(places(6)), jobRun);

        assertThat(claudeCalls).hasValue(6);
        assertThat(tracker.isStopped(1L)).isFalse();
        assertThat(failedEvents().stream().filter(e -> NOT_ATTEMPTED.equals(e.getErrorMessage()))).isEmpty();
        assertThat(theOnlyRunComplete().get("retryable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a run that was stopped does not block a run started afterwards (overlapping runs are pinned "
            + "at the tracker: RunProgressTrackerTest.stopRun_isPerRun)")
    void stoppedRun_doesNotBlockALaterRun() {
        stubPipeline();
        stubSave();
        claudeAnswers(errored("anthropic_401"));
        executor(Runnable::run).execute(command(places(4)), jobRun);
        assertThat(claudeCalls).hasValue(1);
        assertThat(tracker.isStopped(1L)).isTrue();

        JobRunEntity second = run(2L);
        claudeCalls.set(0);
        org.mockito.Mockito.reset(engine);
        claudeAnswers(scored());
        assertThat(tracker.isStopped(2L)).isFalse();

        executor(Runnable::run).execute(command(places(4)), second);

        assertThat(claudeCalls).hasValue(4);
        assertThat(tracker.isStopped(2L)).isFalse();
        assertThat(tracker.getProgress(2L).getCompleted()).isEqualTo(4);
        assertThat(tracker.getProgress(2L).getFailureReason()).isNull();
        assertThat(tracker.getProgress(2L).isRetryable()).isTrue();
        verify(jobRunService).completeRun(second, 4, 0, DATES);
        // The first run is still what it was.
        assertThat(tracker.getProgress(1L).getFailureReason()).isEqualTo(RUN_STOPPED);
    }

    @Test
    @DisplayName("a 401 in the sentinel phase stops the run too: one Claude call, the other places not attempted")
    void rejectedKey_inSentinelPhase_stopsTheRun() {
        RegionEntity north = RegionEntity.builder().id(1L).name("North").enabled(true).build();
        List<LocationEntity> inRegion = new ArrayList<>();
        for (long id = 1; id <= 4; id++) {
            inRegion.add(location(id, north));
        }
        OptimisationStrategyEntity sentinel = new OptimisationStrategyEntity();
        sentinel.setStrategyType(OptimisationStrategyType.SENTINEL_SAMPLING);
        sentinel.setEnabled(true);
        sentinel.setParamValue(2);
        stubPipeline(List.of(sentinel));
        when(sentinelSelector.selectSentinels(any())).thenReturn(List.of(inRegion.getFirst()));
        claudeAnswers(errored("anthropic_401"));

        executor(Runnable::run).execute(command(inRegion), jobRun);

        assertThat(claudeCalls).hasValue(1);
        assertThat(eventsFor(sunsetKey(1)).stream().filter(e -> e.getState() == LocationTaskState.FAILED))
                .singleElement().satisfies(e -> assertThat(e.getErrorMessage()).isEqualTo(KEY_REJECTED));
        assertThat(failedEvents().stream().filter(e -> NOT_ATTEMPTED.equals(e.getErrorMessage()))).hasSize(3);
        JsonNode complete = theOnlyRunComplete();
        assertThat(complete.get("status").asText()).isEqualTo("FAILED");
        assertThat(complete.get("reason").asText()).isEqualTo(RUN_STOPPED);
        assertThat(complete.get("retryable").asBoolean()).isFalse();
        verify(jobRunService).completeRun(jobRun, 0, 4, DATES);
    }

    @Test
    @DisplayName("a single-place run whose only sentinel gets the 401 leaves nothing for the full phase, and "
            + "still completes in EARLY_STOP, not COMPLETE (the early-return exit)")
    void rejectedKey_singlePlaceSentinel_completesInEarlyStop() {
        RegionEntity north = RegionEntity.builder().id(1L).name("North").enabled(true).build();
        LocationEntity only = location(1L, north);
        OptimisationStrategyEntity sentinel = new OptimisationStrategyEntity();
        sentinel.setStrategyType(OptimisationStrategyType.SENTINEL_SAMPLING);
        sentinel.setEnabled(true);
        sentinel.setParamValue(2);
        stubPipeline(List.of(sentinel));
        when(sentinelSelector.selectSentinels(any())).thenReturn(List.of(only));
        claudeAnswers(errored("anthropic_401"));

        executor(Runnable::run).execute(command(List.of(only)), jobRun);

        assertThat(claudeCalls).hasValue(1);
        JsonNode complete = theOnlyRunComplete();
        assertThat(complete.get("phase").asText()).isEqualTo("EARLY_STOP");
        assertThat(complete.get("status").asText()).isEqualTo("FAILED");
        assertThat(complete.get("retryable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("calls already in flight when the key is rejected finish on their own and report the key reason; "
            + "a place that had not started is not attempted")
    void inFlightCalls_finishAndReportTheKeyReason() throws Exception {
        stubPipeline();
        CyclicBarrier bothInFlight = new CyclicBarrier(2);
        when(engine.evaluateNow(any(EvaluationTask.class), any(BatchTriggerSource.class))).thenAnswer(inv -> {
            claudeCalls.incrementAndGet();
            bothInFlight.await(10, TimeUnit.SECONDS);
            return errored("anthropic_401");
        });
        // Two threads: the first two places are in flight together; the third can only start once one of
        // them has finished, by which time the run is stopped.
        ExecutorService twoThreads = Executors.newFixedThreadPool(2);
        try {
            executor(twoThreads).execute(command(places(3)), jobRun);
        } finally {
            twoThreads.shutdownNow();
        }

        assertThat(claudeCalls).hasValue(2);
        List<String> messages = failedEvents().stream().map(LocationTaskEvent::getErrorMessage).sorted().toList();
        assertThat(messages).containsExactlyInAnyOrder(NOT_ATTEMPTED, KEY_REJECTED, KEY_REJECTED);
        assertThat(theOnlyRunComplete().get("reason").asText()).isEqualTo(RUN_STOPPED);
        verify(jobRunService).completeRun(jobRun, 0, 3, DATES);
    }

    /**
     * The "anthropic" breaker as production builds it: the window, threshold and wait come from YAML (pinned
     * to these values by {@code ResilienceConfigTest.anthropicCircuitBreakerSettings}), the exception
     * predicate from {@link ResilienceConfig}'s real customizer.
     */
    private static CircuitBreaker productionAnthropicBreaker() {
        CircuitBreakerConfig.Builder builder = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .permittedNumberOfCallsInHalfOpenState(3);
        new ResilienceConfig().anthropicCircuitBreakerCustomizer().customize(builder);
        return CircuitBreaker.of("anthropic", builder.build());
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a rejected key does not open the Claude circuit breaker: a run started right after one that was "
            + "stopped on the key still reaches Claude and stops with the KEY reason, not 'calls are paused'")
    void rejectedKey_twoRunsInARow_bothStopWithTheKeyReason() throws Exception {
        stubPipeline();
        CircuitBreaker breaker = productionAnthropicBreaker();
        AnthropicServiceException rejected = serviceError(401);
        AtomicInteger reachedClaude = new AtomicInteger();
        AtomicBoolean firstWave = new AtomicBoolean(true);
        CyclicBarrier sixInFlight = new CyclicBarrier(6);
        // The engine as production has it: the call goes through the breaker, and a failure comes back as an
        // Errored result classified by EvaluationFailure (a refused call is circuit_open, a 401 anthropic_401).
        when(engine.evaluateNow(any(EvaluationTask.class), any(BatchTriggerSource.class))).thenAnswer(inv -> {
            try {
                breaker.executeSupplier(() -> {
                    reachedClaude.incrementAndGet();
                    if (firstWave.get()) {
                        awaitQuietly(sixInFlight);
                    }
                    throw rejected;
                });
                return scored();
            } catch (Exception e) {
                return new EvaluationResult.Errored(EvaluationFailure.errorTypeOf(e), e.getMessage());
            }
        });

        // Run 1: the first wave. All six places are in flight together, so six 401s are on the breaker's
        // books before the run's stop is seen — more than its minimum of five calls at a 50% rate.
        ExecutorService sixThreads = Executors.newFixedThreadPool(6);
        try {
            executor(sixThreads).execute(command(places(6)), jobRun);
        } finally {
            sixThreads.shutdownNow();
        }
        assertThat(reachedClaude).hasValue(6);
        assertThat(tracker.getProgress(1L).getFailureReason()).isEqualTo(RUN_STOPPED);
        assertThat(breaker.getState()).as("six rejected calls are not an outage")
                .isEqualTo(CircuitBreaker.State.CLOSED);

        // Run 2, started straight away: it must reach Claude, be rejected, and stop for the same reason.
        firstWave.set(false);
        JobRunEntity second = run(2L);
        executor(Runnable::run).execute(command(places(4)), second);

        assertThat(reachedClaude).as("the second run's first call was not refused by the breaker").hasValue(7);
        assertThat(tracker.isStopped(2L)).isTrue();
        assertThat(tracker.getProgress(2L).getFailureReason()).isEqualTo(RUN_STOPPED);
        assertThat(tracker.getProgress(2L).isRetryable()).isFalse();
        assertThat(eventsFor(sunsetKey(1)).stream().filter(e -> e.getState() == LocationTaskState.FAILED
                && KEY_REJECTED.equals(e.getErrorMessage()))).isNotEmpty();
        assertThat(failedEvents().stream().map(LocationTaskEvent::getErrorMessage))
                .noneMatch(m -> m != null && m.contains("paused"));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("a place the run never attempted never enters the evaluation engine, which is where each "
            + "evaluation's child job_run is created")
    void notAttempted_neverEntersTheEngine() {
        stubPipeline();
        claudeAnswers(errored("anthropic_403"));

        executor(Runnable::run).execute(command(places(3)), jobRun);

        // Three places, one entry: the other two were stopped before the engine.
        ArgumentCaptor<EvaluationTask> sent = ArgumentCaptor.forClass(EvaluationTask.class);
        verify(engine, times(1)).evaluateNow(sent.capture(), eq(BatchTriggerSource.ADMIN));
        assertThat(((EvaluationTask.Forecast) sent.getValue()).location().getName()).isEqualTo("Place 1");
    }
}
