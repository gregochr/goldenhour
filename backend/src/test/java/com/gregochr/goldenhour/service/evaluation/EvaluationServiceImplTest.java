package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.aurora.ClaudeAuroraInterpreter;
import com.gregochr.goldenhour.service.aurora.TriggerType;
import com.gregochr.goldenhour.service.batch.BatchSubmissionService;
import com.gregochr.goldenhour.service.batch.BatchSubmitResult;
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EvaluationServiceImpl}.
 *
 * <p>Mocks the two Anthropic transports ({@link BatchSubmissionService},
 * {@link com.gregochr.goldenhour.service.evaluation.AnthropicApiClient}) and the per-task
 * handlers; these are tested standalone in their own test classes.
 *
 * <p>The synchronous-path tests for forecast are limited because the production sync path
 * builds a real {@link com.anthropic.models.messages.MessageCreateParams} that's awkward to
 * mock without a live Anthropic client. We cover the dispatch logic and error-classification
 * through the aurora task type, which has a simpler request shape.
 */
@ExtendWith(MockitoExtension.class)
class EvaluationServiceImplTest {

    private static final LocalDate DATE = LocalDate.of(2026, 4, 16);
    private static final AtmosphericData ATMOSPHERIC = TestAtmosphericData.defaults();
    private static final SpaceWeatherData SPACE_WEATHER = new SpaceWeatherData(
            List.of(), List.of(), null, List.of(), List.of());

    @Mock
    private BatchSubmissionService batchSubmissionService;
    @Mock
    private BatchRequestFactory batchRequestFactory;
    @Mock
    private AnthropicApiClient anthropicApiClient;
    @Mock
    private ClaudeAuroraInterpreter claudeAuroraInterpreter;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private ForecastResultHandler forecastResultHandler;
    @Mock
    private AuroraResultHandler auroraResultHandler;

    private EvaluationServiceImpl service;

    @BeforeEach
    void setUp() {
        when(forecastResultHandler.taskType())
                .thenReturn(EvaluationTask.Forecast.class);
        when(auroraResultHandler.taskType())
                .thenReturn(EvaluationTask.Aurora.class);
        service = new EvaluationServiceImpl(
                batchSubmissionService, batchRequestFactory, anthropicApiClient,
                claudeAuroraInterpreter, jobRunService,
                List.of(forecastResultHandler, auroraResultHandler));
    }

    // ── submit() ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("submit: empty list → returns EvaluationHandle.empty(), no submission")
    void submit_emptyList_returnsEmptyHandle() {
        EvaluationHandle handle = service.submit(List.of(), BatchTriggerSource.SCHEDULED);

        assertThat(handle).isEqualTo(EvaluationHandle.empty());
        verifyNoInteractions(batchSubmissionService);
        verifyNoInteractions(batchRequestFactory);
    }

    @Test
    @DisplayName("submit: null list → returns EvaluationHandle.empty()")
    void submit_nullList_returnsEmptyHandle() {
        EvaluationHandle handle = service.submit(null, BatchTriggerSource.SCHEDULED);

        assertThat(handle).isEqualTo(EvaluationHandle.empty());
        verifyNoInteractions(batchSubmissionService);
    }

    @Test
    @DisplayName("submit: null trigger → throws NullPointerException")
    void submit_nullTrigger_throws() {
        EvaluationTask task = forecastTask(42L, "Castlerigg", "Lake District");
        assertThatNullPointerException().isThrownBy(() -> service.submit(List.of(task), null));
    }

    @Test
    @DisplayName("submit: forecast tasks → builds requests via factory and submits FORECAST type")
    void submit_forecastTasks_routesThroughForecastFactory() {
        EvaluationTask.Forecast t1 = forecastTask(42L, "Castlerigg", "Lake District");
        EvaluationTask.Forecast t2 = forecastTask(43L, "Bamburgh", "North East");
        BatchCreateParams.Request req1 = mock(BatchCreateParams.Request.class);
        BatchCreateParams.Request req2 = mock(BatchCreateParams.Request.class);
        when(batchRequestFactory.buildForecastRequest(
                eq("fc-42-2026-04-16-SUNRISE"), eq(EvaluationModel.HAIKU),
                eq(t1.data()), eq(EvaluationModel.HAIKU.getMaxTokens())))
                .thenReturn(req1);
        when(batchRequestFactory.buildForecastRequest(
                eq("fc-43-2026-04-16-SUNRISE"), eq(EvaluationModel.HAIKU),
                eq(t2.data()), eq(EvaluationModel.HAIKU.getMaxTokens())))
                .thenReturn(req2);
        when(batchSubmissionService.submit(
                any(), eq(BatchType.FORECAST), eq(BatchTriggerSource.SCHEDULED), anyString()))
                .thenReturn(new BatchSubmitResult("msgbatch_x", 2));

        EvaluationHandle handle = service.submit(
                List.of(t1, t2), BatchTriggerSource.SCHEDULED);

        assertThat(handle.batchId()).isEqualTo("msgbatch_x");
        assertThat(handle.submittedCount()).isEqualTo(2);
        ArgumentCaptor<List<BatchCreateParams.Request>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(batchSubmissionService).submit(
                captor.capture(), eq(BatchType.FORECAST),
                eq(BatchTriggerSource.SCHEDULED), anyString());
        assertThat(captor.getValue()).containsExactly(req1, req2);
    }

    @Test
    @DisplayName("submit: aurora tasks → uses ClaudeAuroraInterpreter.buildUserMessage and AURORA type")
    void submit_auroraTasks_routesThroughAuroraInterpreter() {
        EvaluationTask.Aurora task = auroraTask(AlertLevel.MODERATE);
        when(claudeAuroraInterpreter.buildUserMessage(
                eq(AlertLevel.MODERATE), eq(task.viableLocations()),
                eq(task.cloudByLocation()), eq(SPACE_WEATHER),
                eq(TriggerType.REALTIME), eq(null)))
                .thenReturn("user-message");
        when(batchSubmissionService.submit(
                any(), eq(BatchType.AURORA), eq(BatchTriggerSource.SCHEDULED), anyString()))
                .thenReturn(new BatchSubmitResult("msgbatch_aurora", 1));

        EvaluationHandle handle = service.submit(
                List.of(task), BatchTriggerSource.SCHEDULED);

        assertThat(handle.batchId()).isEqualTo("msgbatch_aurora");
        verify(batchSubmissionService).submit(
                any(), eq(BatchType.AURORA),
                eq(BatchTriggerSource.SCHEDULED), anyString());
    }

    @Test
    @DisplayName("submit: mixed forecast + aurora tasks → IllegalArgumentException")
    void submit_mixedTaskTypes_throws() {
        EvaluationTask forecast = forecastTask(42L, "Castlerigg", "Lake District");
        EvaluationTask aurora = auroraTask(AlertLevel.MODERATE);

        assertThatIllegalArgumentException().isThrownBy(() -> service.submit(
                List.of(forecast, aurora), BatchTriggerSource.SCHEDULED))
                .withMessageContaining("Mixed-type submit");
    }

    @Test
    @DisplayName("submit: BatchSubmissionService returns null → returns EvaluationHandle.empty()")
    void submit_submissionFailureReturnsNull_returnsEmptyHandle() {
        EvaluationTask.Forecast task = forecastTask(42L, "Castlerigg", "Lake District");
        when(batchRequestFactory.buildForecastRequest(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(mock(BatchCreateParams.Request.class));
        when(batchSubmissionService.submit(
                any(), any(), any(), anyString())).thenReturn(null);

        EvaluationHandle handle = service.submit(List.of(task), BatchTriggerSource.SCHEDULED);

        assertThat(handle).isEqualTo(EvaluationHandle.empty());
    }

    // ── evaluateNow() ────────────────────────────────────────────────────────

    @Test
    @DisplayName("evaluateNow: aurora task delegates to AuroraResultHandler.handleSyncResult")
    void evaluateNow_auroraTask_delegatesToAuroraHandler() {
        EvaluationTask.Aurora task = auroraTask(AlertLevel.MODERATE);
        when(claudeAuroraInterpreter.buildUserMessage(
                any(), any(), any(), any(), any(), any()))
                .thenReturn("user-message");
        // Force an Anthropic call failure so we don't have to mock the SDK Message:
        when(anthropicApiClient.createMessage(any()))
                .thenThrow(new RuntimeException("Anthropic outage"));
        when(auroraResultHandler.handleSyncResult(eq(task),
                any(ClaudeSyncOutcome.class), any(ResultContext.class)))
                .thenReturn(new EvaluationResult.Errored("RuntimeException", "Anthropic outage"));

        EvaluationResult result = service.evaluateNow(task, BatchTriggerSource.SCHEDULED);

        assertThat(result).isInstanceOf(EvaluationResult.Errored.class);
        ArgumentCaptor<ClaudeSyncOutcome> outcomeCaptor =
                ArgumentCaptor.forClass(ClaudeSyncOutcome.class);
        verify(auroraResultHandler).handleSyncResult(
                eq(task), outcomeCaptor.capture(), any(ResultContext.class));
        assertThat(outcomeCaptor.getValue().succeeded()).isFalse();
        assertThat(outcomeCaptor.getValue().errorMessage()).contains("Anthropic outage");
    }

    @Test
    @DisplayName("evaluateNow: forecast task delegates to ForecastResultHandler.handleSyncResult")
    void evaluateNow_forecastTask_delegatesToForecastHandler() {
        EvaluationTask.Forecast task = forecastTask(42L, "Castlerigg", "Lake District");
        // No PromptBuilder stub — the path will throw early when selectBuilder returns
        // null, which is exactly the failure mode that flows into the handler's error
        // branch via ClaudeSyncOutcome.failure(...).
        when(forecastResultHandler.handleSyncResult(eq(task),
                any(ClaudeSyncOutcome.class), any(ResultContext.class)))
                .thenReturn(new EvaluationResult.Errored("NullPointerException", "x"));

        EvaluationResult result = service.evaluateNow(task, BatchTriggerSource.ADMIN);

        assertThat(result).isInstanceOf(EvaluationResult.Errored.class);
        ArgumentCaptor<ClaudeSyncOutcome> outcomeCaptor =
                ArgumentCaptor.forClass(ClaudeSyncOutcome.class);
        verify(forecastResultHandler).handleSyncResult(
                eq(task), outcomeCaptor.capture(), any(ResultContext.class));
        assertThat(outcomeCaptor.getValue().succeeded()).isFalse();
    }

    @Test
    @DisplayName("evaluateNow: null task → NPE")
    void evaluateNow_nullTask_throws() {
        assertThatNullPointerException().isThrownBy(() ->
                service.evaluateNow(null, BatchTriggerSource.SCHEDULED));
    }

    @Test
    @DisplayName("evaluateNow: null trigger → NPE")
    void evaluateNow_nullTrigger_throws() {
        EvaluationTask task = auroraTask(AlertLevel.MODERATE);
        assertThatNullPointerException().isThrownBy(() ->
                service.evaluateNow(task, null));
    }

    @Test
    @DisplayName("evaluateNow: forecast success path drains SDK Message into ClaudeSyncOutcome.success")
    void evaluateNow_forecastSuccess_drainsResponseIntoOutcome() {
        EvaluationTask.Forecast task = forecastTask(42L, "Castlerigg", "Lake District");
        // selectBuilder returns a real PromptBuilder so getSystemPrompt() / buildOutputConfig()
        // produce SDK-valid values without us reconstructing them by hand.
        when(batchRequestFactory.selectBuilder(eq(task.data())))
                .thenReturn(new PromptBuilder());
        Message message = mockMessageWithText("{\"rating\":4}", 500L, 200L, 0L, 1000L);
        when(anthropicApiClient.createMessage(any())).thenReturn(message);
        when(forecastResultHandler.handleSyncResult(eq(task),
                any(ClaudeSyncOutcome.class), any(ResultContext.class)))
                .thenReturn(new EvaluationResult.Scored("ok"));

        EvaluationResult result = service.evaluateNow(task, BatchTriggerSource.SCHEDULED);

        assertThat(result).isInstanceOf(EvaluationResult.Scored.class);
        ArgumentCaptor<ClaudeSyncOutcome> outcomeCaptor =
                ArgumentCaptor.forClass(ClaudeSyncOutcome.class);
        verify(forecastResultHandler).handleSyncResult(
                eq(task), outcomeCaptor.capture(), any(ResultContext.class));
        ClaudeSyncOutcome outcome = outcomeCaptor.getValue();
        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.rawText()).isEqualTo("{\"rating\":4}");
        assertThat(outcome.tokenUsage().inputTokens()).isEqualTo(500L);
        assertThat(outcome.tokenUsage().outputTokens()).isEqualTo(200L);
        assertThat(outcome.tokenUsage().cacheReadInputTokens()).isEqualTo(1000L);
        assertThat(outcome.tokenUsage().cacheCreationInputTokens()).isZero();
        assertThat(outcome.model()).isEqualTo(EvaluationModel.HAIKU);
    }

    @Test
    @DisplayName("evaluateNow: aurora success path drains SDK Message into ClaudeSyncOutcome.success")
    void evaluateNow_auroraSuccess_drainsResponseIntoOutcome() {
        EvaluationTask.Aurora task = auroraTask(AlertLevel.MODERATE);
        when(claudeAuroraInterpreter.buildUserMessage(
                any(), any(), any(), any(), any(), any())).thenReturn("user-message");
        Message message = mockMessageWithText("[{\"name\":\"X\",\"stars\":4}]",
                400L, 150L, 50L, 800L);
        when(anthropicApiClient.createMessage(any())).thenReturn(message);
        when(auroraResultHandler.handleSyncResult(eq(task),
                any(ClaudeSyncOutcome.class), any(ResultContext.class)))
                .thenReturn(new EvaluationResult.Scored(List.of()));

        EvaluationResult result = service.evaluateNow(task, BatchTriggerSource.ADMIN);

        assertThat(result).isInstanceOf(EvaluationResult.Scored.class);
        ArgumentCaptor<ClaudeSyncOutcome> outcomeCaptor =
                ArgumentCaptor.forClass(ClaudeSyncOutcome.class);
        verify(auroraResultHandler).handleSyncResult(
                eq(task), outcomeCaptor.capture(), any(ResultContext.class));
        ClaudeSyncOutcome outcome = outcomeCaptor.getValue();
        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.rawText()).isEqualTo("[{\"name\":\"X\",\"stars\":4}]");
        assertThat(outcome.tokenUsage().cacheCreationInputTokens()).isEqualTo(50L);
        assertThat(outcome.tokenUsage().cacheReadInputTokens()).isEqualTo(800L);
    }

    @Test
    @DisplayName("evaluateNow: AnthropicServiceException → errorType formatted as anthropic_<status>")
    void evaluateNow_anthropicServiceException_classifiedWithStatusCode() {
        EvaluationTask.Aurora task = auroraTask(AlertLevel.MODERATE);
        when(claudeAuroraInterpreter.buildUserMessage(
                any(), any(), any(), any(), any(), any())).thenReturn("user-message");
        AnthropicServiceException svc = mock(AnthropicServiceException.class);
        when(svc.statusCode()).thenReturn(529);
        when(svc.getMessage()).thenReturn("overloaded");
        when(anthropicApiClient.createMessage(any())).thenThrow(svc);
        when(auroraResultHandler.handleSyncResult(any(), any(), any()))
                .thenReturn(new EvaluationResult.Errored("anthropic_529", "overloaded"));

        service.evaluateNow(task, BatchTriggerSource.SCHEDULED);

        ArgumentCaptor<ClaudeSyncOutcome> outcomeCaptor =
                ArgumentCaptor.forClass(ClaudeSyncOutcome.class);
        verify(auroraResultHandler).handleSyncResult(
                eq(task), outcomeCaptor.capture(), any(ResultContext.class));
        assertThat(outcomeCaptor.getValue().errorType()).isEqualTo("anthropic_529");
        assertThat(outcomeCaptor.getValue().errorMessage()).isEqualTo("overloaded");
    }

    @Test
    @DisplayName("evaluateNow: jobRunService.startRun throws → swallowed, handler still invoked")
    void evaluateNow_jobRunStartFails_handlerStillInvoked() {
        EvaluationTask.Aurora task = auroraTask(AlertLevel.MODERATE);
        when(jobRunService.startRun(any(), eq(false), any()))
                .thenThrow(new RuntimeException("DB down"));
        when(claudeAuroraInterpreter.buildUserMessage(
                any(), any(), any(), any(), any(), any())).thenReturn("user-message");
        when(anthropicApiClient.createMessage(any()))
                .thenThrow(new RuntimeException("Anthropic outage"));
        when(auroraResultHandler.handleSyncResult(any(), any(), any()))
                .thenReturn(new EvaluationResult.Errored("RuntimeException", "Anthropic outage"));

        EvaluationResult result = service.evaluateNow(task, BatchTriggerSource.SCHEDULED);

        assertThat(result).isInstanceOf(EvaluationResult.Errored.class);
        verify(auroraResultHandler).handleSyncResult(eq(task), any(), any());
        // No completeRun call because jobRun is null after start failure
        verify(jobRunService, org.mockito.Mockito.never())
                .completeRun(any(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("evaluateNow: jobRunService.completeRun throws → swallowed, result still returned")
    void evaluateNow_jobRunCompleteFails_resultStillReturned() {
        EvaluationTask.Aurora task = auroraTask(AlertLevel.MODERATE);
        com.gregochr.goldenhour.entity.JobRunEntity jobRun =
                mock(com.gregochr.goldenhour.entity.JobRunEntity.class);
        when(jobRun.getId()).thenReturn(7L);
        when(jobRunService.startRun(any(), eq(false), any())).thenReturn(jobRun);
        org.mockito.Mockito.doThrow(new RuntimeException("DB write failure"))
                .when(jobRunService).completeRun(eq(jobRun),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt());
        when(claudeAuroraInterpreter.buildUserMessage(
                any(), any(), any(), any(), any(), any())).thenReturn("user-message");
        when(anthropicApiClient.createMessage(any()))
                .thenThrow(new RuntimeException("err"));
        EvaluationResult.Errored handlerResult =
                new EvaluationResult.Errored("RuntimeException", "err");
        when(auroraResultHandler.handleSyncResult(any(), any(), any()))
                .thenReturn(handlerResult);

        EvaluationResult result = service.evaluateNow(task, BatchTriggerSource.SCHEDULED);

        assertThat(result).isSameAs(handlerResult);
        verify(jobRunService).completeRun(eq(jobRun), eq(0), eq(1));
    }

    @Test
    @DisplayName("evaluateNow: forecast handler missing → IllegalStateException")
    void evaluateNow_forecastHandlerMissing_throws() {
        EvaluationServiceImpl noForecastHandler = new EvaluationServiceImpl(
                batchSubmissionService, batchRequestFactory, anthropicApiClient,
                claudeAuroraInterpreter, jobRunService,
                List.of(auroraResultHandler));
        EvaluationTask.Forecast task = forecastTask(42L, "Castlerigg", "Lake District");

        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> noForecastHandler.evaluateNow(task, BatchTriggerSource.SCHEDULED))
                .withMessageContaining("ForecastResultHandler");
    }

    @Test
    @DisplayName("evaluateNow: aurora handler missing → IllegalStateException")
    void evaluateNow_auroraHandlerMissing_throws() {
        EvaluationServiceImpl noAuroraHandler = new EvaluationServiceImpl(
                batchSubmissionService, batchRequestFactory, anthropicApiClient,
                claudeAuroraInterpreter, jobRunService,
                List.of(forecastResultHandler));
        EvaluationTask.Aurora task = auroraTask(AlertLevel.MODERATE);

        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> noAuroraHandler.evaluateNow(task, BatchTriggerSource.SCHEDULED))
                .withMessageContaining("AuroraResultHandler");
    }

    @Test
    @DisplayName("evaluateNow: starts and completes job_run via JobRunService")
    void evaluateNow_tracksJobRun() {
        EvaluationTask.Aurora task = auroraTask(AlertLevel.MODERATE);
        com.gregochr.goldenhour.entity.JobRunEntity jobRun =
                mock(com.gregochr.goldenhour.entity.JobRunEntity.class);
        when(jobRun.getId()).thenReturn(101L);
        when(jobRunService.startRun(any(), eq(false), any())).thenReturn(jobRun);
        when(claudeAuroraInterpreter.buildUserMessage(
                any(), any(), any(), any(), any(), any())).thenReturn("user-message");
        when(anthropicApiClient.createMessage(any()))
                .thenThrow(new RuntimeException("err"));
        when(auroraResultHandler.handleSyncResult(any(), any(), any()))
                .thenReturn(new EvaluationResult.Errored("err", "err"));

        service.evaluateNow(task, BatchTriggerSource.SCHEDULED);

        verify(jobRunService).startRun(any(), eq(false), eq(EvaluationModel.HAIKU));
        verify(jobRunService).completeRun(eq(jobRun), eq(0), eq(1));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static <T> T mock(Class<T> type) {
        return org.mockito.Mockito.mock(type);
    }

    private static Message mockMessageWithText(String text, long input, long output,
            long cacheCreate, long cacheRead) {
        Message message = mock(Message.class);
        ContentBlock block = mock(ContentBlock.class);
        TextBlock textBlock = mock(TextBlock.class);
        Usage usage = mock(Usage.class);
        when(message.content()).thenReturn(List.of(block));
        when(block.isText()).thenReturn(true);
        when(block.asText()).thenReturn(textBlock);
        when(textBlock.text()).thenReturn(text);
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(input);
        when(usage.outputTokens()).thenReturn(output);
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.of(cacheCreate));
        when(usage.cacheReadInputTokens()).thenReturn(Optional.of(cacheRead));
        return message;
    }

    private EvaluationTask.Forecast forecastTask(long id, String name, String regionName) {
        LocationEntity loc = new LocationEntity();
        loc.setId(id);
        loc.setName(name);
        RegionEntity region = new RegionEntity();
        region.setName(regionName);
        loc.setRegion(region);
        return new EvaluationTask.Forecast(
                loc, DATE, TargetType.SUNRISE, EvaluationModel.HAIKU, ATMOSPHERIC);
    }

    private EvaluationTask.Aurora auroraTask(AlertLevel level) {
        LocationEntity loc = new LocationEntity();
        loc.setId(1L);
        loc.setName("X");
        return new EvaluationTask.Aurora(
                level, DATE, EvaluationModel.HAIKU,
                List.of(loc), Map.of(loc, 30),
                SPACE_WEATHER, TriggerType.REALTIME, null);
    }
}
