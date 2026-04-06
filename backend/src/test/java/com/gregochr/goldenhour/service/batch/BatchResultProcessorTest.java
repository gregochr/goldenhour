package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.messages.BatchService;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.ClaudeAuroraInterpreter;
import com.gregochr.goldenhour.service.aurora.WeatherTriageService;
import com.gregochr.goldenhour.service.evaluation.ClaudeEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BatchResultProcessor}.
 */
@ExtendWith(MockitoExtension.class)
class BatchResultProcessorTest {

    @Mock
    private AnthropicClient anthropicClient;
    @Mock
    private MessageService messageService;
    @Mock
    private BatchService batchService;
    @Mock
    private ForecastBatchRepository batchRepository;
    @Mock
    private BriefingEvaluationService briefingEvaluationService;
    @Mock
    private Map<EvaluationModel, EvaluationStrategy> evaluationStrategies;
    @Mock
    private ModelSelectionService modelSelectionService;
    @Mock
    private ClaudeAuroraInterpreter claudeAuroraInterpreter;
    @Mock
    private AuroraStateCache auroraStateCache;
    @Mock
    private WeatherTriageService weatherTriageService;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private AuroraProperties auroraProperties;
    @Mock
    private NoaaSwpcClient noaaSwpcClient;
    @Mock
    private ObjectMapper objectMapper;

    private BatchResultProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new BatchResultProcessor(
                anthropicClient, batchRepository, briefingEvaluationService,
                evaluationStrategies, modelSelectionService, claudeAuroraInterpreter,
                auroraStateCache, weatherTriageService, locationRepository,
                auroraProperties, noaaSwpcClient, objectMapper);
    }

    private void stubBatchService() {
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);
    }

    @Test
    @DisplayName("processResults marks FAILED when stream throws exception")
    void processResults_streamThrows_marksFailed() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);
        when(batchService.resultsStreaming("msgbatch_fail"))
                .thenThrow(new RuntimeException("Network error"));

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(captor.getValue().getErrorMessage()).contains("Network error");
    }

    @Test
    @DisplayName("processResults for FORECAST marks FAILED when all responses errored")
    void processResults_allErrored_marksFailed() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        com.anthropic.models.messages.batches.MessageBatchResult result =
                mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn("North East|2026-04-07|SUNRISE|Durham UK");
        when(result.isSucceeded()).thenReturn(false);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(captor.getValue().getSucceededCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("processResults for FORECAST writes results to evaluation cache")
    void processResults_forecastBatch_writesResultsToCache() throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        String responseText = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":68,"
                + "\"summary\":\"Good conditions\"}";

        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        com.anthropic.models.messages.batches.MessageBatchResult result =
                mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        com.anthropic.models.messages.batches.MessageBatchSucceededResult succeeded =
                mock(com.anthropic.models.messages.batches.MessageBatchSucceededResult.class);
        com.anthropic.models.messages.Message message =
                mock(com.anthropic.models.messages.Message.class);
        com.anthropic.models.messages.TextBlock textBlock =
                mock(com.anthropic.models.messages.TextBlock.class);
        com.anthropic.models.messages.ContentBlock contentBlock =
                mock(com.anthropic.models.messages.ContentBlock.class);

        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn("North East|2026-04-07|SUNRISE|Durham UK");
        when(result.isSucceeded()).thenReturn(true);
        when(result.succeeded()).thenReturn(java.util.Optional.of(succeeded));
        when(succeeded.message()).thenReturn(message);
        when(message.content()).thenReturn(java.util.List.of(contentBlock));
        when(contentBlock.isText()).thenReturn(true);
        when(contentBlock.asText()).thenReturn(textBlock);
        when(textBlock.text()).thenReturn(responseText);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(any())).thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(responseText, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(
                        4, 72, 68, "Good conditions"));

        processor.processResults(batch);

        ArgumentCaptor<String> cacheKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(briefingEvaluationService).writeFromBatch(
                cacheKeyCaptor.capture(), any());
        assertThat(cacheKeyCaptor.getValue()).isEqualTo("North East|2026-04-07|SUNRISE");

        ArgumentCaptor<ForecastBatchEntity> entityCaptor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(entityCaptor.getValue().getSucceededCount()).isEqualTo(1);
        assertThat(entityCaptor.getValue().getErroredCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("processResults marks FAILED for unknown batch type")
    void processResults_unknownBatchType_marksFailed() {
        ForecastBatchEntity batch = mock(ForecastBatchEntity.class);
        when(batch.getBatchType()).thenReturn(null);

        processor.processResults(batch);

        verify(batch).setStatus(BatchStatus.FAILED);
        verify(batchRepository).save(batch);
    }

    @Test
    @DisplayName("processResults for AURORA marks FAILED when no Bortle locations")
    void processResults_auroraBatch_noBortleLocations_marksFailed() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);

        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        com.anthropic.models.messages.batches.MessageBatchResult result =
                mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        com.anthropic.models.messages.batches.MessageBatchSucceededResult succeeded =
                mock(com.anthropic.models.messages.batches.MessageBatchSucceededResult.class);
        com.anthropic.models.messages.Message message =
                mock(com.anthropic.models.messages.Message.class);
        com.anthropic.models.messages.TextBlock textBlock =
                mock(com.anthropic.models.messages.TextBlock.class);
        com.anthropic.models.messages.ContentBlock contentBlock =
                mock(com.anthropic.models.messages.ContentBlock.class);

        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn("aurora|MODERATE");
        when(result.isSucceeded()).thenReturn(true);
        when(result.succeeded()).thenReturn(java.util.Optional.of(succeeded));
        when(succeeded.message()).thenReturn(message);
        when(message.content()).thenReturn(java.util.List.of(contentBlock));
        when(contentBlock.isText()).thenReturn(true);
        when(contentBlock.asText()).thenReturn(textBlock);
        when(textBlock.text()).thenReturn("[{\"name\":\"Dark Sky\",\"stars\":3}]");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        AuroraProperties.BortleThreshold threshold = mock(AuroraProperties.BortleThreshold.class);
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        when(threshold.getModerate()).thenReturn(4);
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(java.util.List.of());

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("processResults for AURORA writes scores to state cache on success")
    void processResults_auroraBatch_success() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);

        String rawResponse = "[{\"name\":\"Dark Sky\",\"stars\":3}]";
        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        com.anthropic.models.messages.batches.MessageBatchResult result =
                mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        com.anthropic.models.messages.batches.MessageBatchSucceededResult succeeded =
                mock(com.anthropic.models.messages.batches.MessageBatchSucceededResult.class);
        com.anthropic.models.messages.Message message =
                mock(com.anthropic.models.messages.Message.class);
        com.anthropic.models.messages.TextBlock textBlock =
                mock(com.anthropic.models.messages.TextBlock.class);
        com.anthropic.models.messages.ContentBlock contentBlock =
                mock(com.anthropic.models.messages.ContentBlock.class);

        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn("aurora|MODERATE");
        when(result.isSucceeded()).thenReturn(true);
        when(result.succeeded()).thenReturn(java.util.Optional.of(succeeded));
        when(succeeded.message()).thenReturn(message);
        when(message.content()).thenReturn(java.util.List.of(contentBlock));
        when(contentBlock.isText()).thenReturn(true);
        when(contentBlock.asText()).thenReturn(textBlock);
        when(textBlock.text()).thenReturn(rawResponse);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        AuroraProperties.BortleThreshold threshold = mock(AuroraProperties.BortleThreshold.class);
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        when(threshold.getModerate()).thenReturn(4);

        LocationEntity loc = new LocationEntity();
        loc.setName("Dark Sky Site");
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(java.util.List.of(loc));

        WeatherTriageService.TriageResult triage = new WeatherTriageService.TriageResult(
                java.util.List.of(loc), java.util.List.of(), java.util.Map.of());
        when(weatherTriageService.triage(java.util.List.of(loc))).thenReturn(triage);

        AuroraForecastScore score = mock(AuroraForecastScore.class);
        when(claudeAuroraInterpreter.parseBatchResponse(any(), any(), any(), any()))
                .thenReturn(java.util.List.of(score));

        processor.processResults(batch);

        verify(auroraStateCache).updateScores(any());
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(captor.getValue().getSucceededCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("processResults for AURORA marks FAILED when triage throws")
    void processResults_auroraBatch_triageFails_marksFailed() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);

        String rawResponse = "[{\"name\":\"Dark Sky\",\"stars\":3}]";
        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        com.anthropic.models.messages.batches.MessageBatchResult result =
                mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        com.anthropic.models.messages.batches.MessageBatchSucceededResult succeeded =
                mock(com.anthropic.models.messages.batches.MessageBatchSucceededResult.class);
        com.anthropic.models.messages.Message message =
                mock(com.anthropic.models.messages.Message.class);
        com.anthropic.models.messages.TextBlock textBlock =
                mock(com.anthropic.models.messages.TextBlock.class);
        com.anthropic.models.messages.ContentBlock contentBlock =
                mock(com.anthropic.models.messages.ContentBlock.class);

        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn("aurora|MODERATE");
        when(result.isSucceeded()).thenReturn(true);
        when(result.succeeded()).thenReturn(java.util.Optional.of(succeeded));
        when(succeeded.message()).thenReturn(message);
        when(message.content()).thenReturn(java.util.List.of(contentBlock));
        when(contentBlock.isText()).thenReturn(true);
        when(contentBlock.asText()).thenReturn(textBlock);
        when(textBlock.text()).thenReturn(rawResponse);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        AuroraProperties.BortleThreshold threshold = mock(AuroraProperties.BortleThreshold.class);
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        when(threshold.getModerate()).thenReturn(4);

        LocationEntity loc = new LocationEntity();
        loc.setName("Dark Sky Site");
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(java.util.List.of(loc));

        when(weatherTriageService.triage(java.util.List.of(loc)))
                .thenThrow(new RuntimeException("Weather API down"));

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    private ForecastBatchEntity buildBatch(BatchType type) {
        return new ForecastBatchEntity("msgbatch_fail", type, 1,
                Instant.now().plusSeconds(86400));
    }
}
