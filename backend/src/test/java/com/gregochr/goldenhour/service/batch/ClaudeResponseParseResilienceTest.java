package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.messages.BatchService;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.CostCalculator;
import com.gregochr.goldenhour.service.ForecastDataAugmentor;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.evaluation.AuroraResultHandler;
import com.gregochr.goldenhour.service.evaluation.ClaudeBatchOutcome;
import com.gregochr.goldenhour.service.evaluation.CustomIdFactory;
import com.gregochr.goldenhour.service.evaluation.EvaluationAbandonmentService;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler.BatchSuccess;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler.ForecastIdentity;
import com.gregochr.goldenhour.service.evaluation.ForecastScoreWriter;
import com.gregochr.goldenhour.service.evaluation.ResultContext;
import com.gregochr.goldenhour.service.evaluation.SunsetEvaluationParser;
import com.gregochr.goldenhour.service.evaluation.visitor.BluebellVisitor;
import com.gregochr.goldenhour.service.evaluation.visitor.RatingCombiner;
import com.gregochr.goldenhour.service.evaluation.visitor.SkyVisitor;
import com.gregochr.goldenhour.service.evaluation.visitor.TideVisitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Parse-resilience contract over checked-in malformed Claude responses
 * ({@code src/test/resources/claude-malformed/}).
 *
 * <p>Every fixture here reproduces a shape that actually reached production:
 * {@code d32e883c} (chain-of-thought instead of JSON — 101 of 109 requests failed to parse),
 * {@code f55ed200} (an unrecognised {@code custom_id} prefix rejected all 64 results of a
 * $0.63 batch), {@code dc770582} (a response truncated at {@code max_tokens}), and
 * {@code 1d838df5} (the doubly-nested batch error envelope, which persisted
 * {@code error_type='unknown'} for every errored row).
 *
 * <p>Each case asserts three things:
 * <ol>
 *   <li><b>the pipeline does not throw</b> — the parser may throw internally, but
 *       {@link ForecastResultHandler#parseBatchResponse} must absorb it;</li>
 *   <li><b>whatever is salvageable is salvaged</b> — pinned field by field, so a future
 *       change to the regex fallback that quietly drops a field fails here;</li>
 *   <li><b>a disposition distinguishable from an honest empty result is recorded</b> — the
 *       lesson of {@code 8fb3ad26}: <em>"because empty picks are indistinguishable from an
 *       honest decline, the drop hid for weeks."</em></li>
 * </ol>
 *
 * <p><b>Honest limit on assertion (iii), pinned by
 * {@link #parseFailureLeavesNoTraceInTheProductSurface()} and
 * {@link #withoutAJobRunIdAParseFailureIsRecordedNowhereAtAll()}.</b> The distinction exists in
 * {@code api_call_log} only. The product surface ({@code cached_evaluation}) carries no
 * result-time disposition at all: a slot whose response failed to parse is simply <em>absent</em>
 * from the region payload, which is byte-identical to never having been submitted. And the
 * {@code api_call_log} write is skipped entirely when the context has no {@code jobRunId}
 * (ForecastResultHandler's {@code persistBatchLog} early-returns), so on that path a parse
 * failure is recorded nowhere. These tests assert the behaviour that exists and name the gap;
 * they do not pretend the capability is there.
 *
 * <p>Fixtures all carry a {@code .json} extension because they are Claude <em>response
 * payloads</em>, which is the directory's contract — several are, by construction, not valid
 * JSON (that is the point of {@code claude-malformed}). They are loaded as raw text, exactly as
 * {@code BatchResultProcessor} hands the assistant text block to the parser. The loader mirrors
 * the {@code cache-golden} / {@code prompt-golden} idiom; there is deliberately no regenerate
 * flag, because these are inputs rather than golden outputs.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeResponseParseResilienceTest {

    private static final String FIXTURE_DIR = "claude-malformed";

    private static final LocalDate DATE = LocalDate.of(2026, 7, 26);
    private static final TargetType SUNSET = TargetType.SUNSET;
    private static final long LOCATION_ID = 42L;
    private static final String CUSTOM_ID = "fc-42-2026-07-26-SUNSET";
    private static final long JOB_RUN_ID = 99L;
    private static final String BATCH_ID = "msgbatch_parse_resilience";

    /**
     * Mirrors the package-private {@code ForecastResultHandler.SKY_NOT_FORECAST_SUMMARY}.
     *
     * <p>Duplicated rather than referenced because this test lives in {@code service.batch} and the
     * constant is package-private to {@code service.evaluation}. That duplication is itself the
     * finding: an honest "Claude declined to score the sky" is discriminated from a genuine 1-star
     * night by <em>this sentence alone</em> — there is no status column behind it.
     */
    private static final String SKY_NOT_FORECAST_SUMMARY =
            "Claude did not forecast the fiery sky and golden hour for this location";

    /** The SDK's own mapper — the one that deserialises real Anthropic batch results. */
    private static final JsonMapper SDK_MAPPER = ObjectMappers.jsonMapper();

    @Mock
    private BriefingEvaluationService briefingEvaluationService;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private ForecastDataAugmentor forecastDataAugmentor;
    @Mock
    private ForecastScoreWriter forecastScoreWriter;
    @Mock
    private ForecastEvaluationRepository forecastEvaluationRepository;

    private ForecastResultHandler handler;

    @BeforeEach
    void setUp() {
        // Real parser, real Jackson-3 mapper, real combiner: the fixtures must be exercised by the
        // production parse path, not by a stub standing in for it.
        handler = new ForecastResultHandler(
                briefingEvaluationService,
                jobRunService,
                new tools.jackson.databind.ObjectMapper(),
                new RatingCombiner(List.of(new SkyVisitor(), new TideVisitor(),
                        new BluebellVisitor())),
                forecastDataAugmentor,
                forecastScoreWriter,
                new SunsetEvaluationParser(),
                forecastEvaluationRepository);
    }

    // ── (i) + (ii) + (iii), table-driven ─────────────────────────────────────

    /**
     * One malformed-response case.
     *
     * @param fixture         file name under {@code claude-malformed/}
     * @param salvaged        whether the handler returns a usable result at all
     * @param loggedSucceeded the {@code api_call_log.succeeded} value expected
     * @param loggedErrorType the {@code api_call_log.error_type} value expected (may be null)
     * @param rating          rating on the salvaged result, or null when none survives
     * @param fierySky        fiery-sky score on the salvaged result, or null
     * @param summary         exact summary on the salvaged result, or null
     * @param headline        exact headline on the salvaged result, or null when the field is lost
     */
    private record MalformedCase(String fixture, boolean salvaged, boolean loggedSucceeded,
                                 String loggedErrorType, Integer rating, Integer fierySky,
                                 String summary, String headline) {

        @Override
        public String toString() {
            return fixture;
        }
    }

    private static Stream<MalformedCase> malformedCases() {
        return Stream.of(
                // Fences on the exact boundaries are stripped, so the STRICT path runs and the
                // response is recorded as an ordinary clean success.
                new MalformedCase("code-fenced.json", true, true, null,
                        4, 72,
                        "Broken mid-level cloud clearing from the west with a clean horizon "
                                + "gap at 113 km.",
                        "Fiery finish over the fells"),

                // Prose BEFORE the fence defeats the ^-anchored strip, so strict parsing fails and
                // the regex fallback silently rescues an otherwise-perfect answer. Correct data,
                // permanently stamped as a degraded parse.
                new MalformedCase("code-fenced-with-preamble.json", true, true,
                        ForecastResultHandler.REGEX_FALLBACK_MARKER,
                        4, 72,
                        "Broken mid-level cloud clearing from the west with a clean horizon "
                                + "gap at 113 km.",
                        "Fiery finish over the fells"),

                // dc770582: cut mid-`summary`. Neither summary pattern can terminate, so the whole
                // slot is lost — the harshest truncation outcome.
                new MalformedCase("truncated-max-tokens.json", false, false, "parse_error",
                        null, null, null, null),

                // Cut AFTER `summary` closed: the fallback rescues the scores and the headline is
                // silently dropped. Recorded as a SUCCESS carrying `regex_fallback`.
                new MalformedCase("truncated-after-summary.json", true, true,
                        ForecastResultHandler.REGEX_FALLBACK_MARKER,
                        3, 55,
                        "Thick low cloud at the western horizon with only a narrow strip of "
                                + "clear sky above it.",
                        null),

                // d32e883c: chain-of-thought prose, no JSON at all.
                new MalformedCase("chain-of-thought-no-json.json", false, false, "parse_error",
                        null, null, null, null),

                // Structurally valid JSON carrying impossible numbers. GAP CLOSED 2026-08-27:
                // RatingValidator.validateScore now nulls the out-of-range fiery_sky (491) the
                // same way the rating guardrail already nulled the 91 (see the dedicated test
                // below for the full before/after on both fields).
                new MalformedCase("rating-out-of-range.json", true, true, null,
                        null, null,
                        "Structurally valid JSON carrying values outside every documented bound.",
                        null),

                // The honest-decline analogue: a parseable response that omitted `rating`.
                // Substituted to 1 star with a fixed sentence, and logged as a clean success.
                new MalformedCase("sky-not-forecast.json", true, true, null,
                        1, 40, SKY_NOT_FORECAST_SUMMARY, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedCases")
    @DisplayName("malformed response: absorbed without throwing, salvaged as far as possible, "
            + "and stamped with a disposition")
    void malformedResponsesAreAbsorbedSalvagedAndDispositioned(MalformedCase testCase) {
        String rawText = readFixture(testCase.fixture());
        LocationEntity location = inlandLocation();
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                CUSTOM_ID, rawText, new TokenUsage(500, 200, 0, 1000), EvaluationModel.HAIKU);

        // (i) the pipeline absorbs it — the parser may throw, the handler must not.
        AtomicReference<Optional<BatchSuccess>> holder = new AtomicReference<>();
        assertThatCode(() -> holder.set(handler.parseBatchResponse(
                location, identity(), outcome, batchContext())))
                .as("(i) %s must not propagate out of the handler", testCase.fixture())
                .doesNotThrowAnyException();
        Optional<BatchSuccess> result = holder.get();

        // (ii) whatever survives is salvaged, field by field.
        assertThat(result.isPresent())
                .as("(ii) %s salvageable?", testCase.fixture())
                .isEqualTo(testCase.salvaged());
        if (testCase.salvaged()) {
            var salvaged = result.orElseThrow().result();
            assertThat(salvaged.rating())
                    .as("(ii) rating salvaged from %s", testCase.fixture())
                    .isEqualTo(testCase.rating());
            assertThat(salvaged.fierySkyPotential())
                    .as("(ii) fiery_sky salvaged from %s", testCase.fixture())
                    .isEqualTo(testCase.fierySky());
            assertThat(salvaged.summary())
                    .as("(ii) summary salvaged from %s", testCase.fixture())
                    .isEqualTo(testCase.summary());
            assertThat(salvaged.headline())
                    .as("(ii) headline salvaged from %s", testCase.fixture())
                    .isEqualTo(testCase.headline());
            assertThat(result.orElseThrow().cacheKey())
                    .isEqualTo("Northumberland Coast|2026-07-26|SUNSET");
        }

        // (iii) the disposition lands on the api_call_log row, joinable to the slot it was for.
        verify(jobRunService).logBatchResult(
                eq(JOB_RUN_ID), eq(BATCH_ID), eq(CUSTOM_ID),
                eq(testCase.loggedSucceeded()), any(String.class),
                eq(testCase.loggedErrorType()), any(),
                any(), any(),
                eq(DATE), eq(SUNSET), any());
    }

    // ── The gap assertion (iii) cannot make ──────────────────────────────────

    @Test
    @DisplayName("KNOWN GAP: a parse failure leaves the product surface identical to a slot that "
            + "was never submitted")
    void parseFailureLeavesNoTraceInTheProductSurface() {
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                CUSTOM_ID, readFixture("truncated-max-tokens.json"),
                new TokenUsage(500, 200, 0, 1000), EvaluationModel.HAIKU);

        Optional<BatchSuccess> result = handler.parseBatchResponse(
                inlandLocation(), identity(), outcome, batchContext());

        assertThat(result)
                .as("a parse failure yields nothing to write to cached_evaluation")
                .isEmpty();
        // Nothing reaches the cache: the slot's absence from the region payload is the ONLY
        // signal, and absence there is indistinguishable from never-submitted, eligibility-skipped
        // or triaged. There is no result-time disposition column — forecast_run_disposition is
        // written at collection time and still reads EVALUATED for this slot.
        verifyNoInteractions(briefingEvaluationService);
        // The failure IS visible in api_call_log, and (unlike BatchResultProcessor's inline
        // MALFORMED_ID / NO_TEXT rows, which pass null date+type) it carries enough context to be
        // joined back to the slot it was for.
        verify(jobRunService).logBatchResult(
                eq(JOB_RUN_ID), eq(BATCH_ID), eq(CUSTOM_ID),
                eq(false), eq("PARSE_FAILED"),
                eq("parse_error"), contains("Failed to parse evaluation response"),
                eq((EvaluationModel) null), eq((TokenUsage) null),
                eq(DATE), eq(SUNSET), eq((String) null));
    }

    @Test
    @DisplayName("KNOWN GAP: with no jobRunId on the context, a parse failure is recorded nowhere "
            + "at all")
    void withoutAJobRunIdAParseFailureIsRecordedNowhereAtAll() {
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                CUSTOM_ID, readFixture("chain-of-thought-no-json.json"),
                new TokenUsage(500, 200, 0, 1000), EvaluationModel.HAIKU);
        ResultContext noJobRun = ResultContext.forBatch(null, BATCH_ID, null, null);

        Optional<BatchSuccess> result = handler.parseBatchResponse(
                inlandLocation(), identity(), outcome, noJobRun);

        assertThat(result).isEmpty();
        // persistBatchLog early-returns on a null jobRunId, so the ONLY record of a lost,
        // paid-for evaluation is a WARN line in the application log.
        verifyNoInteractions(jobRunService);
        verifyNoInteractions(briefingEvaluationService);
    }

    @Test
    @DisplayName("KNOWN GAP: an honest decline and a genuine 1-star night differ only by a prose "
            + "string")
    void honestDeclineIsDiscriminatedOnlyByProse() {
        ClaudeBatchOutcome declined = ClaudeBatchOutcome.success(
                CUSTOM_ID, readFixture("sky-not-forecast.json"),
                new TokenUsage(500, 200, 0, 1000), EvaluationModel.HAIKU);

        var result = handler.parseBatchResponse(
                inlandLocation(), identity(), declined, batchContext()).orElseThrow().result();

        assertThat(result.rating())
                .as("substituted to the same 1 star a genuinely poor night earns")
                .isEqualTo(1);
        assertThat(result.summary())
                .as("the sentence is the entire discriminator — no status field carries it")
                .isEqualTo(SKY_NOT_FORECAST_SUMMARY);
        assertThat(result.triageReason())
                .as("not marked as a stand-down either")
                .isNull();
        // And the log row is a clean success, indistinguishable from a scored slot.
        verify(jobRunService).logBatchResult(
                eq(JOB_RUN_ID), eq(BATCH_ID), eq(CUSTOM_ID),
                eq(true), eq("SUCCESS"),
                eq(null), eq(null),
                eq(EvaluationModel.HAIKU), any(TokenUsage.class),
                eq(DATE), eq(SUNSET), any(String.class));
    }

    @Test
    @DisplayName("GAP CLOSED 2026-08-27: out-of-range 0-100 scores are rejected as null — "
            + "RatingValidator.validateScore is now wired into both parse paths")
    void outOfRangeScoresAreRejectedAsNull() {
        // Closed deliberately: the frontend clamps fiery_sky/golden_hour to 0-100
        // (scoreRamp.js starFromScore), so an out-of-range value like 491 rendered at the TOP
        // of the ramp — full score bar, maximal colour — while the star rating (already
        // validated) went null. That combination was the worst-looking failure mode available:
        // no star, but the best-looking score bar on the screen, from garbage. Rejecting to
        // null loses nothing diagnostically, because the raw Claude response is still persisted
        // by jobRunService.logBatchResult on this same success path (asserted below).
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                CUSTOM_ID, readFixture("rating-out-of-range.json"),
                new TokenUsage(500, 200, 0, 1000), EvaluationModel.HAIKU);

        var result = handler.parseBatchResponse(
                inlandLocation(), identity(), outcome, batchContext()).orElseThrow().result();

        assertThat(result.rating())
                .as("the rating guardrail rejects 91 rather than clamping it")
                .isNull();
        assertThat(result.fierySkyPotential())
                .as("fiery_sky=491 is now rejected as null by RatingValidator.validateScore")
                .isNull();
        assertThat(result.goldenHourPotential())
                .as("golden_hour=-3 is now rejected as null for the same reason")
                .isNull();
        // …the rest of a usable evaluation still lands: one bad component nulls that component,
        // not the whole response — so the row is still logged as a clean success, and the raw
        // response stays diagnosable in api_call_log.
        verify(jobRunService).logBatchResult(
                eq(JOB_RUN_ID), eq(BATCH_ID), eq(CUSTOM_ID),
                eq(true), eq("SUCCESS"),
                eq(null), eq(null),
                eq(EvaluationModel.HAIKU), any(TokenUsage.class),
                eq(DATE), eq(SUNSET), any(String.class));
    }

    // ── f55ed200: an unknown custom-id prefix discards a perfectly good answer ──

    @Test
    @DisplayName("f55ed200: an unrecognised custom-id prefix discards a parseable evaluation and "
            + "fails the whole batch")
    void unknownCustomIdPrefixDiscardsAGoodEvaluationAndFailsTheBatch() {
        MessageBatchIndividualResponse response =
                readIndividualResponse("unknown-custom-id-prefix.json");
        assertThat(response.customId()).startsWith("xyz-");

        // The body Anthropic charged for is a perfectly good evaluation — proving the loss is in
        // custom-id dispatch, not in parsing. This is the shape that cost $0.63 for zero results.
        assertThatThrownBy(() -> CustomIdFactory.parse(response.customId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown custom ID prefix");

        ForecastResultHandler mockHandler = mock(ForecastResultHandler.class);
        JobRunService batchJobRunService = mock(JobRunService.class);
        ForecastBatchRepository batchRepository = mock(ForecastBatchRepository.class);
        CostCalculator costCalculator = mock(CostCalculator.class);
        when(costCalculator.calculateCostMicroDollars(
                eq(EvaluationModel.HAIKU), any(TokenUsage.class), eq(true))).thenReturn(0L);

        BatchResultProcessor processor = new BatchResultProcessor(
                stubbedClient(response), batchRepository, mock(LocationRepository.class),
                batchJobRunService, costCalculator, mockHandler, mock(AuroraResultHandler.class),
                mock(EvaluationAbandonmentService.class));

        ForecastBatchEntity batch = new ForecastBatchEntity(
                BATCH_ID, BatchType.FORECAST, 1, Instant.now().plusSeconds(86_400));
        batch.setJobRunId(JOB_RUN_ID);

        processor.processResults(batch);

        // (ii) nothing is salvaged — the handler is never even offered the response.
        verify(mockHandler, never()).parseBatchResponse(any(), any(), any(), any());
        // (iii) the disposition is recorded, but WITHOUT the date/target type, because they live
        // inside the custom id that could not be decoded. These rows cannot be joined back to the
        // slots they were for.
        verify(batchJobRunService).logBatchResult(
                eq(JOB_RUN_ID), eq(BATCH_ID), eq("xyz-42-2026-07-26-SUNSET"),
                eq(false), eq("MALFORMED_ID"),
                eq("parse_error"), eq("malformed customId"),
                eq((EvaluationModel) null), eq((TokenUsage) null),
                eq((LocalDate) null), eq((TargetType) null));

        ArgumentCaptor<ForecastBatchEntity> saved =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus())
                .as("zero successes fails the batch even though Anthropic completed every request")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(saved.getValue().getErroredCount()).isEqualTo(1);
        assertThat(saved.getValue().getTotalOutputTokens())
                .as("the tokens were still billed")
                .isEqualTo(200L);
    }

    // ── 1d838df5: the doubly-nested error envelope ───────────────────────────

    @Test
    @DisplayName("1d838df5: the nested error envelope resolves to its typed error, not 'unknown'")
    void nestedErrorEnvelopeResolvesToItsTypedError() {
        MessageBatchIndividualResponse response =
                readIndividualResponse("error-envelope-nested.json");

        String[] detail = BatchResultProcessor.describeFailedResult(response.result());

        // The negative assertion is the one that catches drift: BatchResultProcessor wraps
        // describeFailedResult in a catch-all that substitutes "unknown", so a test asserting
        // only "does not throw" would have stayed green through 1d838df5.
        assertThat(detail[0])
                .as("a wrongly-nested envelope degrades every errored row to 'unknown'")
                .isNotEqualTo("unknown")
                .isEqualTo("overloaded_error");
        assertThat(detail[1]).isEqualTo("Overloaded");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds an {@link AnthropicClient} whose batch-results stream yields exactly the given
     * response, so {@link BatchResultProcessor} runs its real loop over a fixture the SDK
     * itself deserialised.
     */
    private static AnthropicClient stubbedClient(MessageBatchIndividualResponse response) {
        AnthropicClient client = mock(AnthropicClient.class);
        MessageService messageService = mock(MessageService.class);
        BatchService batchService = mock(BatchService.class);
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> stream = mock(StreamResponse.class);
        when(client.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);
        when(batchService.resultsStreaming(BATCH_ID)).thenReturn(stream);
        when(stream.stream()).thenReturn(Stream.of(response));
        return client;
    }

    /** Deserialises a fixture through the SDK's own mapper and validates it eagerly. */
    private static MessageBatchIndividualResponse readIndividualResponse(String name) {
        try {
            return SDK_MAPPER.readValue(
                    readFixture(name), MessageBatchIndividualResponse.class).validate();
        } catch (IOException e) {
            return fail("Fixture " + name + " is not a valid Anthropic batch result line", e);
        }
    }

    private ResultContext batchContext() {
        return ResultContext.forBatch(JOB_RUN_ID, BATCH_ID, null, null);
    }

    private ForecastIdentity identity() {
        return new ForecastIdentity(LOCATION_ID, DATE, SUNSET, null);
    }

    /** Inland (non-tidal) so the tide visitor abstains and the sky score alone drives the rating. */
    private static LocationEntity inlandLocation() {
        LocationEntity location = new LocationEntity();
        location.setId(LOCATION_ID);
        location.setName("Bamburgh");
        RegionEntity region = new RegionEntity();
        region.setName("Northumberland Coast");
        location.setRegion(region);
        return location;
    }

    /**
     * Reads a fixture as raw UTF-8 text — the same form {@code BatchResultProcessor} extracts
     * from the assistant text block. Mirrors the {@code cache-golden} / {@code prompt-golden}
     * loader; no regenerate flag, because these fixtures are inputs, not golden outputs.
     */
    private static String readFixture(String name) {
        try (InputStream in = ClaudeResponseParseResilienceTest.class
                .getResourceAsStream("/" + FIXTURE_DIR + "/" + name)) {
            if (in == null) {
                return fail("Missing fixture: src/test/resources/" + FIXTURE_DIR + "/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("Could not read fixture " + name, e);
        }
    }
}
