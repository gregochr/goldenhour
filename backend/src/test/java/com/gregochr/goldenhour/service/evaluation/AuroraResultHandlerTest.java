package com.gregochr.goldenhour.service.evaluation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.CacheDiagnosticsFixtures;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.ClaudeAuroraInterpreter;
import com.gregochr.goldenhour.service.aurora.TriggerType;
import com.gregochr.goldenhour.service.aurora.WeatherTriageService;
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;
import com.gregochr.goldenhour.service.evaluation.AuroraResultHandler.AuroraBatchOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuroraResultHandler}.
 *
 * <p>Covers both transports — the multi-location batch path (with re-triage at
 * result-processing time) and the synchronous {@code evaluateNow} path.
 */
@ExtendWith(MockitoExtension.class)
class AuroraResultHandlerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 4, 16);
    private static final SpaceWeatherData SPACE_WEATHER = new SpaceWeatherData(
            List.of(), List.of(), null, List.of(), List.of());

    @Mock
    private ClaudeAuroraInterpreter claudeAuroraInterpreter;
    @Mock
    private AuroraStateCache auroraStateCache;
    @Mock
    private WeatherTriageService weatherTriageService;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private JobRunService jobRunService;

    private AuroraProperties auroraProperties;
    private AuroraResultHandler handler;

    @BeforeEach
    void setUp() {
        auroraProperties = new AuroraProperties();
        // Use defaults: moderate=4, strong=5
        handler = new AuroraResultHandler(
                claudeAuroraInterpreter, auroraStateCache,
                weatherTriageService, locationRepository,
                auroraProperties, jobRunService);
    }

    @Test
    void taskTypeReturnsAuroraClass() {
        assertThat(handler.taskType()).isEqualTo(EvaluationTask.Aurora.class);
    }

    // ── Batch path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("processBatchResponse: failure outcome → AuroraBatchOutcome.failure, api_call_log written")
    void processBatchResponse_failure_returnsFailureAndLogs() {
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.failure(
                "au-MODERATE-2026-04-16", "OVERLOADED_ERROR",
                "overloaded_error", "busy");

        AuroraBatchOutcome result = handler.processBatchResponse(
                AlertLevel.MODERATE, outcome,
                ResultContext.forBatch(99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("overloaded_error");
        verify(jobRunService).logBatchResult(
                eq(99L), eq("msgbatch_x"), eq("au-MODERATE-2026-04-16"),
                eq(false),
                eq("overloaded_error"), eq("busy"),
                eq(null), eq(null), eq(null), eq(null), eq(null), eq(null));
        verifyNoInteractions(weatherTriageService, claudeAuroraInterpreter, auroraStateCache);
    }

    @Test
    @DisplayName("processBatchResponse: no Bortle-eligible locations → failure, score cache untouched")
    void processBatchResponse_noBortleLocations_returnsFailure() {
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "au-MODERATE-2026-04-16", "[]",
                new TokenUsage(500, 200, 0, 1000),
                EvaluationModel.HAIKU);
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of());

        AuroraBatchOutcome result = handler.processBatchResponse(
                AlertLevel.MODERATE, outcome,
                ResultContext.forBatch(99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).contains("Bortle");
        verifyNoInteractions(weatherTriageService, claudeAuroraInterpreter, auroraStateCache);
    }

    @Test
    @DisplayName("processBatchResponse: STRONG alert uses strong Bortle threshold (5)")
    void processBatchResponse_strongAlert_usesStrongBortleThreshold() {
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "au-STRONG-2026-04-16", "[]",
                new TokenUsage(500, 200, 0, 1000),
                EvaluationModel.HAIKU);
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(5))
                .thenReturn(List.of());

        handler.processBatchResponse(
                AlertLevel.STRONG, outcome,
                ResultContext.forBatch(99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        verify(locationRepository).findByBortleClassLessThanEqualAndEnabledTrue(5);
    }

    @Test
    @DisplayName("processBatchResponse: triage throws → failure, no parsing, no cache write")
    void processBatchResponse_triageThrows_returnsFailure() {
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "au-MODERATE-2026-04-16", "[]",
                new TokenUsage(500, 200, 0, 1000),
                EvaluationModel.HAIKU);
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of(loc(1L, "X")));
        when(weatherTriageService.triage(any())).thenThrow(new RuntimeException("WX outage"));

        AuroraBatchOutcome result = handler.processBatchResponse(
                AlertLevel.MODERATE, outcome,
                ResultContext.forBatch(99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).contains("Weather re-triage failed");
        verifyNoInteractions(claudeAuroraInterpreter, auroraStateCache);
    }

    @Test
    @DisplayName("processBatchResponse: empty viable list after triage → failure")
    void processBatchResponse_noViableAfterTriage_returnsFailure() {
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "au-MODERATE-2026-04-16", "[]",
                new TokenUsage(500, 200, 0, 1000),
                EvaluationModel.HAIKU);
        LocationEntity rejected = loc(1L, "X");
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of(rejected));
        when(weatherTriageService.triage(any())).thenReturn(
                new WeatherTriageService.TriageResult(List.of(), List.of(rejected),
                        Map.of(rejected, 100)));

        AuroraBatchOutcome result = handler.processBatchResponse(
                AlertLevel.MODERATE, outcome,
                ResultContext.forBatch(99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).contains("No viable locations");
        verifyNoInteractions(claudeAuroraInterpreter, auroraStateCache);
    }

    @Test
    @DisplayName("processBatchResponse: success + 1★ fallback for rejected → updateScores called with combined list")
    void processBatchResponse_addsRejectedAsOneStar() {
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "au-MODERATE-2026-04-16",
                "[{\"name\":\"X\",\"stars\":4,\"summary\":\"good\",\"detail\":\"\"}]",
                new TokenUsage(500, 200, 0, 1000),
                EvaluationModel.HAIKU);
        LocationEntity viable = loc(1L, "X");
        LocationEntity overcast = loc(2L, "Y");
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of(viable, overcast));
        when(weatherTriageService.triage(any())).thenReturn(
                new WeatherTriageService.TriageResult(List.of(viable), List.of(overcast),
                        Map.of(viable, 30, overcast, 95)));
        when(claudeAuroraInterpreter.parseBatchResponse(
                any(), eq(AlertLevel.MODERATE), eq(List.of(viable)),
                eq(Map.of(viable, 30, overcast, 95))))
                .thenReturn(List.of(new AuroraForecastScore(
                        viable, 4, AlertLevel.MODERATE, 30, "good", "")));

        AuroraBatchOutcome result = handler.processBatchResponse(
                AlertLevel.MODERATE, outcome,
                ResultContext.forBatch(99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(result.success()).isTrue();
        assertThat(result.scoredCount()).isEqualTo(2);
        ArgumentCaptor<List<AuroraForecastScore>> scoresCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(auroraStateCache).updateScores(scoresCaptor.capture());
        assertThat(scoresCaptor.getValue()).hasSize(2);
        assertThat(scoresCaptor.getValue().stream().filter(s -> s.stars() == 1).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("processBatchResponse: parser throws → failure, score cache untouched")
    void processBatchResponse_parserThrows_returnsFailure() {
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "au-MODERATE-2026-04-16", "garbage",
                new TokenUsage(500, 200, 0, 1000),
                EvaluationModel.HAIKU);
        LocationEntity viable = loc(1L, "X");
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of(viable));
        when(weatherTriageService.triage(any())).thenReturn(
                new WeatherTriageService.TriageResult(List.of(viable), List.of(),
                        Map.of(viable, 30)));
        when(claudeAuroraInterpreter.parseBatchResponse(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("bad json"));

        AuroraBatchOutcome result = handler.processBatchResponse(
                AlertLevel.MODERATE, outcome,
                ResultContext.forBatch(99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).contains("Score parsing failed");
        verifyNoInteractions(auroraStateCache);
    }

    @Test
    @DisplayName("processBatchResponse: the outcome's cache diagnostics reach the api_call_log row")
    void processBatchResponse_logsTheOutcomesCacheDiagnostics() {
        ClaudeBatchOutcome outcome = new ClaudeBatchOutcome(
                "au-MODERATE-2026-04-16", false, "REFUSAL", "refusal", "refused", null,
                new TokenUsage(500, 200, 0, 1000), EvaluationModel.HAIKU,
                CacheDiagnosticsFixtures.MESSAGES_CHANGED_READ);

        handler.processBatchResponse(AlertLevel.MODERATE, outcome,
                ResultContext.forBatch(99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        verify(jobRunService).logBatchResult(
                eq(99L), eq("msgbatch_x"), eq("au-MODERATE-2026-04-16"),
                eq(false),
                eq("refusal"), eq("refused"),
                eq(EvaluationModel.HAIKU), any(TokenUsage.class),
                eq(null), eq(null), eq(null),
                eq(CacheDiagnosticsFixtures.MESSAGES_CHANGED_READ));
    }

    // ── Sync path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("handleSyncResult: the outcome's cache diagnostics reach the api_call_log row")
    void handleSyncResult_logsTheOutcomesCacheDiagnostics() {
        LocationEntity viable = loc(1L, "X");
        EvaluationTask.Aurora task = new EvaluationTask.Aurora(
                AlertLevel.MODERATE, DATE, EvaluationModel.HAIKU,
                List.of(viable), Map.of(viable, 30),
                SPACE_WEATHER, TriggerType.REALTIME, null);
        ClaudeSyncOutcome outcome = ClaudeSyncOutcome.success(
                "[{\"name\":\"X\",\"stars\":4}]",
                new TokenUsage(500, 200, 0, 1000),
                EvaluationModel.HAIKU, 8500, CacheDiagnosticsFixtures.MESSAGES_CHANGED_READ);
        when(claudeAuroraInterpreter.parseBatchResponse(
                eq(outcome.rawText()), eq(AlertLevel.MODERATE),
                eq(List.of(viable)), eq(Map.of(viable, 30))))
                .thenReturn(List.of(new AuroraForecastScore(
                        viable, 4, AlertLevel.MODERATE, 30, "good", "")));

        handler.handleSyncResult(task, outcome, ResultContext.forSync(99L, BatchTriggerSource.SCHEDULED));

        verify(jobRunService).logAnthropicApiCall(
                eq(99L), eq(8500L), eq(200),
                eq(null), eq(true), eq(null),
                eq(EvaluationModel.HAIKU), any(TokenUsage.class),
                eq(false),
                eq(null), eq(null), eq(null),
                eq(CacheDiagnosticsFixtures.MESSAGES_CHANGED_READ));
    }

    @Test
    @DisplayName("handleSyncResult: success → Scored + api_call_log; orchestrator owns updateScores")
    void handleSyncResult_success_returnsScoredAndLogsButDoesNotWriteCache() {
        LocationEntity viable = loc(1L, "X");
        EvaluationTask.Aurora task = new EvaluationTask.Aurora(
                AlertLevel.MODERATE, DATE, EvaluationModel.HAIKU,
                List.of(viable), Map.of(viable, 30),
                SPACE_WEATHER, TriggerType.REALTIME, null);
        ClaudeSyncOutcome outcome = ClaudeSyncOutcome.success(
                "[{\"name\":\"X\",\"stars\":4}]",
                new TokenUsage(500, 200, 0, 1000),
                EvaluationModel.HAIKU, 8500);
        AuroraForecastScore score = new AuroraForecastScore(
                viable, 4, AlertLevel.MODERATE, 30, "good", "");
        when(claudeAuroraInterpreter.parseBatchResponse(
                eq(outcome.rawText()), eq(AlertLevel.MODERATE),
                eq(List.of(viable)), eq(Map.of(viable, 30))))
                .thenReturn(List.of(score));

        EvaluationResult result = handler.handleSyncResult(
                task, outcome,
                ResultContext.forSync(99L, BatchTriggerSource.SCHEDULED));

        assertThat(result).isInstanceOf(EvaluationResult.Scored.class);
        EvaluationResult.Scored scored = (EvaluationResult.Scored) result;
        assertThat(scored.payload()).isEqualTo(List.of(score));
        // Sync path: orchestrator handles updateScores after merging rejected locations.
        verify(auroraStateCache, never()).updateScores(any());
        verify(jobRunService).logAnthropicApiCall(
                eq(99L), eq(8500L), eq(200),
                eq(null), eq(true), eq(null),
                eq(EvaluationModel.HAIKU), any(TokenUsage.class),
                eq(false),
                eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    @DisplayName("handleSyncResult: failure → Errored + api_call_log error, no cache write")
    void handleSyncResult_failure_returnsErroredAndSkipsCache() {
        LocationEntity viable = loc(1L, "X");
        EvaluationTask.Aurora task = new EvaluationTask.Aurora(
                AlertLevel.MODERATE, DATE, EvaluationModel.HAIKU,
                List.of(viable), Map.of(viable, 30),
                SPACE_WEATHER, TriggerType.REALTIME, null);
        ClaudeSyncOutcome outcome = ClaudeSyncOutcome.failure(
                "overloaded_error", "busy", EvaluationModel.HAIKU, 1500);

        EvaluationResult result = handler.handleSyncResult(
                task, outcome,
                ResultContext.forSync(99L, BatchTriggerSource.SCHEDULED));

        assertThat(result).isInstanceOf(EvaluationResult.Errored.class);
        verify(auroraStateCache, never()).updateScores(any());
        verify(jobRunService).logAnthropicApiCall(
                eq(99L), eq(1500L), eq(null),
                eq("busy"), eq(false), eq("busy"),
                eq(EvaluationModel.HAIKU), any(TokenUsage.class),
                eq(false),
                eq(null), eq(null), eq("overloaded_error"), eq(null));
    }

    @Test
    @DisplayName("handleSyncResult: parser throws → Errored, no cache write")
    void handleSyncResult_parserThrows_returnsErrored() {
        LocationEntity viable = loc(1L, "X");
        EvaluationTask.Aurora task = new EvaluationTask.Aurora(
                AlertLevel.MODERATE, DATE, EvaluationModel.HAIKU,
                List.of(viable), Map.of(viable, 30),
                SPACE_WEATHER, TriggerType.REALTIME, null);
        ClaudeSyncOutcome outcome = ClaudeSyncOutcome.success(
                "garbage", new TokenUsage(0, 0, 0, 0), EvaluationModel.HAIKU, 1500);
        when(claudeAuroraInterpreter.parseBatchResponse(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("bad json"));

        EvaluationResult result = handler.handleSyncResult(
                task, outcome,
                ResultContext.forSync(99L, BatchTriggerSource.SCHEDULED));

        assertThat(result).isInstanceOf(EvaluationResult.Errored.class);
        EvaluationResult.Errored err = (EvaluationResult.Errored) result;
        assertThat(err.errorType()).isEqualTo("parse_error");
        verify(auroraStateCache, never()).updateScores(any());
    }

    @Test
    @DisplayName("processBatchResponse: a failure with no error type reports the response status as the reason")
    void processBatchResponse_failureWithoutErrorType_reportsStatus() {
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.failure(
                "au-MODERATE-2026-04-16", "EXPIRED", null, "expired");

        AuroraBatchOutcome result = handler.processBatchResponse(AlertLevel.MODERATE, outcome, null);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("EXPIRED");
        verifyNoInteractions(jobRunService);
    }

    @Test
    @DisplayName("processBatchResponse: a context with no job run id writes no api_call_log row")
    void processBatchResponse_noJobRunId_skipsLog() {
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.failure(
                "au-MODERATE-2026-04-16", "EXPIRED", "expired", "expired");

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            handler.processBatchResponse(AlertLevel.MODERATE, outcome,
                    ResultContext.forBatch(null, "msgbatch_x", BatchTriggerSource.SCHEDULED));
        } finally {
            detachAppender(appender);
        }

        verifyNoInteractions(jobRunService);
        assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.DEBUG
                && event.getFormattedMessage().contains("au-MODERATE-2026-04-16")
                && event.getFormattedMessage().contains("msgbatch_x")
                && event.getFormattedMessage().contains("jobRunId is absent"));
        assertThat(appender.list).noneMatch(event ->
                event.getFormattedMessage().contains("no ResultContext was supplied"));
    }

    @Test
    @DisplayName("processBatchResponse: no context at all writes no api_call_log row and does not throw")
    void processBatchResponse_nullContext_skipsLogWithoutThrowing() {
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.failure(
                "au-MODERATE-2026-04-16", "EXPIRED", "expired", "expired");

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            handler.processBatchResponse(AlertLevel.MODERATE, outcome, null);
        } finally {
            detachAppender(appender);
        }

        verifyNoInteractions(jobRunService);
        assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.DEBUG
                && event.getFormattedMessage().contains("au-MODERATE-2026-04-16")
                && event.getFormattedMessage().contains("no ResultContext was supplied"));
        assertThat(appender.list).noneMatch(event ->
                event.getFormattedMessage().contains("jobRunId is absent"));
    }

    @Test
    @DisplayName("processBatchResponse: an api_call_log write failure is swallowed and the outcome is still returned")
    void processBatchResponse_logWriteFails_isSwallowed() {
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.failure(
                "au-MODERATE-2026-04-16", "EXPIRED", "expired", "expired");
        org.mockito.Mockito.doThrow(new RuntimeException("DB down")).when(jobRunService).logBatchResult(
                eq(99L), eq("msgbatch_x"), eq("au-MODERATE-2026-04-16"), eq(false),
                eq("expired"), eq("expired"), eq(null), eq(null),
                eq(null), eq(null), eq(null), eq(null));

        AuroraBatchOutcome result = handler.processBatchResponse(AlertLevel.MODERATE, outcome,
                ResultContext.forBatch(99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("expired");
        verify(jobRunService).logBatchResult(
                eq(99L), eq("msgbatch_x"), eq("au-MODERATE-2026-04-16"), eq(false),
                eq("expired"), eq("expired"), eq(null), eq(null),
                eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    @DisplayName("processBatchResponse: an unknown alert level in the custom id is treated as QUIET "
            + "(moderate Bortle threshold, QUIET passed to the parser)")
    void processBatchResponse_nullAlertLevel_treatedAsQuiet() {
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "au-?-2026-04-16", "[]", new TokenUsage(500, 200, 0, 1000), EvaluationModel.HAIKU);
        LocationEntity viable = loc(1L, "X");
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of(viable));
        when(weatherTriageService.triage(List.of(viable))).thenReturn(
                new WeatherTriageService.TriageResult(List.of(viable), List.of(), Map.of(viable, 30)));
        AuroraForecastScore score = new AuroraForecastScore(viable, 2, AlertLevel.QUIET, 30, "ok", "");
        when(claudeAuroraInterpreter.parseBatchResponse(
                eq("[]"), eq(AlertLevel.QUIET), eq(List.of(viable)), eq(Map.of(viable, 30))))
                .thenReturn(List.of(score));

        AuroraBatchOutcome result = handler.processBatchResponse(null, outcome, null);

        assertThat(result.success()).isTrue();
        assertThat(result.scoredCount()).isEqualTo(1);
        verify(auroraStateCache).updateScores(List.of(score));
    }

    @Test
    @DisplayName("handleSyncResult: a failure with no error type is reported as 'unknown'")
    void handleSyncResult_failureWithoutErrorType_isUnknown() {
        LocationEntity viable = loc(1L, "X");
        EvaluationTask.Aurora task = new EvaluationTask.Aurora(
                AlertLevel.MODERATE, DATE, EvaluationModel.HAIKU,
                List.of(viable), Map.of(viable, 30),
                SPACE_WEATHER, TriggerType.REALTIME, null);
        ClaudeSyncOutcome outcome = ClaudeSyncOutcome.failure(null, "busy", EvaluationModel.HAIKU, 1500);

        EvaluationResult result = handler.handleSyncResult(task, outcome, null);

        assertThat(result).isEqualTo(new EvaluationResult.Errored("unknown", "busy"));
        verifyNoInteractions(jobRunService);
    }

    @Test
    @DisplayName("handleSyncResult: a context with no job run id writes no api_call_log row and says why")
    void handleSyncResult_noJobRunId_skipsLog() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            handler.handleSyncResult(syncTask(), syncFailure(),
                    ResultContext.forSync(null, BatchTriggerSource.SCHEDULED));
        } finally {
            detachAppender(appender);
        }

        verifyNoInteractions(jobRunService);
        assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.DEBUG
                && event.getFormattedMessage().contains("jobRunId is absent"));
        assertThat(appender.list).noneMatch(event ->
                event.getFormattedMessage().contains("no ResultContext was supplied"));
    }

    @Test
    @DisplayName("handleSyncResult: no context at all writes no api_call_log row and says why")
    void handleSyncResult_nullContext_skipsLog() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            handler.handleSyncResult(syncTask(), syncFailure(), null);
        } finally {
            detachAppender(appender);
        }

        verifyNoInteractions(jobRunService);
        assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.DEBUG
                && event.getFormattedMessage().contains("no ResultContext was supplied"));
        assertThat(appender.list).noneMatch(event ->
                event.getFormattedMessage().contains("jobRunId is absent"));
    }

    private EvaluationTask.Aurora syncTask() {
        LocationEntity viable = loc(1L, "X");
        return new EvaluationTask.Aurora(
                AlertLevel.MODERATE, DATE, EvaluationModel.HAIKU,
                List.of(viable), Map.of(viable, 30),
                SPACE_WEATHER, TriggerType.REALTIME, null);
    }

    private ClaudeSyncOutcome syncFailure() {
        return ClaudeSyncOutcome.failure(
                "overloaded_error", "busy", EvaluationModel.HAIKU, 1500);
    }

    @Test
    @DisplayName("handleSyncResult: an api_call_log write failure is swallowed and the result is still returned")
    void handleSyncResult_logWriteFails_isSwallowed() {
        LocationEntity viable = loc(1L, "X");
        EvaluationTask.Aurora task = new EvaluationTask.Aurora(
                AlertLevel.MODERATE, DATE, EvaluationModel.HAIKU,
                List.of(viable), Map.of(viable, 30),
                SPACE_WEATHER, TriggerType.REALTIME, null);
        ClaudeSyncOutcome outcome = ClaudeSyncOutcome.failure(
                "overloaded_error", "busy", EvaluationModel.HAIKU, 1500);
        org.mockito.Mockito.doThrow(new RuntimeException("DB down")).when(jobRunService)
                .logAnthropicApiCall(eq(99L), eq(1500L), eq(null),
                        eq("busy"), eq(false), eq("busy"), eq(EvaluationModel.HAIKU),
                        eq(TokenUsage.EMPTY), eq(false), eq(null), eq(null),
                        eq("overloaded_error"), eq(null));

        EvaluationResult result = handler.handleSyncResult(task, outcome,
                ResultContext.forSync(99L, BatchTriggerSource.SCHEDULED));

        assertThat(result).isEqualTo(new EvaluationResult.Errored("overloaded_error", "busy"));
    }

    private LocationEntity loc(long id, String name) {
        LocationEntity loc = new LocationEntity();
        loc.setId(id);
        loc.setName(name);
        return loc;
    }

    /** Attaches a capturing appender and opens the logger to DEBUG, which the test config filters out. */
    private static ListAppender<ILoggingEvent> attachAppender() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(AuroraResultHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(AuroraResultHandler.class);
        logger.detachAppender(appender);
        logger.setLevel(null);
    }
}
