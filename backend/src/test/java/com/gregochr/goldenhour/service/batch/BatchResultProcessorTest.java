package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.messages.BatchService;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.CostCalculator;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.evaluation.AuroraResultHandler;
import com.gregochr.goldenhour.service.evaluation.AuroraResultHandler.AuroraBatchOutcome;
import com.gregochr.goldenhour.service.evaluation.ClaudeBatchOutcome;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler.BatchSuccess;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler.ForecastIdentity;
import com.gregochr.goldenhour.service.evaluation.ResultContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BatchResultProcessor} as the per-batch orchestrator.
 *
 * <p>This class no longer covers parsing, validation, or cache-write behaviours —
 * those moved to {@link com.gregochr.goldenhour.service.evaluation.ForecastResultHandler}
 * and {@link com.gregochr.goldenhour.service.evaluation.AuroraResultHandler} in Pass 3.2.
 * The integration test pyramid in {@code com.gregochr.goldenhour.integration} covers the
 * end-to-end byte-identical writes.
 *
 * <p>What this class does cover:
 * <ul>
 *   <li>Dispatch by {@link BatchType} (FORECAST → forecast handler, AURORA → aurora handler)</li>
 *   <li>Per-response loop wiring: handler delegation, error counting, cache-key flushing</li>
 *   <li>Stream-failure handling (mark batch FAILED, no handler interaction)</li>
 *   <li>Static helpers: {@link BatchResultProcessor#describeFailedResult},
 *       {@link BatchResultProcessor#resolveEvaluationModel}</li>
 * </ul>
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
    private LocationRepository locationRepository;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private CostCalculator costCalculator;
    @Mock
    private ForecastResultHandler forecastResultHandler;
    @Mock
    private AuroraResultHandler auroraResultHandler;

    private BatchResultProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new BatchResultProcessor(
                anthropicClient, batchRepository, locationRepository,
                jobRunService, costCalculator,
                forecastResultHandler, auroraResultHandler);
    }

    private void stubBatchService() {
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);
    }

    // ── Stream failure ───────────────────────────────────────────────────────

    @Test
    @DisplayName("FORECAST stream throws → batch marked FAILED, no handler called")
    void forecast_streamThrows_marksFailedAndSkipsHandler() {
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
        verifyNoInteractions(forecastResultHandler);
    }

    @Test
    @DisplayName("AURORA stream throws → batch marked FAILED, no handler called")
    void aurora_streamThrows_marksFailedAndSkipsHandler() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);
        when(batchService.resultsStreaming("msgbatch_fail"))
                .thenThrow(new RuntimeException("Connection reset"));

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
        verifyNoInteractions(auroraResultHandler);
    }

    // ── Unknown batch type ───────────────────────────────────────────────────

    @Test
    @DisplayName("Unknown batch type → marked FAILED, no handler called")
    void unknownBatchType_marksFailed() {
        ForecastBatchEntity batch = mock(ForecastBatchEntity.class);
        when(batch.getAnthropicBatchId()).thenReturn("msgbatch_unknown");
        when(batch.getBatchType()).thenReturn(null);

        processor.processResults(batch);

        verify(batch).setStatus(BatchStatus.FAILED);
        verifyNoInteractions(forecastResultHandler);
        verifyNoInteractions(auroraResultHandler);
    }

    // ── FORECAST: delegates per-response to ForecastResultHandler ────────────

    @Test
    @DisplayName("FORECAST: succeeded response routes to ForecastResultHandler.parseBatchResponse")
    void forecast_successfulResponse_routesToForecastHandler() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);
        LocationEntity location = buildLocationWithRegion(42L, "Castlerigg", "Lake District");
        when(locationRepository.findById(42L)).thenReturn(Optional.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-42-2026-04-07-SUNRISE", "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"X\"}");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        BriefingEvaluationResult parsed = new BriefingEvaluationResult(
                "Castlerigg", 4, 70, 65, "X");
        when(forecastResultHandler.parseBatchResponse(
                eq(location), any(ForecastIdentity.class),
                any(ClaudeBatchOutcome.class), any(ResultContext.class)))
                .thenReturn(Optional.of(new BatchSuccess(
                        "Lake District|2026-04-07|SUNRISE", parsed)));

        processor.processResults(batch);

        verify(forecastResultHandler).flushCacheKey(
                eq("Lake District|2026-04-07|SUNRISE"), eq(List.of(parsed)));
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(captor.getValue().getSucceededCount()).isEqualTo(1);
        assertThat(captor.getValue().getErroredCount()).isZero();
    }

    @Test
    @DisplayName("FORECAST: handler returns Optional.empty() → erroredCount incremented, no flush")
    void forecast_handlerEmpty_incrementsErroredAndDoesNotFlush() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);
        LocationEntity location = buildLocationWithRegion(42L, "Castlerigg", "Lake District");
        when(locationRepository.findById(42L)).thenReturn(Optional.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-42-2026-04-07-SUNRISE", "garbage-not-json");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(forecastResultHandler.parseBatchResponse(
                eq(location), any(ForecastIdentity.class),
                any(ClaudeBatchOutcome.class), any(ResultContext.class)))
                .thenReturn(Optional.empty());

        processor.processResults(batch);

        verify(forecastResultHandler, never()).flushCacheKey(any(), any());
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getErroredCount()).isEqualTo(1);
        assertThat(captor.getValue().getSucceededCount()).isZero();
        // No succeeded results → batch marked FAILED
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("FORECAST: SDK-errored response → handler not called, jobRunService.logBatchResult invoked")
    void forecast_sdkErroredResponse_logsInlineFailure() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_fail", 1, 99L);

        MessageBatchIndividualResponse response = erroredResponse(
                "fc-42-2026-04-07-SUNRISE", "overloaded_error", "busy");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(forecastResultHandler, never()).parseBatchResponse(
                any(), any(), any(), any());
        verify(jobRunService).logBatchResult(
                eq(99L), eq("msgbatch_fail"), eq("fc-42-2026-04-07-SUNRISE"),
                eq(false), eq("OVERLOADED_ERROR"),
                eq("overloaded_error"), eq("busy"),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("FORECAST: location not found → handler not called, lookup_error logged")
    void forecast_locationNotFound_logsInlineFailure() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_fail", 1, 77L);
        when(locationRepository.findById(99L)).thenReturn(Optional.empty());

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-99-2026-04-07-SUNRISE", "{\"rating\":4}");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(forecastResultHandler, never()).parseBatchResponse(
                any(), any(), any(), any());
        verify(jobRunService).logBatchResult(
                eq(77L), eq("msgbatch_fail"), eq("fc-99-2026-04-07-SUNRISE"),
                eq(false), eq("LOCATION_NOT_FOUND"),
                eq("lookup_error"), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("FORECAST: malformed customId → handler not called, parse_error logged")
    void forecast_malformedCustomId_logsInlineFailure() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_fail", 1, 88L);

        MessageBatchIndividualResponse response = succeededResponse(
                "garbage-prefix-123", "{\"rating\":4}");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(forecastResultHandler, never()).parseBatchResponse(
                any(), any(), any(), any());
        verify(jobRunService).logBatchResult(
                eq(88L), eq("msgbatch_fail"), eq("garbage-prefix-123"),
                eq(false), eq("MALFORMED_ID"),
                eq("parse_error"), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("FORECAST: aurora customId in forecast batch → handler not called")
    void forecast_auroraCustomId_logsInlineFailure() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_fail", 1, 33L);

        MessageBatchIndividualResponse response = succeededResponse(
                "au-MODERATE-2026-04-07", "{}");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(forecastResultHandler, never()).parseBatchResponse(
                any(), any(), any(), any());
        verify(jobRunService).logBatchResult(
                eq(33L), eq("msgbatch_fail"), eq("au-MODERATE-2026-04-07"),
                eq(false), eq("MALFORMED_ID"),
                eq("parse_error"),
                eq("aurora customId in forecast batch"),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("FORECAST: two responses in different cache keys → flushCacheKey called twice")
    void forecast_twoCacheKeys_flushedSeparately() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);
        LocationEntity loc1 = buildLocationWithRegion(10L, "Castlerigg", "Lake District");
        LocationEntity loc2 = buildLocationWithRegion(20L, "Bamburgh", "North East");
        when(locationRepository.findById(10L)).thenReturn(Optional.of(loc1));
        when(locationRepository.findById(20L)).thenReturn(Optional.of(loc2));

        MessageBatchIndividualResponse r1 = succeededResponse(
                "fc-10-2026-04-07-SUNRISE", "{\"rating\":4}");
        MessageBatchIndividualResponse r2 = succeededResponse(
                "fc-20-2026-04-07-SUNRISE", "{\"rating\":5}");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(r1, r2));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        BriefingEvaluationResult res1 = new BriefingEvaluationResult("Castlerigg", 4, 70, 65, "X");
        BriefingEvaluationResult res2 = new BriefingEvaluationResult("Bamburgh", 5, 80, 75, "Y");
        when(forecastResultHandler.parseBatchResponse(
                eq(loc1), any(), any(), any()))
                .thenReturn(Optional.of(new BatchSuccess(
                        "Lake District|2026-04-07|SUNRISE", res1)));
        when(forecastResultHandler.parseBatchResponse(
                eq(loc2), any(), any(), any()))
                .thenReturn(Optional.of(new BatchSuccess(
                        "North East|2026-04-07|SUNRISE", res2)));

        processor.processResults(batch);

        verify(forecastResultHandler).flushCacheKey(
                eq("Lake District|2026-04-07|SUNRISE"), eq(List.of(res1)));
        verify(forecastResultHandler).flushCacheKey(
                eq("North East|2026-04-07|SUNRISE"), eq(List.of(res2)));
    }

    // ── AURORA ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AURORA: succeeded response → AuroraResultHandler.processBatchResponse invoked")
    void aurora_successfulResponse_routesToAuroraHandler() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);

        MessageBatchIndividualResponse response = succeededResponse(
                "au-MODERATE-2026-04-07", "[{\"name\":\"X\",\"stars\":4}]");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(auroraResultHandler.processBatchResponse(
                eq(AlertLevel.MODERATE),
                any(ClaudeBatchOutcome.class), any(ResultContext.class)))
                .thenReturn(AuroraBatchOutcome.ok(7));

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(captor.getValue().getSucceededCount()).isEqualTo(7);
        assertThat(captor.getValue().getErroredCount()).isZero();
    }

    @Test
    @DisplayName("AURORA: handler returns failure → batch marked FAILED with reason")
    void aurora_handlerFailure_marksFailed() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);

        MessageBatchIndividualResponse response = succeededResponse(
                "au-STRONG-2026-04-07", "[{\"name\":\"X\",\"stars\":4}]");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(auroraResultHandler.processBatchResponse(
                eq(AlertLevel.STRONG),
                any(ClaudeBatchOutcome.class), any(ResultContext.class)))
                .thenReturn(AuroraBatchOutcome.failure("triage failed"));

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(captor.getValue().getErrorMessage()).contains("triage failed");
    }

    @Test
    @DisplayName("AURORA: errored individual response → marked FAILED, handler not invoked")
    void aurora_erroredResponse_marksFailedSkipsHandler() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);

        MessageBatchIndividualResponse response = erroredResponse(
                "au-MODERATE-2026-04-07", "overloaded_error", "busy");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(captor.getValue().getErrorMessage()).contains("overloaded_error");
        verifyNoInteractions(auroraResultHandler);
    }

    // ── Static helpers ───────────────────────────────────────────────────────

    @Test
    @DisplayName("resolveEvaluationModel maps known model IDs correctly")
    void resolveEvaluationModel_knownModels() {
        assertThat(BatchResultProcessor.resolveEvaluationModel("claude-sonnet-4-6"))
                .isEqualTo(EvaluationModel.SONNET);
        assertThat(BatchResultProcessor.resolveEvaluationModel("claude-haiku-4-5-20251001"))
                .isEqualTo(EvaluationModel.HAIKU);
        assertThat(BatchResultProcessor.resolveEvaluationModel("claude-opus-4-6"))
                .isEqualTo(EvaluationModel.OPUS);
        assertThat(BatchResultProcessor.resolveEvaluationModel(null))
                .isEqualTo(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("resolveEvaluationModel falls back to Sonnet for unknown model strings")
    void resolveEvaluationModel_unknownModel_fallsBackToSonnet() {
        assertThat(BatchResultProcessor.resolveEvaluationModel("claude-future-99"))
                .isEqualTo(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("describeFailedResult returns 'expired' for expired responses")
    void describeFailedResult_expired() {
        var result = mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        when(result.isExpired()).thenReturn(true);

        String[] detail = BatchResultProcessor.describeFailedResult(result);

        assertThat(detail[0]).isEqualTo("expired");
        assertThat(detail[1]).isEqualTo("request expired before processing");
    }

    @Test
    @DisplayName("describeFailedResult returns 'canceled' for canceled responses")
    void describeFailedResult_canceled() {
        var result = mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        when(result.isCanceled()).thenReturn(true);

        String[] detail = BatchResultProcessor.describeFailedResult(result);

        assertThat(detail[0]).isEqualTo("canceled");
    }

    @Test
    @DisplayName("describeFailedResult returns 'unknown' for unrecognised result")
    void describeFailedResult_unknown() {
        var result = mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        // All predicates default to false

        String[] detail = BatchResultProcessor.describeFailedResult(result);

        assertThat(detail[0]).isEqualTo("unknown");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ForecastBatchEntity buildBatch(BatchType type) {
        return new ForecastBatchEntity("msgbatch_fail", type, 1,
                Instant.now().plusSeconds(86400));
    }

    private ForecastBatchEntity buildBatchWithJobRun(BatchType type, String batchId,
            int requestCount, long jobRunId) {
        ForecastBatchEntity batch = new ForecastBatchEntity(batchId, type, requestCount,
                Instant.now().plusSeconds(86400));
        batch.setJobRunId(jobRunId);
        return batch;
    }

    private LocationEntity buildLocationWithRegion(long id, String name, String regionName) {
        LocationEntity location = new LocationEntity();
        location.setId(id);
        location.setName(name);
        RegionEntity region = new RegionEntity();
        region.setName(regionName);
        location.setRegion(region);
        return location;
    }

    private MessageBatchIndividualResponse succeededResponse(String customId, String text) {
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
        com.anthropic.models.messages.Usage usage =
                mock(com.anthropic.models.messages.Usage.class);

        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn(customId);
        when(result.isSucceeded()).thenReturn(true);
        when(result.succeeded()).thenReturn(Optional.of(succeeded));
        when(succeeded.message()).thenReturn(message);
        when(message.content()).thenReturn(List.of(contentBlock));
        when(message.usage()).thenReturn(usage);
        when(message.model())
                .thenReturn(com.anthropic.models.messages.Model.of("claude-sonnet-4-6"));
        when(contentBlock.isText()).thenReturn(true);
        when(contentBlock.asText()).thenReturn(textBlock);
        when(textBlock.text()).thenReturn(text);
        when(usage.inputTokens()).thenReturn(500L);
        when(usage.outputTokens()).thenReturn(200L);
        when(usage.cacheReadInputTokens()).thenReturn(Optional.of(1000L));
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.of(0L));

        return response;
    }

    private MessageBatchIndividualResponse erroredResponse(String customId,
            String errorType, String errorMessage) {
        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        com.anthropic.models.messages.batches.MessageBatchResult result =
                mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        com.anthropic.models.messages.batches.MessageBatchErroredResult erroredResult =
                mock(com.anthropic.models.messages.batches.MessageBatchErroredResult.class);
        com.anthropic.models.ErrorResponse errorResponse =
                mock(com.anthropic.models.ErrorResponse.class);
        com.anthropic.models.ErrorObject errorObject =
                mock(com.anthropic.models.ErrorObject.class);

        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn(customId);
        when(result.isSucceeded()).thenReturn(false);
        when(result.isErrored()).thenReturn(true);
        when(result.asErrored()).thenReturn(erroredResult);
        when(erroredResult.error()).thenReturn(errorResponse);
        when(errorResponse.error()).thenReturn(errorObject);

        if ("overloaded_error".equals(errorType)) {
            var typed = mock(com.anthropic.models.OverloadedError.class);
            when(errorObject.isOverloadedError()).thenReturn(true);
            when(errorObject.asOverloadedError()).thenReturn(typed);
            when(typed.message()).thenReturn(errorMessage);
        } else if ("rate_limit_error".equals(errorType)) {
            var typed = mock(com.anthropic.models.RateLimitError.class);
            when(errorObject.isRateLimitError()).thenReturn(true);
            when(errorObject.asRateLimitError()).thenReturn(typed);
            when(typed.message()).thenReturn(errorMessage);
        }

        return response;
    }
}
