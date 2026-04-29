package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler.BatchSuccess;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler.ForecastIdentity;
import tools.jackson.databind.ObjectMapper;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ForecastResultHandler} covering both batch and sync transports.
 *
 * <p>Each test stubs only the strategy method actually exercised; we use a real
 * {@link ClaudeEvaluationStrategy} (sourced from the strategy bean map at construction)
 * and stub it for parsing rather than mocking it — this exercises the handler's
 * delegation contract.
 */
@ExtendWith(MockitoExtension.class)
class ForecastResultHandlerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 4, 16);
    private static final TargetType SUNRISE = TargetType.SUNRISE;
    private static final AtmosphericData ATMOSPHERIC = TestAtmosphericData.defaults();

    @Mock
    private BriefingEvaluationService briefingEvaluationService;
    @Mock
    private ClaudeEvaluationStrategy parsingStrategy;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private ObjectMapper objectMapper;

    private ForecastResultHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ForecastResultHandler(
                briefingEvaluationService,
                Map.of(EvaluationModel.HAIKU, parsingStrategy),
                jobRunService, objectMapper);
    }

    @Test
    void taskTypeReturnsForecastClass() {
        assertThat(handler.taskType()).isEqualTo(EvaluationTask.Forecast.class);
    }

    @Test
    void rejectsConstructionWithoutClaudeStrategyForHaiku() {
        EvaluationStrategy notClaude = new NoOpEvaluationStrategy();
        assertThatThrownBy(() -> new ForecastResultHandler(
                briefingEvaluationService,
                Map.of(EvaluationModel.HAIKU, notClaude),
                jobRunService, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ClaudeEvaluationStrategy");
    }

    // ── Batch path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("parseBatchResponse: success → BatchSuccess + api_call_log row")
    void parseBatchResponse_success_returnsBatchSuccessAndLogs() {
        LocationEntity location = locationWithRegion(42L, "Castlerigg", "Lake District");
        ForecastIdentity identity = new ForecastIdentity(42L, DATE, SUNRISE);
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "fc-42-2026-04-16-SUNRISE",
                "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"X\"}",
                new TokenUsage(500, 200, 0, 1000),
                EvaluationModel.HAIKU);
        when(parsingStrategy.parseEvaluation(outcome.rawText(), objectMapper))
                .thenReturn(new SunsetEvaluation(4, 70, 65, "X"));

        ResultContext context = ResultContext.forBatch(
                99L, "msgbatch_x", BatchTriggerSource.SCHEDULED);
        Optional<BatchSuccess> result = handler.parseBatchResponse(
                location, identity, outcome, context);

        assertThat(result).isPresent();
        assertThat(result.get().cacheKey()).isEqualTo("Lake District|2026-04-16|SUNRISE");
        assertThat(result.get().result().locationName()).isEqualTo("Castlerigg");
        assertThat(result.get().result().rating()).isEqualTo(4);

        verify(jobRunService).logBatchResult(
                eq(99L), eq("msgbatch_x"), eq("fc-42-2026-04-16-SUNRISE"),
                eq(true), eq("SUCCESS"),
                eq(null), eq(null),
                eq(EvaluationModel.HAIKU), any(TokenUsage.class),
                eq(DATE), eq(SUNRISE));
    }

    @Test
    @DisplayName("parseBatchResponse: failure outcome → empty + api_call_log error row")
    void parseBatchResponse_failure_returnsEmptyAndLogsError() {
        LocationEntity location = locationWithRegion(42L, "Castlerigg", "Lake District");
        ForecastIdentity identity = new ForecastIdentity(42L, DATE, SUNRISE);
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.failure(
                "fc-42-2026-04-16-SUNRISE", "OVERLOADED_ERROR",
                "overloaded_error", "busy");

        ResultContext context = ResultContext.forBatch(
                99L, "msgbatch_x", BatchTriggerSource.SCHEDULED);
        Optional<BatchSuccess> result = handler.parseBatchResponse(
                location, identity, outcome, context);

        assertThat(result).isEmpty();
        verify(jobRunService).logBatchResult(
                eq(99L), eq("msgbatch_x"), eq("fc-42-2026-04-16-SUNRISE"),
                eq(false), eq("OVERLOADED_ERROR"),
                eq("overloaded_error"), eq("busy"),
                eq(null), eq(null),
                eq(DATE), eq(SUNRISE));
        verifyNoInteractions(parsingStrategy);
    }

    @Test
    @DisplayName("parseBatchResponse: parser throws → empty + parse_error log row")
    void parseBatchResponse_parserThrows_returnsEmptyAndLogsParseError() {
        LocationEntity location = locationWithRegion(42L, "Castlerigg", "Lake District");
        ForecastIdentity identity = new ForecastIdentity(42L, DATE, SUNRISE);
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "fc-42-2026-04-16-SUNRISE", "garbage",
                new TokenUsage(0, 0, 0, 0), EvaluationModel.HAIKU);
        when(parsingStrategy.parseEvaluation(outcome.rawText(), objectMapper))
                .thenThrow(new IllegalArgumentException("bad json"));

        ResultContext context = ResultContext.forBatch(
                99L, "msgbatch_x", BatchTriggerSource.SCHEDULED);
        Optional<BatchSuccess> result = handler.parseBatchResponse(
                location, identity, outcome, context);

        assertThat(result).isEmpty();
        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(jobRunService).logBatchResult(
                eq(99L), eq("msgbatch_x"), eq("fc-42-2026-04-16-SUNRISE"),
                eq(false), statusCaptor.capture(),
                eq("parse_error"), eq("bad json"),
                eq(null), eq(null),
                eq(DATE), eq(SUNRISE));
        assertThat(statusCaptor.getValue()).isEqualTo("PARSE_FAILED");
    }

    @Test
    @DisplayName("parseBatchResponse: rating out of range → safeRating=null but BatchSuccess still returned")
    void parseBatchResponse_outOfRangeRating_safeRatingNulled() {
        LocationEntity location = locationWithRegion(42L, "Castlerigg", "Lake District");
        ForecastIdentity identity = new ForecastIdentity(42L, DATE, SUNRISE);
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "fc-42-2026-04-16-SUNRISE",
                "{\"rating\":7,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"X\"}",
                new TokenUsage(500, 200, 0, 1000),
                EvaluationModel.HAIKU);
        when(parsingStrategy.parseEvaluation(outcome.rawText(), objectMapper))
                .thenReturn(new SunsetEvaluation(7, 70, 65, "X"));

        Optional<BatchSuccess> result = handler.parseBatchResponse(
                location, identity, outcome, ResultContext.forBatch(
                        99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(result).isPresent();
        assertThat(result.get().result().rating()).isNull();
    }

    @Test
    @DisplayName("parseBatchResponse: location with no region → uses location name as cache prefix")
    void parseBatchResponse_noRegion_usesLocationNameAsCachePrefix() {
        LocationEntity location = new LocationEntity();
        location.setId(42L);
        location.setName("Castlerigg");
        // region intentionally null
        ForecastIdentity identity = new ForecastIdentity(42L, DATE, SUNRISE);
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "fc-42-2026-04-16-SUNRISE",
                "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"X\"}",
                new TokenUsage(500, 200, 0, 1000),
                EvaluationModel.HAIKU);
        when(parsingStrategy.parseEvaluation(outcome.rawText(), objectMapper))
                .thenReturn(new SunsetEvaluation(4, 70, 65, "X"));

        Optional<BatchSuccess> result = handler.parseBatchResponse(
                location, identity, outcome, ResultContext.forBatch(
                        null, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(result).isPresent();
        assertThat(result.get().cacheKey()).isEqualTo("Castlerigg|2026-04-16|SUNRISE");
    }

    @Test
    @DisplayName("parseBatchResponse: null jobRunId → skips api_call_log, still returns parsed result")
    void parseBatchResponse_nullJobRunId_skipsLogButReturnsResult() {
        LocationEntity location = locationWithRegion(42L, "Castlerigg", "Lake District");
        ForecastIdentity identity = new ForecastIdentity(42L, DATE, SUNRISE);
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "fc-42-2026-04-16-SUNRISE",
                "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"X\"}",
                new TokenUsage(500, 200, 0, 1000),
                EvaluationModel.HAIKU);
        when(parsingStrategy.parseEvaluation(outcome.rawText(), objectMapper))
                .thenReturn(new SunsetEvaluation(4, 70, 65, "X"));

        Optional<BatchSuccess> result = handler.parseBatchResponse(
                location, identity, outcome,
                ResultContext.forBatch(null, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(result).isPresent();
        verifyNoInteractions(jobRunService);
    }

    @Test
    @DisplayName("parseBatchResponse: persistence exception is swallowed (does not break batch)")
    void parseBatchResponse_persistenceFailure_isSwallowed() {
        LocationEntity location = locationWithRegion(42L, "Castlerigg", "Lake District");
        ForecastIdentity identity = new ForecastIdentity(42L, DATE, SUNRISE);
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "fc-42-2026-04-16-SUNRISE",
                "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"X\"}",
                new TokenUsage(500, 200, 0, 1000),
                EvaluationModel.HAIKU);
        when(parsingStrategy.parseEvaluation(outcome.rawText(), objectMapper))
                .thenReturn(new SunsetEvaluation(4, 70, 65, "X"));
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                .when(jobRunService).logBatchResult(
                        any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                        any(), any(), any(), any(), any(), any(), any());

        Optional<BatchSuccess> result = handler.parseBatchResponse(
                location, identity, outcome, ResultContext.forBatch(
                        99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("flushCacheKey delegates to BriefingEvaluationService.writeFromBatch")
    void flushCacheKey_delegatesToBriefingService() {
        BriefingEvaluationResult res =
                new BriefingEvaluationResult("Castlerigg", 4, 70, 65, "X");

        handler.flushCacheKey("Lake District|2026-04-16|SUNRISE", List.of(res));

        verify(briefingEvaluationService).writeFromBatch(
                eq("Lake District|2026-04-16|SUNRISE"), eq(List.of(res)));
    }

    // ── Sync path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("handleSyncResult: success with BRIEFING_CACHE → Scored + api_call_log + cache write")
    void handleSyncResult_success_writesEverything() {
        LocationEntity location = locationWithRegion(42L, "Castlerigg", "Lake District");
        EvaluationTask.Forecast task = new EvaluationTask.Forecast(
                location, DATE, SUNRISE, EvaluationModel.HAIKU, ATMOSPHERIC,
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        ClaudeSyncOutcome outcome = ClaudeSyncOutcome.success(
                "{\"rating\":5,\"fiery_sky\":80,\"golden_hour\":75,\"summary\":\"OK\"}",
                new TokenUsage(500, 200, 0, 1000),
                EvaluationModel.HAIKU, 8500);
        when(parsingStrategy.parseEvaluation(outcome.rawText(), objectMapper))
                .thenReturn(new SunsetEvaluation(5, 80, 75, "OK"));

        EvaluationResult result = handler.handleSyncResult(
                task, outcome, ResultContext.forSync(99L, BatchTriggerSource.ADMIN));

        assertThat(result).isInstanceOf(EvaluationResult.Scored.class);
        verify(briefingEvaluationService).writeFromBatch(
                eq("Lake District|2026-04-16|SUNRISE"),
                org.mockito.ArgumentMatchers.<List<BriefingEvaluationResult>>any());
        verify(jobRunService).logAnthropicApiCall(
                eq(99L), eq(8500L), eq(200),
                eq(null), eq(true), eq(null),
                eq(EvaluationModel.HAIKU), any(TokenUsage.class),
                eq(false),
                eq(DATE), eq(SUNRISE));
    }

    @Test
    @DisplayName("handleSyncResult: success with NONE → Scored + api_call_log but NO cache write")
    void handleSyncResult_success_writeTargetNone_skipsCache() {
        LocationEntity location = locationWithRegion(42L, "Castlerigg", "Lake District");
        EvaluationTask.Forecast task = new EvaluationTask.Forecast(
                location, DATE, SUNRISE, EvaluationModel.HAIKU, ATMOSPHERIC,
                EvaluationTask.Forecast.WriteTarget.NONE);
        ClaudeSyncOutcome outcome = ClaudeSyncOutcome.success(
                "{\"rating\":5,\"fiery_sky\":80,\"golden_hour\":75,\"summary\":\"OK\"}",
                new TokenUsage(500, 200, 0, 1000),
                EvaluationModel.HAIKU, 8500);
        when(parsingStrategy.parseEvaluation(outcome.rawText(), objectMapper))
                .thenReturn(new SunsetEvaluation(5, 80, 75, "OK"));

        EvaluationResult result = handler.handleSyncResult(
                task, outcome, ResultContext.forSync(99L, BatchTriggerSource.ADMIN));

        assertThat(result).isInstanceOf(EvaluationResult.Scored.class);
        verify(briefingEvaluationService, never()).writeFromBatch(any(), any());
        verify(jobRunService).logAnthropicApiCall(
                eq(99L), eq(8500L), eq(200),
                eq(null), eq(true), eq(null),
                eq(EvaluationModel.HAIKU), any(TokenUsage.class),
                eq(false),
                eq(DATE), eq(SUNRISE));
    }

    @Test
    @DisplayName("handleSyncResult: failure → Errored + api_call_log error row, no cache write")
    void handleSyncResult_failure_returnsErroredAndSkipsCache() {
        LocationEntity location = locationWithRegion(42L, "Castlerigg", "Lake District");
        EvaluationTask.Forecast task = new EvaluationTask.Forecast(
                location, DATE, SUNRISE, EvaluationModel.HAIKU, ATMOSPHERIC,
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        ClaudeSyncOutcome outcome = ClaudeSyncOutcome.failure(
                "overloaded_error", "busy", EvaluationModel.HAIKU, 1500);

        EvaluationResult result = handler.handleSyncResult(
                task, outcome, ResultContext.forSync(99L, BatchTriggerSource.ADMIN));

        assertThat(result).isInstanceOf(EvaluationResult.Errored.class);
        EvaluationResult.Errored err = (EvaluationResult.Errored) result;
        assertThat(err.errorType()).isEqualTo("overloaded_error");
        verify(briefingEvaluationService, never()).writeFromBatch(any(), any());
        verify(jobRunService).logAnthropicApiCall(
                eq(99L), eq(1500L), eq(500),
                eq("busy"), eq(false), eq("busy"),
                eq(EvaluationModel.HAIKU), any(TokenUsage.class),
                eq(false),
                eq(DATE), eq(SUNRISE));
    }

    @Test
    @DisplayName("handleSyncResult: parser throws → Errored, no cache write")
    void handleSyncResult_parseError_returnsErrored() {
        LocationEntity location = locationWithRegion(42L, "Castlerigg", "Lake District");
        EvaluationTask.Forecast task = new EvaluationTask.Forecast(
                location, DATE, SUNRISE, EvaluationModel.HAIKU, ATMOSPHERIC,
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        ClaudeSyncOutcome outcome = ClaudeSyncOutcome.success(
                "garbage", new TokenUsage(0, 0, 0, 0), EvaluationModel.HAIKU, 1500);
        when(parsingStrategy.parseEvaluation(outcome.rawText(), objectMapper))
                .thenThrow(new IllegalArgumentException("bad json"));

        EvaluationResult result = handler.handleSyncResult(
                task, outcome, ResultContext.forSync(99L, BatchTriggerSource.ADMIN));

        assertThat(result).isInstanceOf(EvaluationResult.Errored.class);
        EvaluationResult.Errored err = (EvaluationResult.Errored) result;
        assertThat(err.errorType()).isEqualTo("parse_error");
        verify(briefingEvaluationService, never()).writeFromBatch(any(), any());
    }

    @Test
    @DisplayName("handleSyncResult: null jobRunId → skips api_call_log, still writes cache")
    void handleSyncResult_nullJobRun_skipsLogStillWritesCache() {
        LocationEntity location = locationWithRegion(42L, "Castlerigg", "Lake District");
        EvaluationTask.Forecast task = new EvaluationTask.Forecast(
                location, DATE, SUNRISE, EvaluationModel.HAIKU, ATMOSPHERIC,
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        ClaudeSyncOutcome outcome = ClaudeSyncOutcome.success(
                "{\"rating\":5,\"fiery_sky\":80,\"golden_hour\":75,\"summary\":\"OK\"}",
                new TokenUsage(500, 200, 0, 1000),
                EvaluationModel.HAIKU, 8500);
        when(parsingStrategy.parseEvaluation(outcome.rawText(), objectMapper))
                .thenReturn(new SunsetEvaluation(5, 80, 75, "OK"));

        EvaluationResult result = handler.handleSyncResult(
                task, outcome, ResultContext.forSync(null, BatchTriggerSource.ADMIN));

        assertThat(result).isInstanceOf(EvaluationResult.Scored.class);
        verify(briefingEvaluationService).writeFromBatch(any(), any());
        verifyNoInteractions(jobRunService);
    }

    private LocationEntity locationWithRegion(long id, String name, String regionName) {
        LocationEntity loc = new LocationEntity();
        loc.setId(id);
        loc.setName(name);
        RegionEntity region = new RegionEntity();
        region.setName(regionName);
        loc.setRegion(region);
        return loc;
    }
}
