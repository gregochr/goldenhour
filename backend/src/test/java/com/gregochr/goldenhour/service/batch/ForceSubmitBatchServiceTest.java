package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.models.messages.batches.MessageBatchRequestCounts;
import com.anthropic.models.messages.batches.MessageBatchResult;
import com.anthropic.models.messages.batches.MessageBatchSucceededResult;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.messages.BatchService;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.batch.ForceSubmitBatchService.ForceResultResponse;
import com.gregochr.goldenhour.service.batch.ForceSubmitBatchService.ForceSubmitResult;
import com.gregochr.goldenhour.service.evaluation.EvaluationHandle;
import com.gregochr.goldenhour.service.evaluation.EvaluationService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ForceSubmitBatchService}.
 *
 * <p>Post Pass 3.3.2 the service routes its JFDI and force-submit work through
 * {@link EvaluationService#submit}; the engine owns request building, custom-id
 * generation, prompt-builder selection, and {@code ForecastBatchEntity}
 * persistence. Those concerns are tested in the engine-level test suites
 * ({@code EvaluationServiceImplTest}, {@code BatchRequestFactoryTest},
 * {@code BatchSubmissionServiceTest}). This class therefore focuses on the
 * service's own responsibilities: location filtering, data assembly via
 * {@code ForecastService.fetchWeatherAndTriage}, error / partial-failure
 * handling, return shape, and the {@code getResult} polling-bypass path that
 * still calls the SDK directly per Pass 2.5 design.
 */
@ExtendWith(MockitoExtension.class)
class ForceSubmitBatchServiceTest {

    @Mock
    private AnthropicClient anthropicClient;
    @Mock
    private MessageService messageService;
    @Mock
    private BatchService batchService;
    @Mock
    private RegionRepository regionRepository;
    @Mock
    private LocationService locationService;
    @Mock
    private ForecastService forecastService;
    @Mock
    private ModelSelectionService modelSelectionService;
    @Mock
    private EvaluationService evaluationService;

    private ForceSubmitBatchService service;

    @BeforeEach
    void setUp() {
        service = new ForceSubmitBatchService(
                anthropicClient, regionRepository, locationService,
                forecastService, modelSelectionService, evaluationService);
    }

    private void stubBatchService() {
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);
    }

    // ── forceSubmit: input validation ─────────────────────────────────────

    @Test
    @DisplayName("forceSubmit throws when region not found")
    void forceSubmit_unknownRegion_throws() {
        when(regionRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.forceSubmit(999L,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Region not found");
        verifyNoInteractions(evaluationService);
    }

    @Test
    @DisplayName("forceSubmit throws when no enabled locations in region")
    void forceSubmit_noLocationsInRegion_throws() {
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));
        when(locationService.findAllEnabled()).thenReturn(List.of());

        assertThatThrownBy(() -> service.forceSubmit(7L,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No enabled locations");
        verifyNoInteractions(evaluationService);
    }

    // ── forceSubmit: happy-path engine routing ────────────────────────────

    @Test
    @DisplayName("forceSubmit submits a forecast task per location with WriteTarget.BRIEFING_CACHE via FORCE trigger")
    void forceSubmit_validRegion_submitsThroughEngine() {
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.HAIKU, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);
        when(evaluationService.submit(anyList(), eq(BatchTriggerSource.FORCE)))
                .thenReturn(new EvaluationHandle(null, "msgbatch_force001", 1));

        ForceSubmitResult result = service.forceSubmit(7L,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        assertThat(result.batchId()).isEqualTo("msgbatch_force001");
        assertThat(result.requestCount()).isEqualTo(1);
        assertThat(result.locationsAttempted()).isEqualTo(1);
        assertThat(result.locationsIncluded()).isEqualTo(1);
        assertThat(result.locationsFailedData()).isEqualTo(0);
        assertThat(result.failedLocations()).isEmpty();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvaluationTask.Forecast>> taskCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(evaluationService).submit(taskCaptor.capture(), eq(BatchTriggerSource.FORCE));
        List<EvaluationTask.Forecast> tasks = taskCaptor.getValue();
        assertThat(tasks).hasSize(1);
        EvaluationTask.Forecast task = tasks.get(0);
        assertThat(task.location()).isEqualTo(loc);
        assertThat(task.date()).isEqualTo(LocalDate.of(2026, 4, 16));
        assertThat(task.targetType()).isEqualTo(TargetType.SUNSET);
        assertThat(task.model()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(task.data()).isSameAs(data);
        assertThat(task.writeTarget())
                .isEqualTo(EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
    }

    @Test
    @DisplayName("forceSubmit returns null batchId when all data assembly fails")
    void forceSubmit_allDataFails_returnsNullBatchId() {
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any()))
                .thenThrow(new RuntimeException("Open-Meteo timeout"));

        ForceSubmitResult result = service.forceSubmit(7L,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        assertThat(result.batchId()).isNull();
        assertThat(result.requestCount()).isEqualTo(0);
        assertThat(result.locationsFailedData()).isEqualTo(1);
        assertThat(result.failedLocations()).containsExactly("Bamburgh Castle");
        verify(evaluationService, never()).submit(anyList(), any());
    }

    @Test
    @DisplayName("forceSubmit handles null atmospheric data gracefully")
    void forceSubmit_nullAtmosphericData_recordsFailure() {
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, null, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.HAIKU, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);

        ForceSubmitResult result = service.forceSubmit(7L,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        assertThat(result.batchId()).isNull();
        assertThat(result.locationsFailedData()).isEqualTo(1);
        assertThat(result.failedLocations()).containsExactly("Bamburgh Castle");
        verify(evaluationService, never()).submit(anyList(), any());
    }

    @Test
    @DisplayName("forceSubmit returns failed status when engine returns null handle")
    void forceSubmit_engineNullHandle_returnsFailed() {
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.HAIKU, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);
        when(evaluationService.submit(anyList(), eq(BatchTriggerSource.FORCE)))
                .thenReturn(null);

        ForceSubmitResult result = service.forceSubmit(7L,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        assertThat(result.batchId()).isNull();
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.locationsAttempted()).isEqualTo(1);
        assertThat(result.locationsIncluded()).isEqualTo(1);
    }

    @Test
    @DisplayName("forceSubmit filters locations to the target region only")
    void forceSubmit_multipleRegions_onlyIncludesTargetRegion() {
        RegionEntity northumberland = buildRegion(7L, "Northumberland");
        RegionEntity yorkshire = buildRegion(8L, "Yorkshire");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(northumberland));

        LocationEntity loc1 = buildLocation(10L, "Bamburgh Castle", northumberland);
        LocationEntity loc2 = buildLocation(20L, "Flamborough Head", yorkshire);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc1,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.HAIKU, loc1.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);
        when(evaluationService.submit(anyList(), eq(BatchTriggerSource.FORCE)))
                .thenReturn(new EvaluationHandle(null, "msgbatch_filter", 1));

        ForceSubmitResult result = service.forceSubmit(7L,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        assertThat(result.locationsAttempted()).isEqualTo(1);
        assertThat(result.requestCount()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvaluationTask.Forecast>> taskCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(evaluationService).submit(taskCaptor.capture(), eq(BatchTriggerSource.FORCE));
        assertThat(taskCaptor.getValue()).hasSize(1);
        assertThat(taskCaptor.getValue().get(0).location()).isEqualTo(loc1);
    }

    @Test
    @DisplayName("forceSubmit passes correct location, date, event, tideType, model to forecastService")
    void forceSubmit_verifiesFetchWeatherArgs() {
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        loc.setTideType(Set.of(TideType.HIGH));
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        AtmosphericData data = mock(AtmosphericData.class);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNRISE,
                LocalDateTime.of(2026, 4, 16, 5, 30), 90, 1,
                EvaluationModel.SONNET, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(
                eq(loc), eq(LocalDate.of(2026, 4, 16)), eq(TargetType.SUNRISE),
                eq(Set.of(TideType.HIGH)), eq(EvaluationModel.SONNET),
                eq(false), isNull()))
                .thenReturn(preEval);
        when(evaluationService.submit(anyList(), eq(BatchTriggerSource.FORCE)))
                .thenReturn(new EvaluationHandle(null, "msgbatch_args", 1));

        service.forceSubmit(7L, LocalDate.of(2026, 4, 16), TargetType.SUNRISE);

        verify(forecastService).fetchWeatherAndTriage(
                eq(loc), eq(LocalDate.of(2026, 4, 16)), eq(TargetType.SUNRISE),
                eq(Set.of(TideType.HIGH)), eq(EvaluationModel.SONNET),
                eq(false), isNull());
    }

    @Test
    @DisplayName("forceSubmit resolves model from BATCH_NEAR_TERM, not other RunTypes")
    void forceSubmit_resolvesBatchNearTermModel() {
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.OPUS);

        AtmosphericData data = mock(AtmosphericData.class);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.OPUS, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);
        when(evaluationService.submit(anyList(), eq(BatchTriggerSource.FORCE)))
                .thenReturn(new EvaluationHandle(null, "msgbatch_model", 1));

        service.forceSubmit(7L, LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        verify(modelSelectionService).getActiveModel(RunType.BATCH_NEAR_TERM);
    }

    @Test
    @DisplayName("forceSubmit reports counts with mixed pass/fail outcomes")
    void forceSubmit_mixedOutcomes_correctCounts() {
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc1 = buildLocation(10L, "Bamburgh Castle", region);
        LocationEntity loc2 = buildLocation(11L, "Lindisfarne", region);
        LocationEntity loc3 = buildLocation(12L, "Dunstanburgh", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2, loc3));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        ForecastPreEvalResult okPreEval = new ForecastPreEvalResult(
                false, null, data, loc1,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.HAIKU, loc1.getTideType(), "key", null);
        ForecastPreEvalResult nullDataPreEval = new ForecastPreEvalResult(
                false, null, null, loc2,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.HAIKU, loc2.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(eq(loc1), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(okPreEval);
        when(forecastService.fetchWeatherAndTriage(eq(loc2), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(nullDataPreEval);
        when(forecastService.fetchWeatherAndTriage(eq(loc3), any(), any(), any(), any(),
                any(Boolean.class), any()))
                .thenThrow(new RuntimeException("Open-Meteo down"));

        when(evaluationService.submit(anyList(), eq(BatchTriggerSource.FORCE)))
                .thenReturn(new EvaluationHandle(null, "msgbatch_mixed", 1));

        ForceSubmitResult result = service.forceSubmit(7L,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        assertThat(result.locationsAttempted()).isEqualTo(3);
        assertThat(result.locationsIncluded()).isEqualTo(1);
        assertThat(result.locationsFailedData()).isEqualTo(2);
        assertThat(result.failedLocations()).containsExactlyInAnyOrder(
                "Lindisfarne", "Dunstanburgh");
    }

    // ── submitJfdiBatch: engine routing ───────────────────────────────────

    @Test
    @DisplayName("submitJfdiBatch builds tasks for each location × date × event "
            + "with WriteTarget.BRIEFING_CACHE via JFDI trigger")
    void submitJfdiBatch_validRegion_submitsThroughEngine() {
        RegionEntity region = buildRegion(7L, "Northumberland");
        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        loc.setLocationType(Set.of(com.gregochr.goldenhour.entity.LocationType.LANDSCAPE));
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.now(), TargetType.SUNSET,
                LocalDateTime.now(), 270, 1,
                EvaluationModel.HAIKU, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);
        when(evaluationService.submit(anyList(), eq(BatchTriggerSource.JFDI)))
                .thenReturn(new EvaluationHandle(null, "msgbatch_jfdi", 8));

        BatchSubmitResult result = service.submitJfdiBatch(List.of(7L));

        assertThat(result).isNotNull();
        assertThat(result.batchId()).isEqualTo("msgbatch_jfdi");
        assertThat(result.requestCount()).isEqualTo(8);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvaluationTask.Forecast>> taskCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(evaluationService).submit(taskCaptor.capture(), eq(BatchTriggerSource.JFDI));
        // 1 location × 4 dates (T+0..T+3) × 2 events (SUNRISE+SUNSET) = 8 tasks
        assertThat(taskCaptor.getValue()).hasSize(8);
        assertThat(taskCaptor.getValue())
                .allMatch(t -> t.writeTarget()
                        == EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        assertThat(taskCaptor.getValue())
                .allMatch(t -> t.model() == EvaluationModel.HAIKU);
    }

    @Test
    @DisplayName("submitJfdiBatch returns null when no eligible locations")
    void submitJfdiBatch_noEligibleLocations_returnsNull() {
        when(locationService.findAllEnabled()).thenReturn(List.of());

        BatchSubmitResult result = service.submitJfdiBatch(List.of(7L));

        assertThat(result).isNull();
        verifyNoInteractions(evaluationService);
    }

    @Test
    @DisplayName("submitJfdiBatch returns null when all data assembly fails")
    void submitJfdiBatch_allDataFails_returnsNull() {
        RegionEntity region = buildRegion(7L, "Northumberland");
        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        loc.setLocationType(Set.of(com.gregochr.goldenhour.entity.LocationType.LANDSCAPE));
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any()))
                .thenThrow(new RuntimeException("Open-Meteo down"));

        BatchSubmitResult result = service.submitJfdiBatch(null);

        assertThat(result).isNull();
        verify(evaluationService, never()).submit(anyList(), any());
    }

    // ── getResult: SDK polling-bypass path (Pass 2.5) ─────────────────────

    @Test
    @DisplayName("getResult returns in-progress status when batch not ended")
    void getResult_inProgress_returnsStatus() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus())
                .thenReturn(MessageBatch.ProcessingStatus.IN_PROGRESS);
        MessageBatchRequestCounts counts = mock(MessageBatchRequestCounts.class);
        when(counts.processing()).thenReturn(3L);
        when(counts.succeeded()).thenReturn(2L);
        when(counts.errored()).thenReturn(0L);
        when(status.requestCounts()).thenReturn(counts);
        when(batchService.retrieve("msgbatch_test")).thenReturn(status);

        ForceResultResponse result = service.getResult("msgbatch_test");

        assertThat(result.status()).isEqualTo("in_progress");
        assertThat(result.processing()).isEqualTo(3);
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.results()).isNull();
    }

    @Test
    @DisplayName("getResult streams results when batch has ended")
    @SuppressWarnings("unchecked")
    void getResult_ended_returnsResults() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_done")).thenReturn(status);

        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        when(response.customId()).thenReturn("fc-10-2026-04-16-SUNSET");
        MessageBatchResult batchResult = mock(MessageBatchResult.class);
        when(batchResult.isSucceeded()).thenReturn(true);
        MessageBatchSucceededResult succeeded = mock(MessageBatchSucceededResult.class);
        when(batchResult.succeeded()).thenReturn(Optional.of(succeeded));
        Message message = mock(Message.class);
        when(succeeded.message()).thenReturn(message);
        TextBlock textBlock = mock(TextBlock.class);
        when(textBlock.text()).thenReturn("{\"rating\":4,\"summary\":\"test\"}");
        ContentBlock contentBlock = mock(ContentBlock.class);
        when(contentBlock.isText()).thenReturn(true);
        when(contentBlock.asText()).thenReturn(textBlock);
        when(message.content()).thenReturn(List.of(contentBlock));
        when(response.result()).thenReturn(batchResult);

        StreamResponse<MessageBatchIndividualResponse> streamResponse =
                mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_done")).thenReturn(streamResponse);

        ForceResultResponse result = service.getResult("msgbatch_done");

        assertThat(result.status()).isEqualTo("ended");
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.totalResults()).isEqualTo(1);
        assertThat(result.results()).hasSize(1);
        assertThat(result.results().get(0).customId())
                .isEqualTo("fc-10-2026-04-16-SUNSET");
        assertThat(result.results().get(0).responsePreview())
                .contains("rating");
    }

    @Test
    @DisplayName("getResult counts errored results and records them with null preview")
    @SuppressWarnings("unchecked")
    void getResult_erroredResult_countsAndNullPreview() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_err")).thenReturn(status);

        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        when(response.customId()).thenReturn("fc-10-2026-04-16-SUNSET");
        MessageBatchResult batchResult = mock(MessageBatchResult.class);
        when(batchResult.isSucceeded()).thenReturn(false);
        when(response.result()).thenReturn(batchResult);

        StreamResponse<MessageBatchIndividualResponse> streamResponse =
                mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_err")).thenReturn(streamResponse);

        ForceResultResponse result = service.getResult("msgbatch_err");

        assertThat(result.errored()).isEqualTo(1);
        assertThat(result.totalResults()).isEqualTo(1);
        assertThat(result.results()).hasSize(1);
        assertThat(result.results().get(0).responsePreview()).isNull();
    }

    @Test
    @DisplayName("getResult truncates response preview to 500 chars")
    @SuppressWarnings("unchecked")
    void getResult_longResponse_truncatesTo500Chars() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_long")).thenReturn(status);

        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            longText.append("a");
        }

        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        when(response.customId()).thenReturn("fc-10-2026-04-16-SUNSET");
        MessageBatchResult batchResult = mock(MessageBatchResult.class);
        when(batchResult.isSucceeded()).thenReturn(true);
        MessageBatchSucceededResult succeeded = mock(MessageBatchSucceededResult.class);
        when(batchResult.succeeded()).thenReturn(Optional.of(succeeded));
        Message message = mock(Message.class);
        when(succeeded.message()).thenReturn(message);
        TextBlock textBlock = mock(TextBlock.class);
        when(textBlock.text()).thenReturn(longText.toString());
        ContentBlock contentBlock = mock(ContentBlock.class);
        when(contentBlock.isText()).thenReturn(true);
        when(contentBlock.asText()).thenReturn(textBlock);
        when(message.content()).thenReturn(List.of(contentBlock));
        when(response.result()).thenReturn(batchResult);

        StreamResponse<MessageBatchIndividualResponse> streamResponse =
                mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_long")).thenReturn(streamResponse);

        ForceResultResponse result = service.getResult("msgbatch_long");

        assertThat(result.results().get(0).responsePreview()).hasSize(500);
    }

    @Test
    @DisplayName("getResult returns empty results when stream is empty")
    @SuppressWarnings("unchecked")
    void getResult_emptyStream_zeroCounts() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_empty")).thenReturn(status);

        StreamResponse<MessageBatchIndividualResponse> streamResponse =
                mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.empty());
        when(batchService.resultsStreaming("msgbatch_empty")).thenReturn(streamResponse);

        ForceResultResponse result = service.getResult("msgbatch_empty");

        assertThat(result.succeeded()).isEqualTo(0);
        assertThat(result.errored()).isEqualTo(0);
        assertThat(result.totalResults()).isEqualTo(0);
        assertThat(result.results()).isEmpty();
    }

    @Test
    @DisplayName("getResult returns canceling status when batch is canceling")
    void getResult_canceling_returnsCanceling() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.CANCELING);
        MessageBatchRequestCounts counts = mock(MessageBatchRequestCounts.class);
        when(counts.processing()).thenReturn(0L);
        when(counts.succeeded()).thenReturn(0L);
        when(counts.errored()).thenReturn(0L);
        when(status.requestCounts()).thenReturn(counts);
        when(batchService.retrieve("msgbatch_cancel")).thenReturn(status);

        ForceResultResponse result = service.getResult("msgbatch_cancel");

        assertThat(result.status()).isEqualTo("canceling");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private RegionEntity buildRegion(Long id, String name) {
        RegionEntity region = new RegionEntity();
        region.setId(id);
        region.setName(name);
        return region;
    }

    private LocationEntity buildLocation(Long id, String name, RegionEntity region) {
        return LocationEntity.builder()
                .id(id)
                .name(name)
                .lat(55.0)
                .lon(-1.5)
                .region(region)
                .enabled(true)
                .tideType(Set.of())
                .build();
    }
}
