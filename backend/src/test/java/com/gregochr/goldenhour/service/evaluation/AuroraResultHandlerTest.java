package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
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
                eq(false), eq("OVERLOADED_ERROR"),
                eq("overloaded_error"), eq("busy"),
                eq(null), eq(null), eq(null), eq(null));
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

    // ── Sync path ────────────────────────────────────────────────────────────

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
                eq(null), eq(null));
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
                eq(99L), eq(1500L), eq(500),
                eq("busy"), eq(false), eq("busy"),
                eq(EvaluationModel.HAIKU), any(TokenUsage.class),
                eq(false),
                eq(null), eq(null));
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

    private LocationEntity loc(long id, String name) {
        LocationEntity loc = new LocationEntity();
        loc.setId(id);
        loc.setName(name);
        return loc;
    }
}
