package com.gregochr.goldenhour.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.messages.BatchService;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRefreshedEvent;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.entity.CachedEvaluationEntity;
import com.gregochr.goldenhour.repository.CachedEvaluationRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BriefingEvaluationService}.
 */
@ExtendWith(MockitoExtension.class)
class BriefingEvaluationServiceTest {

    @Mock
    private LocationService locationService;
    @Mock
    private BriefingService briefingService;
    @Mock
    private ForecastService forecastService;
    @Mock
    private ModelSelectionService modelSelectionService;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private ForecastBatchRepository batchRepository;
    @Mock
    private CachedEvaluationRepository cachedEvaluationRepository;
    @Mock
    private AnthropicClient anthropicClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private BriefingEvaluationService service;

    private static final LocalDate DATE = LocalDate.of(2026, 3, 30);
    private static final String REGION = "Northumberland";

    @BeforeEach
    void setUp() {
        service = new BriefingEvaluationService(
                locationService, briefingService, forecastService,
                modelSelectionService, jobRunService, batchRepository,
                cachedEvaluationRepository, anthropicClient, objectMapper);
        // Default: all locations are colour locations
        org.mockito.Mockito.lenient().when(briefingService.isColourLocation(any())).thenReturn(true);
        // Default: no outstanding batches
        org.mockito.Mockito.lenient()
                .when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of());
        // Default: no existing DB cache entries
        org.mockito.Mockito.lenient()
                .when(cachedEvaluationRepository.findByCacheKey(any()))
                .thenReturn(java.util.Optional.empty());
    }

    // ── Filtering tests ────────────────────────────────────────────────────────

    @Test
    @DisplayName("evaluateRegion evaluates GO/MARGINAL locations only")
    void evaluates_goAndMarginal_only() {
        LocationEntity goLoc = locationInRegion("Bamburgh", REGION);
        LocationEntity marginalLoc = locationInRegion("Dunstanburgh", REGION);
        LocationEntity standdownLoc = locationInRegion("Craster", REGION);

        when(locationService.findAllEnabled()).thenReturn(List.of(goLoc, marginalLoc, standdownLoc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                .thenReturn(JobRunEntity.builder().id(1L).build());

        BriefingSlot goSlot = slot("Bamburgh", Verdict.GO);
        BriefingSlot marginalSlot = slot("Dunstanburgh", Verdict.MARGINAL);
        BriefingSlot standdownSlot = slot("Craster", Verdict.STANDDOWN);
        stubBriefing(List.of(goSlot, marginalSlot, standdownSlot));

        ForecastPreEvalResult preEval = nonTriagedResult(goLoc);
        when(forecastService.fetchWeatherAndTriage(
                any(), eq(DATE), eq(TargetType.SUNSET), any(), any(), eq(false), any()))
                .thenReturn(preEval);
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(4, 72, 65, "Good conditions"));

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);

        // GO + MARGINAL = 2 calls
        verify(forecastService, times(2))
                .fetchWeatherAndTriage(any(), eq(DATE), eq(TargetType.SUNSET), any(),
                        any(), eq(false), any());

        // Standdown location must never reach forecastService
        verify(forecastService, never())
                .fetchWeatherAndTriage(
                        argThat(loc -> "Craster".equals(loc.getName())),
                        any(), any(), any(), any(), eq(false), any());

        Map<String, BriefingEvaluationResult> cached =
                service.getCachedScores(REGION, DATE, TargetType.SUNSET);
        assertThat(cached).hasSize(2);
        assertThat(cached).containsKey("Bamburgh");
        assertThat(cached).containsKey("Dunstanburgh");
        assertThat(cached.get("Bamburgh").rating()).isEqualTo(4);
    }

    @Test
    @DisplayName("Cache hit replays results without new Claude calls")
    void cacheHit_noCalls() {
        LocationEntity loc = locationInRegion("Bamburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                .thenReturn(JobRunEntity.builder().id(1L).build());
        stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));

        ForecastPreEvalResult preEval = nonTriagedResult(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), eq(DATE), eq(TargetType.SUNSET), any(), any(), eq(false), any()))
                .thenReturn(preEval);
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(4, 72, 65, "Good"));

        SseEmitter emitter1 = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter1);

        // Second call should use cache
        SseEmitter emitter2 = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter2);

        // ForecastService should only be called once (first run)
        verify(forecastService).fetchWeatherAndTriage(
                any(), eq(DATE), eq(TargetType.SUNSET), any(), any(), eq(false), any());
    }

    @Test
    @DisplayName("BriefingRefreshedEvent retains evaluation cache")
    void eventRetainsCache() {
        LocationEntity loc = locationInRegion("Bamburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                .thenReturn(JobRunEntity.builder().id(1L).build());
        stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));

        ForecastPreEvalResult preEval = nonTriagedResult(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), eq(DATE), eq(TargetType.SUNSET), any(), any(), eq(false), any()))
                .thenReturn(preEval);
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(4, 72, 65, "Good"));

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isNotEmpty();

        service.onBriefingRefreshed(new BriefingRefreshedEvent(this));
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isNotEmpty();
    }

    @Test
    @DisplayName("getCachedScores returns empty map when no cache exists")
    void noCacheReturnsEmpty() {
        assertThat(service.getCachedScores("Unknown", DATE, TargetType.SUNSET)).isEmpty();
    }

    @Test
    @DisplayName("getCachedEvaluatedAt returns null when no cache exists")
    void getCachedEvaluatedAt_noCacheReturnsNull() {
        assertThat(service.getCachedEvaluatedAt("Unknown", DATE, TargetType.SUNSET)).isNull();
    }

    @Test
    @DisplayName("getCachedEvaluatedAt returns formatted UK time after evaluation")
    void getCachedEvaluatedAt_returnsFormattedTimeAfterEvaluation() {
        LocationEntity loc = locationInRegion("Bamburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                .thenReturn(JobRunEntity.builder().id(1L).build());
        stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));

        ForecastPreEvalResult preEval = nonTriagedResult(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), eq(DATE), eq(TargetType.SUNSET), any(), any(), eq(false), any()))
                .thenReturn(preEval);
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(4, 72, 65, "Good"));

        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

        String evaluatedAt = service.getCachedEvaluatedAt(REGION, DATE, TargetType.SUNSET);
        assertThat(evaluatedAt).isNotNull();
        assertThat(evaluatedAt).matches("\\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("getCachedEvaluatedAt returns formatted time after writeFromBatch")
    void getCachedEvaluatedAt_returnsTimeAfterBatchWrite() {
        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Durham", 4, 72, 65, "Good");
        service.writeFromBatch(REGION + "|" + DATE + "|SUNSET", List.of(result));

        String evaluatedAt = service.getCachedEvaluatedAt(REGION, DATE, TargetType.SUNSET);
        assertThat(evaluatedAt).isNotNull();
        assertThat(evaluatedAt).matches("\\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("Null briefing yields no evaluable locations")
    void nullBriefing_noEvaluations() {
        LocationEntity loc = locationInRegion("Bamburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(briefingService.getCachedBriefing()).thenReturn(null);

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);

        verifyNoInteractions(forecastService);
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isEmpty();
    }

    @Test
    @DisplayName("Location without region is filtered out")
    void locationWithoutRegion_filtered() {
        LocationEntity noRegionLoc = LocationEntity.builder()
                .id(99L).name("Orphan").lat(55.0).lon(-1.5)
                .locationType(Set.of(LocationType.LANDSCAPE))
                .region(null)
                .build();
        when(locationService.findAllEnabled()).thenReturn(List.of(noRegionLoc));
        stubBriefing(List.of(slot("Orphan", Verdict.GO)));

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);

        verifyNoInteractions(forecastService);
    }

    @Test
    @DisplayName("Non-colour location (pure wildlife) is filtered out")
    void nonColourLocation_filtered() {
        LocationEntity wildlifeLoc = locationInRegion("Farne Islands", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(wildlifeLoc));
        when(briefingService.isColourLocation(any())).thenReturn(false);
        stubBriefing(List.of(slot("Farne Islands", Verdict.GO)));

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);

        verifyNoInteractions(forecastService);
    }

    @Test
    @DisplayName("No locations in region yields empty evaluation")
    void noLocationsInRegion_emptyEvaluation() {
        LocationEntity otherRegionLoc = locationInRegion("Whitby", "Yorkshire");
        when(locationService.findAllEnabled()).thenReturn(List.of(otherRegionLoc));
        stubBriefing(List.of(slot("Whitby", Verdict.GO)));

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);

        verifyNoInteractions(forecastService);
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isEmpty();
    }

    @Test
    @DisplayName("Evaluation error continues to next location")
    void evaluationError_continuesProcessing() {
        LocationEntity failLoc = locationInRegion("Bamburgh", REGION);
        LocationEntity okLoc = locationInRegion("Dunstanburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(failLoc, okLoc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                .thenReturn(JobRunEntity.builder().id(1L).build());
        stubBriefing(List.of(slot("Bamburgh", Verdict.GO), slot("Dunstanburgh", Verdict.MARGINAL)));

        // First call fails, second succeeds — must use broad any() for sequential chaining
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(), eq(false), any()))
                .thenThrow(new RuntimeException("API timeout"))
                .thenReturn(nonTriagedResult(okLoc));
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(3, 50, 45, "Decent"));

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);

        Map<String, BriefingEvaluationResult> cached =
                service.getCachedScores(REGION, DATE, TargetType.SUNSET);
        assertThat(cached).hasSize(1);
        assertThat(cached).containsKey("Dunstanburgh");
        assertThat(cached).doesNotContainKey("Bamburgh");
    }

    @Test
    @DisplayName("Different date creates separate cache entry")
    void differentDate_separateCache() {
        LocalDate otherDate = LocalDate.of(2026, 3, 31);
        LocationEntity loc = locationInRegion("Bamburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                .thenReturn(JobRunEntity.builder().id(1L).build());

        ForecastPreEvalResult preEval = nonTriagedResult(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(LocalDate.class), any(TargetType.class), any(), any(), eq(false), any()))
                .thenReturn(preEval);
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(4, 72, 65, "Good"))
                .thenReturn(evaluationEntity(2, 30, 25, "Poor"));

        stubBriefingForDate(DATE, List.of(slot("Bamburgh", Verdict.GO)));

        SseEmitter emitter1 = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter1);

        stubBriefingForDate(otherDate, List.of(slot("Bamburgh", Verdict.GO)));

        SseEmitter emitter2 = mock(SseEmitter.class);
        service.evaluateRegion(REGION, otherDate, TargetType.SUNSET, emitter2);

        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).hasSize(1);
        assertThat(service.getCachedScores(REGION, otherDate, TargetType.SUNSET)).hasSize(1);
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)
                .get("Bamburgh").rating()).isEqualTo(4);
    }

    @Test
    @DisplayName("SUNRISE targetType works independently of SUNSET")
    void sunriseTargetType_separateCache() {
        LocationEntity loc = locationInRegion("Bamburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                .thenReturn(JobRunEntity.builder().id(1L).build());

        ForecastPreEvalResult preEval = nonTriagedResult(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), eq(DATE), eq(TargetType.SUNRISE), any(), any(), eq(false), any()))
                .thenReturn(preEval);
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(5, 90, 85, "Spectacular"));

        stubBriefingForDateAndTarget(DATE, TargetType.SUNRISE, List.of(slot("Bamburgh", Verdict.GO)));

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNRISE, emitter);

        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNRISE)).hasSize(1);
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isEmpty();
    }

    @Test
    @DisplayName("Triaged location includes triage reason in summary")
    void triagedLocation_includesReasonInSummary() {
        LocationEntity loc = locationInRegion("Bamburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                .thenReturn(JobRunEntity.builder().id(1L).build());
        stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));

        ForecastPreEvalResult triagedPreEval = new ForecastPreEvalResult(
                true, "Heavy rain forecast", null, loc, DATE, TargetType.SUNSET,
                null, null, 0, null, null, null, null);
        when(forecastService.fetchWeatherAndTriage(
                any(), eq(DATE), eq(TargetType.SUNSET), any(), any(), eq(false), any()))
                .thenReturn(triagedPreEval);

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);

        BriefingEvaluationResult result =
                service.getCachedScores(REGION, DATE, TargetType.SUNSET).get("Bamburgh");
        assertThat(result.summary()).contains("Heavy rain forecast");
        assertThat(result.fierySkyPotential()).isEqualTo(5);
        assertThat(result.goldenHourPotential()).isEqualTo(5);
    }

    @Test
    @DisplayName("clearCache is idempotent when empty")
    void clearCache_idempotentWhenEmpty() {
        assertThat(service.clearCache()).isZero();
        assertThat(service.clearCache()).isZero();
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isEmpty();
    }

    @Test
    @DisplayName("clearCache removes all region entries")
    void clearCache_removesAllRegions() {
        LocationEntity loc1 = locationInRegion("Bamburgh", REGION);
        LocationEntity loc2 = locationInRegion("Whitby", "Yorkshire");
        when(locationService.findAllEnabled())
                .thenReturn(List.of(loc1))
                .thenReturn(List.of(loc2));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                .thenReturn(JobRunEntity.builder().id(1L).build());

        ForecastPreEvalResult preEval = nonTriagedResult(loc1);
        when(forecastService.fetchWeatherAndTriage(
                any(), eq(DATE), eq(TargetType.SUNSET), any(), any(), eq(false), any()))
                .thenReturn(preEval);
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(4, 70, 60, "Good"));

        stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

        stubBriefingForRegion("Yorkshire", List.of(slot("Whitby", Verdict.GO)));
        service.evaluateRegion("Yorkshire", DATE, TargetType.SUNSET, mock(SseEmitter.class));

        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isNotEmpty();
        assertThat(service.getCachedScores("Yorkshire", DATE, TargetType.SUNSET)).isNotEmpty();

        assertThat(service.clearCache()).isEqualTo(2);

        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isEmpty();
        assertThat(service.getCachedScores("Yorkshire", DATE, TargetType.SUNSET)).isEmpty();
    }

    @Test
    @DisplayName("Cache hit calls emitter.complete()")
    void cacheHit_callsEmitterComplete() throws Exception {
        LocationEntity loc = locationInRegion("Bamburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                .thenReturn(JobRunEntity.builder().id(1L).build());
        stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));

        ForecastPreEvalResult preEval = nonTriagedResult(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), eq(DATE), eq(TargetType.SUNSET), any(), any(), eq(false), any()))
                .thenReturn(preEval);
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(4, 72, 65, "Good"));

        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

        // Cache hit path
        SseEmitter emitter2 = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter2);

        verify(emitter2).complete();
    }

    @Test
    @DisplayName("evaluation-complete SSE event contains regionName, date, targetType, and evaluatedAt")
    void evaluationComplete_sseEventContainsExpectedFields() {
        LocationEntity loc = locationInRegion("Bamburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                .thenReturn(JobRunEntity.builder().id(1L).build());
        stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));

        ForecastPreEvalResult preEval = nonTriagedResult(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), eq(DATE), eq(TargetType.SUNSET), any(), any(), eq(false), any()))
                .thenReturn(preEval);
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(4, 72, 65, "Good"));

        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

        // Cache state is the source of truth for what is sent in the evaluation-complete event.
        // SseEventBuilder content cannot be meaningfully inspected via Mockito.
        Map<String, BriefingEvaluationResult> cached =
                service.getCachedScores(REGION, DATE, TargetType.SUNSET);
        assertThat(cached).containsKey("Bamburgh");
        assertThat(service.getCachedEvaluatedAt(REGION, DATE, TargetType.SUNSET))
                .isNotNull()
                .matches("\\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("All STANDDOWN slots yield empty evaluation with no job run")
    void allStanddown_noJobRun() {
        LocationEntity loc = locationInRegion("Craster", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubBriefing(List.of(slot("Craster", Verdict.STANDDOWN)));

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);

        verifyNoInteractions(forecastService);
        verifyNoInteractions(modelSelectionService);
        verifyNoInteractions(jobRunService);
    }

    @Test
    @DisplayName("Triaged location returns rating 1")
    void triagedLocationReturnsRating1() {
        LocationEntity loc = locationInRegion("Bamburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                .thenReturn(JobRunEntity.builder().id(1L).build());
        stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));

        ForecastPreEvalResult triagedPreEval = new ForecastPreEvalResult(
                true, "Heavy rain", null, loc, DATE, TargetType.SUNSET,
                null, null, 0, null, null, null, null);
        when(forecastService.fetchWeatherAndTriage(
                any(), eq(DATE), eq(TargetType.SUNSET), any(), any(), eq(false), any()))
                .thenReturn(triagedPreEval);

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);

        Map<String, BriefingEvaluationResult> cached =
                service.getCachedScores(REGION, DATE, TargetType.SUNSET);
        assertThat(cached.get("Bamburgh").rating()).isEqualTo(1);
    }

    // ── Argument verification tests ────────────────────────────────────────────

    @Nested
    @DisplayName("Argument verification")
    class ArgumentVerification {

        @Test
        @DisplayName("fetchWeatherAndTriage receives the correct location entity")
        void fetchWeatherAndTriage_receives_correct_location() {
            LocationEntity loc = locationInRegion("Bamburgh", REGION);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
            when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                    .thenReturn(JobRunEntity.builder().id(1L).build());
            stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));

            ForecastPreEvalResult preEval = nonTriagedResult(loc);
            when(forecastService.fetchWeatherAndTriage(
                    any(), eq(DATE), eq(TargetType.SUNSET), any(), any(), eq(false), any()))
                    .thenReturn(preEval);
            when(forecastService.evaluateAndPersist(any(), any()))
                    .thenReturn(evaluationEntity(4, 72, 65, "Good"));

            service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<LocationEntity> locCaptor = ArgumentCaptor.forClass(LocationEntity.class);
            verify(forecastService).fetchWeatherAndTriage(
                    locCaptor.capture(), eq(DATE), eq(TargetType.SUNSET), any(),
                    any(), eq(false), any());
            assertThat(locCaptor.getValue().getName()).isEqualTo("Bamburgh");
            assertThat(locCaptor.getValue().getLat()).isEqualTo(55.6);
        }

        @Test
        @DisplayName("tideAlignmentEnabled is always false for briefing evaluations")
        void tideAlignmentEnabled_always_false() {
            LocationEntity loc = locationInRegion("Bamburgh", REGION);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
            when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                    .thenReturn(JobRunEntity.builder().id(1L).build());
            stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));

            ForecastPreEvalResult preEval = nonTriagedResult(loc);
            when(forecastService.fetchWeatherAndTriage(
                    any(), eq(DATE), eq(TargetType.SUNSET), any(), any(), eq(false), any()))
                    .thenReturn(preEval);
            when(forecastService.evaluateAndPersist(any(), any()))
                    .thenReturn(evaluationEntity(4, 72, 65, "Good"));

            service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

            // Verify tideAlignment (6th arg) is explicitly false, not just any boolean
            verify(forecastService).fetchWeatherAndTriage(
                    any(), any(), any(), any(), any(), eq(false), any());

            // And never called with true
            verify(forecastService, never()).fetchWeatherAndTriage(
                    any(), any(), any(), any(), any(), eq(true), any());
        }

        @Test
        @DisplayName("Model from modelSelectionService is passed to fetchWeatherAndTriage")
        void model_passed_from_modelSelectionService() {
            LocationEntity loc = locationInRegion("Bamburgh", REGION);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.SONNET);
            when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.SONNET)))
                    .thenReturn(JobRunEntity.builder().id(1L).build());
            stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));

            ForecastPreEvalResult preEval = nonTriagedResult(loc);
            when(forecastService.fetchWeatherAndTriage(
                    any(), eq(DATE), eq(TargetType.SUNSET), any(), eq(EvaluationModel.SONNET), eq(false), any()))
                    .thenReturn(preEval);
            when(forecastService.evaluateAndPersist(any(), any()))
                    .thenReturn(evaluationEntity(4, 72, 65, "Good"));

            service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

            verify(forecastService).fetchWeatherAndTriage(
                    any(), eq(DATE), eq(TargetType.SUNSET), any(),
                    eq(EvaluationModel.SONNET), eq(false), any());
        }

        @Test
        @DisplayName("jobRun started with SHORT_TERM and manual=true")
        void jobRun_started_with_SHORT_TERM_and_manual() {
            LocationEntity loc = locationInRegion("Bamburgh", REGION);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
            when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                    .thenReturn(JobRunEntity.builder().id(1L).build());
            stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));

            ForecastPreEvalResult preEval = nonTriagedResult(loc);
            when(forecastService.fetchWeatherAndTriage(
                    any(), eq(DATE), eq(TargetType.SUNSET), any(), any(), eq(false), any()))
                    .thenReturn(preEval);
            when(forecastService.evaluateAndPersist(any(), any()))
                    .thenReturn(evaluationEntity(4, 72, 65, "Good"));

            service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

            verify(jobRunService).startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU));
        }

        @Test
        @DisplayName("jobRun completed with correct succeeded/failed counts")
        void jobRun_completed_with_correct_counts() {
            LocationEntity goLoc = locationInRegion("Bamburgh", REGION);
            LocationEntity marginalLoc = locationInRegion("Dunstanburgh", REGION);
            when(locationService.findAllEnabled()).thenReturn(List.of(goLoc, marginalLoc));
            when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
            when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                    .thenReturn(JobRunEntity.builder().id(1L).build());
            stubBriefing(List.of(slot("Bamburgh", Verdict.GO), slot("Dunstanburgh", Verdict.MARGINAL)));

            ForecastPreEvalResult preEval = nonTriagedResult(goLoc);
            when(forecastService.fetchWeatherAndTriage(
                    any(), eq(DATE), eq(TargetType.SUNSET), any(), any(), eq(false), any()))
                    .thenReturn(preEval);
            when(forecastService.evaluateAndPersist(any(), any()))
                    .thenReturn(evaluationEntity(4, 72, 65, "Good"));

            service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

            verify(jobRunService).completeRun(any(JobRunEntity.class), eq(2), eq(0), eq(List.of(DATE)));
        }

        @Test
        @DisplayName("evaluateAndPersist receives the preEval output from fetchWeatherAndTriage")
        void evaluateAndPersist_receives_preEval_output() {
            LocationEntity loc = locationInRegion("Bamburgh", REGION);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
            when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                    .thenReturn(JobRunEntity.builder().id(1L).build());
            stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));

            ForecastPreEvalResult preEval = nonTriagedResult(loc);
            when(forecastService.fetchWeatherAndTriage(
                    any(), eq(DATE), eq(TargetType.SUNSET), any(), any(), eq(false), any()))
                    .thenReturn(preEval);
            when(forecastService.evaluateAndPersist(any(), any()))
                    .thenReturn(evaluationEntity(4, 72, 65, "Good"));

            service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

            // Capture the exact ForecastPreEvalResult passed to evaluateAndPersist
            ArgumentCaptor<ForecastPreEvalResult> captor =
                    ArgumentCaptor.forClass(ForecastPreEvalResult.class);
            verify(forecastService).evaluateAndPersist(captor.capture(), any(JobRunEntity.class));
            assertThat(captor.getValue()).isSameAs(preEval);
        }
    }

    // ── Batch cache methods ──────────────────────────────────────────────────────

    @Test
    @DisplayName("hasEvaluation returns false when cache is empty")
    void hasEvaluation_returnsFalseWhenNotCached() {
        assertThat(service.hasEvaluation("North East|2026-04-07|SUNRISE")).isFalse();
    }

    @Test
    @DisplayName("hasEvaluation returns false when writeFromBatch was called with empty list")
    void hasEvaluation_returnsFalseWhenEmptyResults() {
        service.writeFromBatch("North East|2026-04-07|SUNRISE", List.of());

        assertThat(service.hasEvaluation("North East|2026-04-07|SUNRISE")).isFalse();
    }

    @Test
    @DisplayName("hasEvaluation returns true after writeFromBatch with at least one result")
    void hasEvaluation_returnsTrueAfterWriteFromBatch() {
        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Durham", 4, 72, 65, "Good conditions");
        service.writeFromBatch("North East|2026-04-07|SUNRISE", List.of(result));

        assertThat(service.hasEvaluation("North East|2026-04-07|SUNRISE")).isTrue();
    }

    @Test
    @DisplayName("writeFromBatch results are returned by getCachedScores")
    void writeFromBatch_populatesCacheAccessibleByGetCachedScores() {
        LocalDate date = LocalDate.of(2026, 4, 7);
        BriefingEvaluationResult durham =
                new BriefingEvaluationResult("Durham", 4, 72, 65, "Good conditions");
        BriefingEvaluationResult sunderland =
                new BriefingEvaluationResult("Sunderland", 3, 45, 40, "Marginal");
        service.writeFromBatch("North East|2026-04-07|SUNRISE", List.of(durham, sunderland));

        Map<String, BriefingEvaluationResult> scores =
                service.getCachedScores("North East", date, TargetType.SUNRISE);
        assertThat(scores).hasSize(2);
        assertThat(scores.get("Durham").rating()).isEqualTo(4);
        assertThat(scores.get("Durham").fierySkyPotential()).isEqualTo(72);
        assertThat(scores.get("Sunderland").rating()).isEqualTo(3);
    }

    @Test
    @DisplayName("writeFromBatch cache entry is retained after onBriefingRefreshed")
    void writeFromBatch_retainedAfterBriefingRefresh() {
        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Durham", 4, 72, 65, "Good");
        service.writeFromBatch("North East|2026-04-07|SUNRISE", List.of(result));
        assertThat(service.hasEvaluation("North East|2026-04-07|SUNRISE")).isTrue();

        service.onBriefingRefreshed(new BriefingRefreshedEvent(this));

        assertThat(service.hasEvaluation("North East|2026-04-07|SUNRISE")).isTrue();
    }

    @Test
    @DisplayName("batch scores are retrievable with correct values after briefing refresh")
    void writeFromBatch_scoresIntactAfterBriefingRefresh() {
        String cacheKey = "North East|" + DATE + "|SUNRISE";
        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Durham", 4, 72, 65, "Good conditions");
        service.writeFromBatch(cacheKey, List.of(result));

        service.onBriefingRefreshed(new BriefingRefreshedEvent(this));

        Map<String, BriefingEvaluationResult> scores =
                service.getCachedScores("North East", DATE, TargetType.SUNRISE);
        assertThat(scores).containsKey("Durham");
        BriefingEvaluationResult preserved = scores.get("Durham");
        assertThat(preserved.rating()).isEqualTo(4);
        assertThat(preserved.fierySkyPotential()).isEqualTo(72);
        assertThat(preserved.goldenHourPotential()).isEqualTo(65);
        assertThat(preserved.summary()).isEqualTo("Good conditions");
    }

    @Test
    @DisplayName("evaluatedAt timestamp survives briefing refresh")
    void writeFromBatch_evaluatedAtSurvivesBriefingRefresh() {
        String cacheKey = REGION + "|" + DATE + "|SUNSET";
        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Bamburgh", 3, 50, 45, "Average");
        service.writeFromBatch(cacheKey, List.of(result));
        String timestampBefore = service.getCachedEvaluatedAt(REGION, DATE, TargetType.SUNSET);
        assertThat(timestampBefore).isNotNull();

        service.onBriefingRefreshed(new BriefingRefreshedEvent(this));

        String timestampAfter = service.getCachedEvaluatedAt(REGION, DATE, TargetType.SUNSET);
        assertThat(timestampAfter).isEqualTo(timestampBefore);
    }

    @Test
    @DisplayName("clearCache removes batch-written entries")
    void clearCache_removesBatchWrittenEntries() {
        String cacheKey = "North East|" + DATE + "|SUNRISE";
        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Durham", 4, 72, 65, "Good");
        service.writeFromBatch(cacheKey, List.of(result));
        assertThat(service.hasEvaluation(cacheKey)).isTrue();

        assertThat(service.clearCache()).isEqualTo(1);

        assertThat(service.hasEvaluation(cacheKey)).isFalse();
        assertThat(service.getCachedScores("North East", DATE, TargetType.SUNRISE)).isEmpty();
        assertThat(service.getCachedEvaluatedAt("North East", DATE, TargetType.SUNRISE)).isNull();
    }

    @Test
    @DisplayName("writeFromBatch overwrites existing cache entry for same key")
    void writeFromBatch_overwritesExistingEntry() {
        String cacheKey = REGION + "|" + DATE + "|SUNSET";

        BriefingEvaluationResult first =
                new BriefingEvaluationResult("Bamburgh", 4, 72, 65, "First run");
        service.writeFromBatch(cacheKey, List.of(first));

        BriefingEvaluationResult second =
                new BriefingEvaluationResult("Dunstanburgh", 3, 50, 45, "Second run");
        service.writeFromBatch(cacheKey, List.of(second));

        Map<String, BriefingEvaluationResult> scores =
                service.getCachedScores(REGION, DATE, TargetType.SUNSET);
        assertThat(scores).hasSize(1);
        assertThat(scores).containsKey("Dunstanburgh");
        assertThat(scores).doesNotContainKey("Bamburgh");
    }

    // ── Batch cancel-on-JFDI tests ──────────────────────────────────────────────

    @Test
    @DisplayName("cancelOutstandingForecastBatches: no submitted batches — Anthropic API never called")
    void cancel_noBatchesSubmitted_anthropicNotCalled() {
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of());

        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

        verifyNoInteractions(anthropicClient);
    }

    @Test
    @DisplayName("cancelOutstandingForecastBatches: FORECAST batch is cancelled and persisted as CANCELLED")
    void cancel_forecastBatch_cancelledAndSaved() {
        MessageService messageService = mock(MessageService.class);
        BatchService batchService = mock(BatchService.class);
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);

        ForecastBatchEntity batch = new ForecastBatchEntity(
                "batch_forecast_001", BatchType.FORECAST, 10, Instant.now().plusSeconds(3600));
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batch));

        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

        verify(batchService).cancel(any(String.class));

        ArgumentCaptor<ForecastBatchEntity> captor = ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        ForecastBatchEntity saved = captor.getValue();
        assertThat(saved.getAnthropicBatchId()).isEqualTo("batch_forecast_001");
        assertThat(saved.getStatus()).isEqualTo(BatchStatus.CANCELLED);
        assertThat(saved.getEndedAt()).isNotNull();
        assertThat(saved.getErrorMessage()).contains("real-time SSE evaluation");
    }

    @Test
    @DisplayName("cancelOutstandingForecastBatches: AURORA batch is skipped — cancel never called")
    void cancel_auroraBatch_notCancelled() {
        ForecastBatchEntity auroraBatch = new ForecastBatchEntity(
                "batch_aurora_001", BatchType.AURORA, 5, Instant.now().plusSeconds(3600));
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(auroraBatch));

        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

        verifyNoInteractions(anthropicClient);
        verify(batchRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancelOutstandingForecastBatches: cancel API failure is swallowed — evaluation proceeds")
    void cancel_apiFailure_doesNotBlockEvaluation() {
        MessageService messageService = mock(MessageService.class);
        BatchService batchService = mock(BatchService.class);
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);
        doThrow(new RuntimeException("Batch already ended")).when(batchService).cancel(any(String.class));

        ForecastBatchEntity batch = new ForecastBatchEntity(
                "batch_forecast_002", BatchType.FORECAST, 4, Instant.now().plusSeconds(3600));
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batch));

        // Evaluation should proceed without throwing
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

        // Cancel was attempted
        verify(batchService).cancel(any(String.class));
        // But batch was never updated to CANCELLED since cancel threw
        verify(batchRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancelOutstandingForecastBatches: mixed batch types — only FORECAST batch cancelled")
    void cancel_mixedBatchTypes_onlyForecastCancelled() {
        MessageService messageService = mock(MessageService.class);
        BatchService batchService = mock(BatchService.class);
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);

        ForecastBatchEntity forecastBatch = new ForecastBatchEntity(
                "batch_forecast_003", BatchType.FORECAST, 8, Instant.now().plusSeconds(3600));
        ForecastBatchEntity auroraBatch = new ForecastBatchEntity(
                "batch_aurora_003", BatchType.AURORA, 3, Instant.now().plusSeconds(3600));
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(forecastBatch, auroraBatch));

        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

        // Exactly one cancel call — for the FORECAST batch only
        verify(batchService, times(1)).cancel(any(String.class));

        // The saved entity is the FORECAST batch, now CANCELLED
        ArgumentCaptor<ForecastBatchEntity> captor = ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getAnthropicBatchId()).isEqualTo("batch_forecast_003");
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.CANCELLED);
    }

    @Test
    @DisplayName("evaluateRegion: location already in partial cache is skipped — no Claude call for it")
    void evaluateRegion_partialCacheHit_skipsAlreadyCachedLocation() {
        LocationEntity durham = locationInRegion("Durham", REGION);
        LocationEntity bamburgh = locationInRegion("Bamburgh", REGION);

        // Pre-populate cache with Durham result (simulates batch having written it)
        BriefingEvaluationResult cachedResult =
                new BriefingEvaluationResult("Durham", 4, 72, 65, "Batch result");
        service.writeFromBatch(REGION + "|" + DATE + "|SUNSET", List.of(cachedResult));

        when(locationService.findAllEnabled()).thenReturn(List.of(durham, bamburgh));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                .thenReturn(JobRunEntity.builder().id(1L).build());
        stubBriefing(List.of(slot("Durham", Verdict.GO), slot("Bamburgh", Verdict.GO)));

        when(forecastService.fetchWeatherAndTriage(
                any(), eq(DATE), eq(TargetType.SUNSET), any(), any(), eq(false), any()))
                .thenReturn(nonTriagedResult(bamburgh));
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(3, 50, 45, "Fair"));

        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

        // Only Bamburgh triggers a Claude call — Durham was already cached
        verify(forecastService, times(1))
                .fetchWeatherAndTriage(any(), eq(DATE), eq(TargetType.SUNSET), any(), any(), eq(false), any());
        verify(forecastService, never())
                .fetchWeatherAndTriage(
                        argThat(loc -> "Durham".equals(loc.getName())),
                        any(), any(), any(), any(), eq(false), any());
    }

    @Test
    @DisplayName("evaluateRegion: partial cache from batch is merged with new SSE results in final cache")
    void evaluateRegion_partialCacheHit_finalCacheMergesBatchAndSseResults() {
        LocationEntity durham = locationInRegion("Durham", REGION);
        LocationEntity bamburgh = locationInRegion("Bamburgh", REGION);

        // Durham pre-cached via batch — Bamburgh is NOT cached
        BriefingEvaluationResult durhamBatchResult =
                new BriefingEvaluationResult("Durham", 4, 72, 65, "Batch result");
        service.writeFromBatch(REGION + "|" + DATE + "|SUNSET", List.of(durhamBatchResult));

        when(locationService.findAllEnabled()).thenReturn(List.of(durham, bamburgh));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), eq(EvaluationModel.HAIKU)))
                .thenReturn(JobRunEntity.builder().id(1L).build());
        stubBriefing(List.of(slot("Durham", Verdict.GO), slot("Bamburgh", Verdict.GO)));

        when(forecastService.fetchWeatherAndTriage(
                any(), eq(DATE), eq(TargetType.SUNSET), any(), any(), eq(false), any()))
                .thenReturn(nonTriagedResult(bamburgh));
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(3, 55, 48, "SSE result"));

        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

        // Final cache must contain both Durham (from batch) and Bamburgh (from SSE)
        Map<String, BriefingEvaluationResult> scores =
                service.getCachedScores(REGION, DATE, TargetType.SUNSET);
        assertThat(scores).hasSize(2);
        assertThat(scores.get("Durham").summary()).isEqualTo("Batch result");
        assertThat(scores.get("Bamburgh").summary()).isEqualTo("SSE result");
    }

    @Test
    @DisplayName("cancelOutstandingForecastBatches: two FORECAST batches — both cancelled and saved")
    void cancel_twoForecastBatches_bothCancelled() {
        MessageService messageService = mock(MessageService.class);
        BatchService batchService = mock(BatchService.class);
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);

        ForecastBatchEntity batchA = new ForecastBatchEntity(
                "batch_a", BatchType.FORECAST, 5, Instant.now().plusSeconds(3600));
        ForecastBatchEntity batchB = new ForecastBatchEntity(
                "batch_b", BatchType.FORECAST, 3, Instant.now().plusSeconds(3600));
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batchA, batchB));

        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

        ArgumentCaptor<ForecastBatchEntity> captor = ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository, times(2)).save(captor.capture());
        List<ForecastBatchEntity> saved = captor.getAllValues();
        assertThat(saved).extracting(ForecastBatchEntity::getStatus)
                .containsOnly(BatchStatus.CANCELLED);
        assertThat(saved).extracting(ForecastBatchEntity::getAnthropicBatchId)
                .containsExactlyInAnyOrder("batch_a", "batch_b");
        assertThat(saved).extracting(ForecastBatchEntity::getEndedAt)
                .doesNotContainNull();
    }

    @Test
    @DisplayName("evaluateRegion: cancel called even when full region is already in cache")
    void evaluateRegion_fullCacheHit_stillCancelsBatch() {
        MessageService messageService = mock(MessageService.class);
        BatchService batchService = mock(BatchService.class);
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);

        ForecastBatchEntity batch = new ForecastBatchEntity(
                "batch_forecast_004", BatchType.FORECAST, 5, Instant.now().plusSeconds(3600));
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batch));

        // Pre-populate full cache for the region
        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Bamburgh", 5, 90, 85, "Excellent");
        service.writeFromBatch(REGION + "|" + DATE + "|SUNSET", List.of(result));

        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

        // Cancel still fires even though the cache returned immediately
        verify(batchService).cancel(any(String.class));
        verifyNoInteractions(forecastService);
    }

    // ── DB persistence tests ────────────────────────────────────────────────────

    @Test
    @DisplayName("writeFromBatch persists results to DB via cachedEvaluationRepository")
    void writeFromBatch_persistsToDb() {
        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Bamburgh", 4, 72, 65, "Good");
        String cacheKey = REGION + "|" + DATE + "|SUNSET";

        service.writeFromBatch(cacheKey, List.of(result));

        ArgumentCaptor<CachedEvaluationEntity> captor =
                ArgumentCaptor.forClass(CachedEvaluationEntity.class);
        verify(cachedEvaluationRepository).save(captor.capture());

        CachedEvaluationEntity saved = captor.getValue();
        assertThat(saved.getCacheKey()).isEqualTo(cacheKey);
        assertThat(saved.getRegionName()).isEqualTo(REGION);
        assertThat(saved.getEvaluationDate()).isEqualTo(DATE);
        assertThat(saved.getTargetType()).isEqualTo("SUNSET");
        assertThat(saved.getSource()).isEqualTo("BATCH");
        assertThat(saved.getResultsJson()).contains("Bamburgh");
    }

    @Test
    @DisplayName("writeFromBatch updates existing DB row on same cache key")
    void writeFromBatch_updatesExistingDbRow() {
        String cacheKey = REGION + "|" + DATE + "|SUNSET";
        CachedEvaluationEntity existing = new CachedEvaluationEntity();
        existing.setId(42L);
        existing.setCacheKey(cacheKey);
        existing.setEvaluatedAt(Instant.now().minusSeconds(3600));
        when(cachedEvaluationRepository.findByCacheKey(cacheKey))
                .thenReturn(java.util.Optional.of(existing));

        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Bamburgh", 5, 90, 85, "Excellent");
        service.writeFromBatch(cacheKey, List.of(result));

        ArgumentCaptor<CachedEvaluationEntity> captor =
                ArgumentCaptor.forClass(CachedEvaluationEntity.class);
        verify(cachedEvaluationRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(42L);
        assertThat(captor.getValue().getSource()).isEqualTo("BATCH");
    }

    @Test
    @DisplayName("rehydrateCacheOnStartup loads entries for today and future into in-memory cache")
    void rehydrate_loadsTodayAndFuture() throws Exception {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Europe/London"));
        String cacheKey = REGION + "|" + today + "|SUNSET";
        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Bamburgh", 4, 72, 65, "Good");

        CachedEvaluationEntity entity = new CachedEvaluationEntity();
        entity.setCacheKey(cacheKey);
        entity.setResultsJson(objectMapper.writeValueAsString(List.of(result)));
        entity.setEvaluatedAt(Instant.now());

        when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(today))
                .thenReturn(List.of(entity));

        service.rehydrateCacheOnStartup();

        Map<String, BriefingEvaluationResult> scores =
                service.getCachedScores(REGION, today, TargetType.SUNSET);
        assertThat(scores).containsKey("Bamburgh");
        assertThat(scores.get("Bamburgh").rating()).isEqualTo(4);
    }

    @Test
    @DisplayName("rehydrateCacheOnStartup skips entries with corrupt JSON")
    void rehydrate_skipsCorruptJson() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Europe/London"));

        CachedEvaluationEntity entity = new CachedEvaluationEntity();
        entity.setCacheKey(REGION + "|" + today + "|SUNSET");
        entity.setResultsJson("NOT VALID JSON {{{{");
        entity.setEvaluatedAt(Instant.now());

        when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(today))
                .thenReturn(List.of(entity));

        service.rehydrateCacheOnStartup();

        assertThat(service.getCachedScores(REGION, today, TargetType.SUNSET)).isEmpty();
    }

    @Test
    @DisplayName("clearCache deletes all DB rows")
    void clearCache_deletesDbRows() {
        when(cachedEvaluationRepository.count()).thenReturn(3L);

        service.clearCache();

        verify(cachedEvaluationRepository).deleteAll();
    }

    @Test
    @DisplayName("DB persistence failure does not break in-memory cache write")
    void persistToDb_failureDoesNotBreakInMemory() {
        when(cachedEvaluationRepository.save(any())).thenThrow(
                new RuntimeException("DB down"));

        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Bamburgh", 4, 72, 65, "Good");
        String cacheKey = REGION + "|" + DATE + "|SUNSET";
        service.writeFromBatch(cacheKey, List.of(result));

        // In-memory cache should still work despite DB failure
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET))
                .containsKey("Bamburgh");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private LocationEntity locationInRegion(String name, String regionName) {
        RegionEntity region = RegionEntity.builder().id(1L).name(regionName).build();
        return LocationEntity.builder()
                .id((long) name.hashCode())
                .name(name)
                .lat(55.6)
                .lon(-1.7)
                .region(region)
                .locationType(Set.of(LocationType.LANDSCAPE))
                .build();
    }

    private BriefingSlot slot(String locationName, Verdict verdict) {
        return new BriefingSlot(locationName,
                LocalDateTime.of(2026, 3, 30, 18, 30),
                verdict,
                new BriefingSlot.WeatherConditions(30, null, 20000, 70, 8.0, 6.0, 2, null, 0, 0),
                new BriefingSlot.TideInfo(null, false, null, null, false, false, null, null, null),
                List.of(), null);
    }

    private void stubBriefing(List<BriefingSlot> slots) {
        stubBriefingForRegionDateTarget(REGION, DATE, TargetType.SUNSET, slots);
    }

    private void stubBriefingForDate(LocalDate date, List<BriefingSlot> slots) {
        stubBriefingForRegionDateTarget(REGION, date, TargetType.SUNSET, slots);
    }

    private void stubBriefingForDateAndTarget(LocalDate date, TargetType target,
            List<BriefingSlot> slots) {
        stubBriefingForRegionDateTarget(REGION, date, target, slots);
    }

    private void stubBriefingForRegion(String regionName, List<BriefingSlot> slots) {
        stubBriefingForRegionDateTarget(regionName, DATE, TargetType.SUNSET, slots);
    }

    private void stubBriefingForRegionDateTarget(String regionName, LocalDate date,
            TargetType target, List<BriefingSlot> slots) {
        BriefingRegion region = new BriefingRegion(regionName, Verdict.GO, "Clear skies",
                List.of(), slots, 8.0, 6.0, null, null, null, null);
        BriefingEventSummary es = new BriefingEventSummary(
                target, List.of(region), List.of());
        BriefingDay day = new BriefingDay(date, List.of(es));
        DailyBriefingResponse resp = new DailyBriefingResponse(
                LocalDateTime.now(), "Headline", List.of(day), List.of(),
                null, null, false, false, 0, "Opus", List.of(), List.of());
        org.mockito.Mockito.lenient().when(briefingService.getCachedBriefing()).thenReturn(resp);
    }

    private ForecastPreEvalResult nonTriagedResult(LocationEntity loc) {
        return new ForecastPreEvalResult(
                false, null, null, loc, DATE, TargetType.SUNSET,
                null, null, 0, null, null, null, null);
    }

    private ForecastEvaluationEntity evaluationEntity(int rating, int fiery, int golden, String summary) {
        return ForecastEvaluationEntity.builder()
                .rating(rating)
                .fierySkyPotential(fiery)
                .goldenHourPotential(golden)
                .summary(summary)
                .build();
    }
}
