package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BluebellEvaluation;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TideContext;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.ForecastDataAugmentor;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler.BatchSuccess;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler.ForecastIdentity;
import com.gregochr.goldenhour.service.evaluation.visitor.BluebellVisitor;
import com.gregochr.goldenhour.service.evaluation.visitor.ComponentScore;
import com.gregochr.goldenhour.service.evaluation.visitor.RatingCombiner;
import com.gregochr.goldenhour.service.evaluation.visitor.SkyVisitor;
import com.gregochr.goldenhour.service.evaluation.visitor.TideVisitor;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
    @Mock
    private ForecastDataAugmentor forecastDataAugmentor;
    @Mock
    private ForecastScoreWriter forecastScoreWriter;

    private ForecastResultHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ForecastResultHandler(
                briefingEvaluationService,
                Map.of(EvaluationModel.HAIKU, parsingStrategy),
                jobRunService, objectMapper,
                new RatingCombiner(List.of(
                        new SkyVisitor(), new TideVisitor(), new BluebellVisitor())),
                forecastDataAugmentor, forecastScoreWriter);
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
                jobRunService, objectMapper,
                new RatingCombiner(List.of(new SkyVisitor())),
                forecastDataAugmentor, forecastScoreWriter))
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
        when(parsingStrategy.parseEvaluationWithMetadata(outcome.rawText(), objectMapper))
                .thenReturn(new ClaudeEvaluationStrategy.ParseResult(
                        new SunsetEvaluation(4, 70, 65, "X"), false));

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
                eq(DATE), eq(SUNRISE), eq(null));
    }

    @Test
    @DisplayName("parseBluebellBatchResponse: woodland → bluebell IS the rating, no sky scores, "
            + "BLUEBELL component dual-written")
    void parseBluebellBatchResponse_woodland_bluebellIsRating() {
        LocationEntity location = woodlandBluebellLocation(53L, "Bluebell Wood", "Lake District");
        ForecastIdentity identity = new ForecastIdentity(53L, DATE, SUNRISE);
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "bb-53-2026-04-16-SUNRISE",
                "{\"rating\":4,\"summary\":\"Bright still light if they are in flower.\","
                        + "\"headline\":\"Soft canopy light\"}",
                new TokenUsage(300, 80, 0, 900),
                EvaluationModel.HAIKU);
        when(parsingStrategy.parseBluebellEvaluation(outcome.rawText(), objectMapper))
                .thenReturn(new BluebellEvaluation(
                        4, "Bright still light if they are in flower.", "Soft canopy light"));

        ResultContext context = ResultContext.forBatch(
                99L, "msgbatch_bb", BatchTriggerSource.SCHEDULED);
        Optional<BatchSuccess> result = handler.parseBluebellBatchResponse(
                location, identity, outcome, context);

        assertThat(result).isPresent();
        BriefingEvaluationResult r = result.get().result();
        assertThat(result.get().cacheKey()).isEqualTo("Lake District|2026-04-16|SUNRISE");
        assertThat(r.locationName()).isEqualTo("Bluebell Wood");
        assertThat(r.rating()).isEqualTo(4);
        // No sky scores behind a bluebell-only slot.
        assertThat(r.fierySkyPotential()).isNull();
        assertThat(r.goldenHourPotential()).isNull();
        assertThat(r.summary()).isEqualTo("Bright still light if they are in flower.");
        assertThat(r.headline()).isEqualTo("Soft canopy light");

        // Dual-write persists ONLY the BLUEBELL component (writeComponents, not write).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ComponentScore>> captor = ArgumentCaptor.forClass(List.class);
        verify(forecastScoreWriter).writeComponents(
                eq(location), eq(DATE), eq(SUNRISE), captor.capture(), eq(null));
        assertThat(captor.getValue())
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.type()).isEqualTo(ForecastType.BLUEBELL);
                    assertThat(c.score()).isEqualTo(4);
                });
        verify(forecastScoreWriter, never()).write(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("parseBluebellBatchResponse: failed outcome → empty + failure log, no combine")
    void parseBluebellBatchResponse_failedOutcome_returnsEmpty() {
        LocationEntity location = woodlandBluebellLocation(53L, "Bluebell Wood", "Lake District");
        ForecastIdentity identity = new ForecastIdentity(53L, DATE, SUNRISE);
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.failure(
                "bb-53-2026-04-16-SUNRISE", "expired", "expired", "request expired");

        ResultContext context = ResultContext.forBatch(
                99L, "msgbatch_bb", BatchTriggerSource.SCHEDULED);
        Optional<BatchSuccess> result = handler.parseBluebellBatchResponse(
                location, identity, outcome, context);

        assertThat(result).isEmpty();
        verifyNoInteractions(forecastScoreWriter);
    }

    @Test
    @DisplayName("parseBatchResponse: regex fallback used → persists raw response_body + "
            + "regex_fallback marker, succeeded stays true")
    void parseBatchResponse_regexFallback_persistsRawAndMarker() {
        LocationEntity location = locationWithRegion(42L, "Castlerigg", "Lake District");
        ForecastIdentity identity = new ForecastIdentity(42L, DATE, SUNRISE);
        // Realistic malformed-ish raw that would force strict-parse failure (resembles the
        // observed Bug B artifact). The parser is mocked, so this is the raw that must be
        // persisted verbatim — it is NOT a Bug B fix fixture.
        String rawText = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,"
                + "\"summary\":\"Clear horizon.\",'headline\"}";
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "fc-42-2026-04-16-SUNRISE", rawText,
                new TokenUsage(500, 200, 0, 1000), EvaluationModel.HAIKU);
        when(parsingStrategy.parseEvaluationWithMetadata(outcome.rawText(), objectMapper))
                .thenReturn(new ClaudeEvaluationStrategy.ParseResult(
                        new SunsetEvaluation(4, 70, 65, "Clear horizon."), true));

        Optional<BatchSuccess> result = handler.parseBatchResponse(
                location, identity, outcome,
                ResultContext.forBatch(99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(result).isPresent();
        assertThat(result.get().result().rating()).isEqualTo(4);
        // succeeded stays TRUE (the marker, not the flag, makes it findable); raw + marker stored.
        verify(jobRunService).logBatchResult(
                eq(99L), eq("msgbatch_x"), eq("fc-42-2026-04-16-SUNRISE"),
                eq(true), eq("SUCCESS"),
                eq(ForecastResultHandler.REGEX_FALLBACK_MARKER), eq(null),
                eq(EvaluationModel.HAIKU), any(TokenUsage.class),
                eq(DATE), eq(SUNRISE), eq(rawText));
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
                eq(DATE), eq(SUNRISE), eq(null));
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
        when(parsingStrategy.parseEvaluationWithMetadata(outcome.rawText(), objectMapper))
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
                eq(DATE), eq(SUNRISE), eq(null));
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
        when(parsingStrategy.parseEvaluationWithMetadata(outcome.rawText(), objectMapper))
                .thenReturn(new ClaudeEvaluationStrategy.ParseResult(
                        new SunsetEvaluation(7, 70, 65, "X"), false));

        Optional<BatchSuccess> result = handler.parseBatchResponse(
                location, identity, outcome, ResultContext.forBatch(
                        99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(result).isPresent();
        assertThat(result.get().result().rating()).isNull();
    }

    @Test
    @DisplayName("dual-write failure is isolated: evaluation still succeeds and logs at ERROR")
    void parseBatchResponse_dualWriteThrows_evaluationProceedsAndLogsError() {
        LocationEntity location = locationWithRegion(42L, "Castlerigg", "Lake District");
        ForecastIdentity identity = new ForecastIdentity(42L, DATE, SUNRISE);
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "fc-42-2026-04-16-SUNRISE",
                "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"X\"}",
                new TokenUsage(500, 200, 0, 1000),
                EvaluationModel.HAIKU);
        when(parsingStrategy.parseEvaluationWithMetadata(outcome.rawText(), objectMapper))
                .thenReturn(new ClaudeEvaluationStrategy.ParseResult(
                        new SunsetEvaluation(4, 70, 65, "X"), false));
        // The forecast_score dual-write blows up — it must NOT take down the evaluation.
        doThrow(new RuntimeException("DB down")).when(forecastScoreWriter)
                .write(any(), any(), any(), any(), anyList(), any());

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(ForecastResultHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Optional<BatchSuccess> result = handler.parseBatchResponse(
                    location, identity, outcome, ResultContext.forBatch(
                            99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

            // Evaluation proceeds: the serving result is returned with its rating intact.
            assertThat(result).isPresent();
            assertThat(result.get().result().rating()).isEqualTo(4);
            // The failure is surfaced loudly at ERROR with the component key, not swallowed.
            assertThat(appender.list)
                    .anyMatch(event -> event.getLevel() == Level.ERROR
                            && event.getFormattedMessage().contains("dual-write FAILED")
                            && event.getFormattedMessage().contains("Castlerigg"));
        } finally {
            logger.detachAppender(appender);
        }
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
        when(parsingStrategy.parseEvaluationWithMetadata(outcome.rawText(), objectMapper))
                .thenReturn(new ClaudeEvaluationStrategy.ParseResult(
                        new SunsetEvaluation(4, 70, 65, "X"), false));

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
        when(parsingStrategy.parseEvaluationWithMetadata(outcome.rawText(), objectMapper))
                .thenReturn(new ClaudeEvaluationStrategy.ParseResult(
                        new SunsetEvaluation(4, 70, 65, "X"), false));

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
        when(parsingStrategy.parseEvaluationWithMetadata(outcome.rawText(), objectMapper))
                .thenReturn(new ClaudeEvaluationStrategy.ParseResult(
                        new SunsetEvaluation(4, 70, 65, "X"), false));
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                .when(jobRunService).logBatchResult(
                        any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                        any(), any(), any(), any(), any(), any(), any(), any());

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

    // ── v2.13.2: sky-tide decomposition ──────────────────────────────────────

    @Test
    @DisplayName("coastal aligned (regular) tide: sky 3 + tide 4 → averaged to 4")
    void parseBatchResponse_coastalAlignedTide_averagesSkyAndTide() {
        LocationEntity location = coastalLocation(50L, "Berwick", "Northumberland");
        ForecastIdentity identity = new ForecastIdentity(50L, DATE, SUNRISE);
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "fc-50-2026-04-16-SUNRISE",
                "{\"rating\":3,\"fiery_sky\":55,\"golden_hour\":60,\"summary\":\"sky\"}",
                new TokenUsage(500, 200, 0, 1000), EvaluationModel.HAIKU);
        when(parsingStrategy.parseEvaluationWithMetadata(outcome.rawText(), objectMapper))
                .thenReturn(new ClaudeEvaluationStrategy.ParseResult(
                        new SunsetEvaluation(3, 55, 60, "sky-only summary"), false));
        when(forecastDataAugmentor.deriveTideContext(location, DATE, SUNRISE))
                .thenReturn(Optional.of(tideContext(true, false, LunarTideType.REGULAR_TIDE)));

        Optional<BatchSuccess> result = handler.parseBatchResponse(
                location, identity, outcome,
                ResultContext.forBatch(99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(result).isPresent();
        // avg(sky 3, tide 4) = 3.5 → 4 (half-up)
        assertThat(result.get().result().rating()).isEqualTo(4);
        assertThat(result.get().result().summary()).isEqualTo("sky-only summary");
    }

    @Test
    @DisplayName("coastal misaligned tide: sky 3 + tide 1 → dragged to 2")
    void parseBatchResponse_coastalMisalignedTide_dragsRating() {
        LocationEntity location = coastalLocation(51L, "Spittal", "Northumberland");
        ForecastIdentity identity = new ForecastIdentity(51L, DATE, SUNRISE);
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "fc-51-2026-04-16-SUNRISE",
                "{\"rating\":3,\"fiery_sky\":55,\"golden_hour\":60,\"summary\":\"sky\"}",
                new TokenUsage(500, 200, 0, 1000), EvaluationModel.HAIKU);
        when(parsingStrategy.parseEvaluationWithMetadata(outcome.rawText(), objectMapper))
                .thenReturn(new ClaudeEvaluationStrategy.ParseResult(
                        new SunsetEvaluation(3, 55, 60, "sky-only summary"), false));
        when(forecastDataAugmentor.deriveTideContext(location, DATE, SUNRISE))
                .thenReturn(Optional.of(tideContext(false, false, LunarTideType.REGULAR_TIDE)));

        Optional<BatchSuccess> result = handler.parseBatchResponse(
                location, identity, outcome,
                ResultContext.forBatch(99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(result).isPresent();
        // avg(sky 3, tide 1) = 2 — a misaligned tide at a tidal location drags the rating
        assertThat(result.get().result().rating()).isEqualTo(2);
    }

    @Test
    @DisplayName("coastal un-derivable tide (data gap): scores on sky alone, not penalised")
    void parseBatchResponse_coastalTideUnderivable_scoresSkyAlone() {
        LocationEntity location = coastalLocation(52L, "Seahouses", "Northumberland");
        ForecastIdentity identity = new ForecastIdentity(52L, DATE, SUNRISE);
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "fc-52-2026-04-16-SUNRISE",
                "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"sky\"}",
                new TokenUsage(500, 200, 0, 1000), EvaluationModel.HAIKU);
        when(parsingStrategy.parseEvaluationWithMetadata(outcome.rawText(), objectMapper))
                .thenReturn(new ClaudeEvaluationStrategy.ParseResult(
                        new SunsetEvaluation(4, 70, 65, "sky-only summary"), false));
        when(forecastDataAugmentor.deriveTideContext(location, DATE, SUNRISE))
                .thenReturn(Optional.empty());

        Optional<BatchSuccess> result = handler.parseBatchResponse(
                location, identity, outcome,
                ResultContext.forBatch(99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(result).isPresent();
        // Tide visitor abstains on a data gap → sky alone (4), never dragged to 1.
        assertThat(result.get().result().rating()).isEqualTo(4);
    }

    @Test
    @DisplayName("sky not forecast (inland, rating null): 1★ + not-forecast summary, no triage")
    void parseBatchResponse_skyNotForecastInland_substitutesOneStar() {
        LocationEntity location = locationWithRegion(53L, "Keswick", "Lake District");
        ForecastIdentity identity = new ForecastIdentity(53L, DATE, SUNRISE);
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "fc-53-2026-04-16-SUNRISE",
                "{\"fiery_sky\":40,\"golden_hour\":45,\"summary\":\"ignored\"}",
                new TokenUsage(500, 200, 0, 1000), EvaluationModel.HAIKU);
        when(parsingStrategy.parseEvaluationWithMetadata(outcome.rawText(), objectMapper))
                .thenReturn(new ClaudeEvaluationStrategy.ParseResult(
                        new SunsetEvaluation(null, 40, 45, "Claude prose to be overridden"), false));

        Optional<BatchSuccess> result = handler.parseBatchResponse(
                location, identity, outcome,
                ResultContext.forBatch(99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(result).isPresent();
        BriefingEvaluationResult res = result.get().result();
        assertThat(res.rating()).isEqualTo(1);
        assertThat(res.summary()).isEqualTo(ForecastResultHandler.SKY_NOT_FORECAST_SUMMARY);
        assertThat(res.headline()).isNull();
        assertThat(res.triageReason()).isNull();
        assertThat(res.triageMessage()).isNull();
        assertThat(res.fierySkyPotential()).isEqualTo(40);
    }

    @Test
    @DisplayName("sky not forecast (coastal): 1★ substituted BEFORE combine — tide never averaged")
    void parseBatchResponse_skyNotForecastCoastal_neverScoresOnTideAlone() {
        LocationEntity location = coastalLocation(54L, "St Marys", "Northumberland");
        ForecastIdentity identity = new ForecastIdentity(54L, DATE, SUNRISE);
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "fc-54-2026-04-16-SUNRISE",
                "{\"fiery_sky\":40,\"golden_hour\":45,\"summary\":\"ignored\"}",
                new TokenUsage(500, 200, 0, 1000), EvaluationModel.HAIKU);
        when(parsingStrategy.parseEvaluationWithMetadata(outcome.rawText(), objectMapper))
                .thenReturn(new ClaudeEvaluationStrategy.ParseResult(
                        new SunsetEvaluation(null, 40, 45, "prose"), false));

        Optional<BatchSuccess> result = handler.parseBatchResponse(
                location, identity, outcome,
                ResultContext.forBatch(99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(result).isPresent();
        assertThat(result.get().result().rating()).isEqualTo(1);
        assertThat(result.get().result().summary())
                .isEqualTo(ForecastResultHandler.SKY_NOT_FORECAST_SUMMARY);
        // The pre-combine branch must fire BEFORE tide re-derivation — proving a coastal
        // sky-empty location can never be scored on tide alone (which would yield 4/5).
        verify(forecastDataAugmentor, never()).deriveTideContext(any(), any(), any());
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

    private LocationEntity coastalLocation(long id, String name, String regionName) {
        LocationEntity loc = locationWithRegion(id, name, regionName);
        loc.setTideType(java.util.Set.of(TideType.HIGH));
        return loc;
    }

    private LocationEntity woodlandBluebellLocation(long id, String name, String regionName) {
        LocationEntity loc = locationWithRegion(id, name, regionName);
        loc.setLocationType(java.util.Set.of(LocationType.BLUEBELL));
        loc.setBluebellExposure(BluebellExposure.WOODLAND);
        return loc;
    }

    /** Builds a {@link TideContext} with the given tight/widened alignment and lunar type. */
    private static TideContext tideContext(boolean tightAligned, boolean widenedAligned,
            LunarTideType lunar) {
        TideSnapshot snapshot = new TideSnapshot(
                TideState.HIGH, null, null, null, null,
                tightAligned, null, null, lunar, null, null, null);
        return new TideContext(snapshot, widenedAligned);
    }
}
