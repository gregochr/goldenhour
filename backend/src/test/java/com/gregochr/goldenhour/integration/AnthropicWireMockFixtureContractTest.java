package com.gregochr.goldenhour.integration;

import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.models.ErrorObject;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.models.messages.batches.MessageBatchRequestCounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.gregochr.goldenhour.integration.AnthropicWireMockFixtures.BatchResultFixture;
import com.gregochr.goldenhour.integration.AnthropicWireMockFixtures.RequestCounts;
import com.gregochr.goldenhour.integration.AnthropicWireMockFixtures.ResultKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Guards the test double itself against drifting away from the wire shapes Anthropic
 * actually sends — the failure mode that produced {@code 1d838df5}.
 *
 * <p>{@link AnthropicWireMockFixtures} hands WireMock hand-concatenated JSON standing in
 * for the Batch API. At {@code 1d838df5} that JSON nested the error envelope wrongly, so
 * every batch test exercised a shape Anthropic never sends: the production getter chain
 * {@code result.asErrored().error().error()} threw, {@code BatchResultProcessor} swallowed
 * it into its {@code "unknown"} fallback, and {@code error_type} persisted as
 * {@code 'unknown'} for every errored row. The suite stayed green against a fiction, and
 * the fix was test-only — nothing guards it recurring.
 *
 * <p>This test closes that by round-tripping every fixture through the SDK's <em>own</em>
 * Jackson mapper ({@link ObjectMappers#jsonMapper()}, a Jackson 2 mapper — the SDK carries
 * Jackson 2 while the application runs on Jackson 3) into the SDK's own response types,
 * then walking the same getter chains production walks. The SDK's models are lazily
 * validated, so deserialising alone proves little; each check therefore calls
 * {@code validate()} and reads the fields production reads.
 *
 * <p>Fixtures are discovered <strong>reflectively</strong> rather than listed by hand: a
 * hardcoded list silently stops covering fixtures added later, which is the same
 * silent-decay this tier exists to prevent. Discovered factories are invoked over a
 * cartesian expansion of registered per-parameter arguments, and any parameter with no
 * registered argument — or any new public factory returning an unrecognised type — fails
 * the test loudly rather than being skipped.
 *
 * <p><strong>Honest limit:</strong> this proves the fixture matches the SDK's expectations.
 * It cannot prove the SDK's expectations match Anthropic's live API — only a real-API test
 * can do that.
 */
class AnthropicWireMockFixtureContractTest {

    /** The SDK's own mapper — the one that parses real Anthropic responses in production. */
    private static final JsonMapper MAPPER = ObjectMappers.jsonMapper();

    private static final String BATCH_ID = "msgbatch_01FixtureContract";
    private static final String CUSTOM_ID = "fc-42-2026-07-26-SUNSET";
    private static final String MODEL_ID = "claude-haiku-4-5-20251001";

    /**
     * Deliberately carries double quotes: the assistant text is escaped twice on its way to
     * the wire (once into the evaluation JSON, once into the JSONL envelope), and a broken
     * escape produces a body that no longer deserialises.
     *
     * <p>A backslash is <em>not</em> included, because
     * {@code AnthropicWireMockFixtures.succeededWithEvaluation} escapes only quotes when it
     * builds the evaluation JSON and mangles one — see
     * {@link #summaryContainingABackslashIsSilentlyCorruptedByTheFixtureDouble}.
     */
    private static final String SUMMARY = "Thin cirrus \"strip\" at 226 km, far-field sample";

    /**
     * A summary whose backslash forms a <em>valid</em> JSON escape ({@code \t}) once the
     * fixture drops it into the evaluation JSON unescaped. See
     * {@link #summaryContainingABackslashIsSilentlyCorruptedByTheFixtureDouble}.
     */
    private static final String BACKSLASH_SUMMARY = "Shot from C:\\temp overlook";

    /** What {@link #BACKSLASH_SUMMARY} actually reads back as: {@code \t} decoded to a tab. */
    private static final String BACKSLASH_SUMMARY_CORRUPTED = "Shot from C:\temp overlook";

    private static final RequestCounts COUNTS = new RequestCounts(1, 2, 3, 4, 5);

    /** The message {@code erroredOverloaded} hardcodes; reused so both paths assert the same text. */
    private static final String OVERLOAD_MESSAGE = "Service temporarily overloaded";

    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String JSONL_CONTENT_TYPE = "application/x-jsonl";

    /**
     * The nine Anthropic error variants {@code BatchResultProcessor.resolveErrorType} and
     * {@code resolveErrorMessage} dispatch on, keyed by the wire {@code type} discriminator.
     * Any fixture whose envelope nests wrongly matches none of these, which is precisely
     * how {@code error_type} degraded to {@code 'unknown'} at {@code 1d838df5}.
     */
    private static final List<ErrorVariant> ERROR_VARIANTS = List.of(
            new ErrorVariant("overloaded_error", ErrorObject::isOverloadedError,
                    e -> e.asOverloadedError().message()),
            new ErrorVariant("invalid_request_error", ErrorObject::isInvalidRequestError,
                    e -> e.asInvalidRequestError().message()),
            new ErrorVariant("rate_limit_error", ErrorObject::isRateLimitError,
                    e -> e.asRateLimitError().message()),
            new ErrorVariant("authentication_error", ErrorObject::isAuthenticationError,
                    e -> e.asAuthenticationError().message()),
            new ErrorVariant("billing_error", ErrorObject::isBillingError,
                    e -> e.asBillingError().message()),
            new ErrorVariant("permission_error", ErrorObject::isPermissionError,
                    e -> e.asPermissionError().message()),
            new ErrorVariant("not_found_error", ErrorObject::isNotFoundError,
                    e -> e.asNotFoundError().message()),
            new ErrorVariant("timeout_error", ErrorObject::isTimeoutError,
                    e -> e.asTimeoutError().message()),
            new ErrorVariant("api_error", ErrorObject::isApiError,
                    e -> e.asApiError().message()));

    /**
     * Arguments supplied to discovered factory methods, keyed by parameter name (the build
     * retains {@code -parameters}). A name mapped to several values expands the cartesian
     * product, so every processing status and every error type is exercised.
     */
    private static final Map<String, List<Object>> ARGUMENTS_BY_PARAMETER_NAME = Map.ofEntries(
            Map.entry("batchId", List.of(BATCH_ID)),
            Map.entry("customId", List.of(CUSTOM_ID)),
            Map.entry("processingStatus", List.of("in_progress", "canceling", "ended")),
            Map.entry("counts", List.of(COUNTS)),
            Map.entry("rating", List.of(4)),
            Map.entry("fierySky", List.of(72)),
            Map.entry("goldenHour", List.of(61)),
            Map.entry("summary", List.of(SUMMARY)),
            Map.entry("modelId", List.of(MODEL_ID)),
            Map.entry("errorMessage", List.of(OVERLOAD_MESSAGE)),
            Map.entry("errorType", ERROR_VARIANTS.stream()
                    .map(ErrorVariant::wireType)
                    .map(Object.class::cast)
                    .toList()));

    // ---------------------------------------------------------------------
    // The reflective sweep — every fixture, every SDK type
    // ---------------------------------------------------------------------

    /**
     * Deserialises every response body every discovered stub factory produces into the SDK
     * type production receives, and validates it.
     *
     * @return one dynamic test per (factory, argument-vector) pair
     */
    @TestFactory
    @DisplayName("every stubbed response body deserialises and validates as the SDK's own type")
    Stream<DynamicTest> everyStubbedResponseBodyDeserialisesIntoTheSdkResponseTypes() {
        return discoverStubBodies().stream()
                .map(stub -> DynamicTest.dynamicTest(
                        stub.label() + " -> " + stub.contentType(),
                        () -> assertBodyMatchesSdkContract(stub)));
    }

    /**
     * The sweep above is only as good as its discovery. If it found nothing it would pass
     * vacuously, so pin that both fixture families and both result kinds were reached.
     */
    @Test
    @DisplayName("reflective discovery reaches both stub families and both result kinds")
    void reflectiveDiscoveryIsNonEmptyAndSpansBothContentTypesAndBothResultKinds() {
        List<BatchResultFixture> fixtures = discoverFixtures();
        assertThat(fixtures)
                .as("no BatchResultFixture factories discovered — the sweep would pass vacuously")
                .isNotEmpty();
        assertThat(fixtures).extracting(BatchResultFixture::kind)
                .contains(ResultKind.SUCCEEDED, ResultKind.ERRORED);

        List<StubBody> bodies = discoverStubBodies();
        assertThat(bodies)
                .as("no MappingBuilder factories discovered — the sweep would pass vacuously")
                .isNotEmpty();
        assertThat(bodies).extracting(StubBody::contentType)
                .contains(JSON_CONTENT_TYPE, JSONL_CONTENT_TYPE);
    }

    /**
     * A new public factory returning some third type would be invisible to the sweep. Fail
     * on it here rather than letting coverage decay silently.
     */
    @Test
    @DisplayName("every public factory on the fixture class returns a type this contract covers")
    void everyPublicFixtureFactoryReturnsATypeTheSweepUnderstands() {
        List<String> uncovered = publicStaticFactories()
                .filter(m -> !MappingBuilder.class.equals(m.getReturnType()))
                .filter(m -> !BatchResultFixture.class.equals(m.getReturnType()))
                .map(m -> m.getName() + " -> " + m.getReturnType().getSimpleName())
                .toList();

        assertThat(uncovered)
                .as("new public fixture factories must return MappingBuilder or BatchResultFixture "
                        + "so this contract test discovers them")
                .isEmpty();
    }

    // ---------------------------------------------------------------------
    // The specific drift the incidents produced
    // ---------------------------------------------------------------------

    /**
     * The {@code 1d838df5} regression, stated as an assertion: for each of the nine
     * Anthropic error types, the doubly-nested envelope must survive
     * {@code result.asErrored().error().error()} and resolve to exactly that typed variant.
     */
    @Test
    @DisplayName("1d838df5: errored envelope resolves to the requested typed error, never 'unknown'")
    void erroredFixtureResolvesToExactlyTheRequestedTypedErrorAndNeverUnknown() {
        for (ErrorVariant variant : ERROR_VARIANTS) {
            String message = "boom: " + variant.wireType();
            BatchResultFixture fixture =
                    AnthropicWireMockFixtures.erroredApi(CUSTOM_ID, variant.wireType(), message);

            MessageBatchIndividualResponse response =
                    readIndividualResponse(fixture.toJsonLine());
            assertThat(response.result().isErrored()).as("%s is an errored result", variant.wireType()).isTrue();

            ErrorObject error = response.result().asErrored().error().error();

            List<String> matchedVariants = ERROR_VARIANTS.stream()
                    .filter(candidate -> candidate.matches().test(error))
                    .map(ErrorVariant::wireType)
                    .toList();
            assertThat(matchedVariants)
                    .as("resolveErrorType would return %s for wire type '%s'",
                            matchedVariants.isEmpty() ? "\"unknown\"" : matchedVariants, variant.wireType())
                    .containsExactly(variant.wireType());
            assertThat(variant.message().apply(error)).isEqualTo(message);
        }
    }

    /**
     * Proves the assertion above is load-bearing rather than vacuous, by feeding it the exact
     * envelope {@code 1d838df5} shipped: the typed error placed directly under
     * {@code result.error} with no outer {@code {type:"error"}} wrapper. The SDK accepts the
     * line and only fails when the getter chain is walked — which is why
     * {@code BatchResultProcessor}'s catch turned it into {@code error_type='unknown'} rather
     * than into a loud failure.
     */
    @Test
    @DisplayName("1d838df5: the singly-nested envelope that shipped still fails this contract")
    void singlyNestedErrorEnvelopeIsRejectedSoTheGuardIsNotVacuous() {
        String regressedLine = "{\"custom_id\":\"" + CUSTOM_ID + "\","
                + "\"result\":{\"type\":\"errored\",\"error\":{"
                + "\"type\":\"overloaded_error\",\"message\":\"" + OVERLOAD_MESSAGE + "\"}}}";

        MessageBatchIndividualResponse response = assertDoesNotThrow(
                () -> MAPPER.readValue(regressedLine, MessageBatchIndividualResponse.class),
                "the SDK accepts the mis-nested line lazily — that is why it hid");

        assertThatThrownBy(() -> response.validate().result().asErrored().error().error())
                .as("the mis-nested envelope must not silently resolve to a typed error")
                .isInstanceOf(AnthropicInvalidDataException.class);
    }

    /**
     * {@code erroredOverloaded} is the fixture the batch retry tests lean on; pin its type
     * and message explicitly rather than only via the generic sweep.
     */
    @Test
    @DisplayName("the overloaded fixture reads back as overloaded_error with its message intact")
    void overloadedFixtureExposesTheOverloadedErrorTypeAndMessage() {
        MessageBatchIndividualResponse response =
                readIndividualResponse(AnthropicWireMockFixtures.erroredOverloaded(CUSTOM_ID).toJsonLine());

        ErrorObject error = response.result().asErrored().error().error();

        assertThat(response.customId()).isEqualTo(CUSTOM_ID);
        assertThat(error.isOverloadedError()).isTrue();
        assertThat(error.asOverloadedError().message()).isEqualTo(OVERLOAD_MESSAGE);
    }

    /**
     * The succeeded shape, read exactly as {@code BatchResultProcessor} reads it: message
     * from the succeeded result, text from the first text content block, usage totals for
     * cost tracking. Also proves the double escaping survives a round trip — the extracted
     * text block must itself be valid JSON, because that is what the sky parser is handed.
     */
    @Test
    @DisplayName("the succeeded fixture round-trips model, text and usage through the production getters")
    void succeededFixtureExposesModelTextAndUsageThroughTheProductionGetterChain() throws Exception {
        BatchResultFixture fixture = AnthropicWireMockFixtures.succeededWithEvaluation(
                CUSTOM_ID, 4, 72, 61, SUMMARY, MODEL_ID);

        MessageBatchIndividualResponse response = readIndividualResponse(fixture.toJsonLine());

        assertThat(response.customId()).isEqualTo(CUSTOM_ID);
        assertThat(response.result().isSucceeded()).isTrue();

        Message message = response.result().asSucceeded().message();
        assertThat(message.model().asString()).isEqualTo(MODEL_ID);
        assertThat(message.usage().inputTokens()).isEqualTo(1500L);
        assertThat(message.usage().outputTokens()).isEqualTo(50L);
        assertThat(message.usage().cacheReadInputTokens()).contains(0L);
        assertThat(message.usage().cacheCreationInputTokens()).contains(1400L);

        JsonNode evaluation = MAPPER.readTree(firstTextBlock(response));
        assertThat(evaluation.get("rating").asInt()).isEqualTo(4);
        assertThat(evaluation.get("fiery_sky").asInt()).isEqualTo(72);
        assertThat(evaluation.get("golden_hour").asInt()).isEqualTo(61);
        assertThat(evaluation.get("summary").asText())
                .as("double escaping in toJsonLine must survive the round trip")
                .isEqualTo(SUMMARY);
    }

    /**
     * KNOWN GAP IN THE DOUBLE — this test pins current behaviour, it does not endorse it.
     *
     * <p>{@code succeededWithEvaluation} escapes only {@code "} when it interpolates the
     * summary into the evaluation JSON ({@code summary.replace("\"", "\\\"")}), so a
     * backslash reaches the text block unescaped. {@code toJsonLine} one layer up gets this
     * right ({@code .replace("\\", "\\\\").replace("\"", "\\\"")}); the inner interpolation
     * needs the same pair.
     *
     * <p>The consequence is worse than a parse error, which is why it is worth an assertion
     * rather than a comment: a backslash that happens to form a valid JSON escape —
     * {@code \t}, {@code \n}, {@code \b} — yields a text block that parses <em>cleanly</em>
     * and silently returns different text than the fixture asked for. A test asserting on a
     * summary would fail for a reason with nothing to do with the code under test, and a
     * test not asserting on it would never notice. Only {@code \} followed by a
     * non-escape character fails loudly.
     *
     * <p>Fixing it is a one-line change to {@code AnthropicWireMockFixtures}, which this
     * slice does not own; when it lands, this test fails and should be deleted.
     */
    @Test
    @DisplayName("KNOWN GAP: a backslash in the summary is silently corrupted by the fixture double")
    void summaryContainingABackslashIsSilentlyCorruptedByTheFixtureDouble() throws Exception {
        BatchResultFixture fixture = AnthropicWireMockFixtures.succeededWithEvaluation(
                CUSTOM_ID, 4, 72, 61, BACKSLASH_SUMMARY, MODEL_ID);

        MessageBatchIndividualResponse response = readIndividualResponse(fixture.toJsonLine());
        String text = firstTextBlock(response);

        JsonNode evaluation = assertDoesNotThrow(() -> MAPPER.readTree(text),
                "the corrupted text block still parses — that is precisely why this hides");
        assertThat(evaluation.get("summary").asText())
                .as("the fixture leaves the backslash unescaped, so \\t decodes to a tab")
                .isEqualTo(BACKSLASH_SUMMARY_CORRUPTED)
                .isNotEqualTo(BACKSLASH_SUMMARY);
    }

    /**
     * The {@code f49959dd} tripwire. {@code ProcessingStatus} is a Kotlin-compiled SDK class,
     * not a Java enum: the SDK deserialises each response into a fresh instance, so
     * {@code ==} never matches and "ended" batches were polled forever.
     * {@code BatchPollingService} compares with {@code equals()} for exactly this reason.
     * The {@code isNotSameAs} half is what makes the whole class of bug visible rather than
     * just this instance of it — if a future SDK starts interning, this test says so.
     */
    @Test
    @DisplayName("f49959dd: deserialised ProcessingStatus equals the constant but is a different instance")
    void deserialisedProcessingStatusMustBeComparedWithEqualsNotReferenceIdentity() {
        MessageBatch ended = readMessageBatch(
                bodyOf(AnthropicWireMockFixtures.stubBatchRetrieve(BATCH_ID, "ended", COUNTS)));

        assertThat(ended.processingStatus()).isEqualTo(MessageBatch.ProcessingStatus.ENDED);
        assertThat(ended.processingStatus())
                .as("reference equality is why f49959dd retried ended batches forever")
                .isNotSameAs(MessageBatch.ProcessingStatus.ENDED);
        assertThat(ended.endedAt()).as("an ended batch carries ended_at").isPresent();
        assertThat(ended.resultsUrl()).as("an ended batch carries results_url").isPresent();
    }

    /**
     * A batch still in progress must not advertise results; polling reads
     * {@code processingStatus} before it reads {@code resultsUrl}, and an in-progress stub
     * that carried a results URL would let a broken poller pass.
     */
    @Test
    @DisplayName("an in-progress batch stub carries no results_url and no ended_at")
    void inProgressBatchStubExposesNoResultsUrlAndNoEndedAt() {
        MessageBatch created = readMessageBatch(bodyOf(AnthropicWireMockFixtures.stubBatchCreate(BATCH_ID)));

        assertThat(created.id()).isEqualTo(BATCH_ID);
        assertThat(created.processingStatus()).isEqualTo(MessageBatch.ProcessingStatus.IN_PROGRESS);
        assertThat(created.resultsUrl()).isEmpty();
        assertThat(created.endedAt()).isEmpty();
        assertThat(created.requestCounts().processing()).isZero();
    }

    /**
     * The five request counters must land on the fields they were given, in order. They are
     * distinct values here precisely so a transposition in {@code messageBatchJson} — which
     * writes them out by hand — cannot pass.
     */
    @Test
    @DisplayName("request_counts round-trips all five counters onto the right SDK fields")
    void requestCountsRoundTripOntoTheMatchingSdkAccessors() {
        MessageBatch batch = readMessageBatch(
                bodyOf(AnthropicWireMockFixtures.stubBatchRetrieve(BATCH_ID, "ended", COUNTS)));

        MessageBatchRequestCounts counts = batch.requestCounts();
        assertThat(counts.processing()).isEqualTo(COUNTS.processing());
        assertThat(counts.succeeded()).isEqualTo(COUNTS.succeeded());
        assertThat(counts.errored()).isEqualTo(COUNTS.errored());
        assertThat(counts.canceled()).isEqualTo(COUNTS.canceled());
        assertThat(counts.expired()).isEqualTo(COUNTS.expired());
    }

    /**
     * The stubbed timestamps must stay in the order the poller assumes, or a batch would
     * read as expired the moment the fixture is built.
     */
    @Test
    @DisplayName("stub timestamps keep created_at < ended_at < now < expires_at")
    void stubbedBatchTimestampsAreOrderedSoTheBatchNeverReadsAsAlreadyExpired() {
        MessageBatch ended = readMessageBatch(
                bodyOf(AnthropicWireMockFixtures.stubBatchRetrieve(BATCH_ID, "ended", COUNTS)));

        assertThat(ended.createdAt()).isBefore(ended.endedAt().orElseThrow());
        assertThat(ended.endedAt().orElseThrow()).isBefore(ended.expiresAt());
        assertThat(ended.expiresAt()).isAfter(OffsetDateTime.now());
    }

    // ---------------------------------------------------------------------
    // Deserialisation helpers
    // ---------------------------------------------------------------------

    private static void assertBodyMatchesSdkContract(StubBody stub) {
        assertThat(stub.body()).as("%s produced no response body", stub.label()).isNotBlank();

        switch (stub.contentType()) {
            case JSON_CONTENT_TYPE -> {
                MessageBatch batch = readMessageBatch(stub.body());
                assertThat(batch.id()).isEqualTo(BATCH_ID);
                assertThat(batch.processingStatus().known())
                        .as("%s stubs a processing_status the SDK does not know", stub.label())
                        .isNotNull();
                assertThat(countersOf(batch))
                        .as("%s stubs request_counts the SDK cannot read as longs", stub.label())
                        .allSatisfy(counter -> assertThat(counter).isNotNegative());
            }
            case JSONL_CONTENT_TYPE -> {
                List<String> lines = stub.body().lines().filter(line -> !line.isBlank()).toList();
                assertThat(lines).as("%s streamed an empty JSONL body", stub.label()).isNotEmpty();
                for (String line : lines) {
                    MessageBatchIndividualResponse response = readIndividualResponse(line);
                    assertThat(response.customId()).isEqualTo(CUSTOM_ID);
                    assertThat(response.result().isSucceeded() || response.result().isErrored())
                            .as("%s produced a result that is neither succeeded nor errored", stub.label())
                            .isTrue();
                }
            }
            default -> fail("%s responds with content type '%s', which this contract test does not "
                    + "know how to deserialise. Register the SDK type for it.",
                    stub.label(), stub.contentType());
        }
    }

    private static List<Long> countersOf(MessageBatch batch) {
        MessageBatchRequestCounts counts = batch.requestCounts();
        return List.of(counts.processing(), counts.succeeded(), counts.errored(),
                counts.canceled(), counts.expired());
    }

    /**
     * Extracts the assistant text exactly as {@code BatchResultProcessor.extractTextFromMessage}
     * does — the first {@code text} content block, which is what the sky parser is handed.
     */
    private static String firstTextBlock(MessageBatchIndividualResponse response) {
        String text = response.result().asSucceeded().message().content().stream()
                .filter(ContentBlock::isText)
                .map(ContentBlock::asText)
                .map(TextBlock::text)
                .findFirst()
                .orElse(null);
        assertThat(text).as("BatchResultProcessor takes the first text block").isNotNull();
        return text;
    }

    private static MessageBatch readMessageBatch(String body) {
        try {
            return MAPPER.readValue(body, MessageBatch.class).validate();
        } catch (RuntimeException | IOException e) {
            return fail("MessageBatch fixture does not match the SDK's wire contract: " + body, e);
        }
    }

    private static MessageBatchIndividualResponse readIndividualResponse(String jsonLine) {
        try {
            return MAPPER.readValue(jsonLine, MessageBatchIndividualResponse.class).validate();
        } catch (RuntimeException | IOException e) {
            return fail("Batch result fixture does not match the SDK's wire contract: " + jsonLine, e);
        }
    }

    private static String bodyOf(MappingBuilder builder) {
        return builder.build().getResponse().getBody();
    }

    // ---------------------------------------------------------------------
    // Reflective discovery
    // ---------------------------------------------------------------------

    private static Stream<Method> publicStaticFactories() {
        return Arrays.stream(AnthropicWireMockFixtures.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> Modifier.isStatic(m.getModifiers()))
                .filter(m -> !m.isSynthetic());
    }

    private static List<Method> factoriesReturning(Class<?> returnType) {
        return publicStaticFactories()
                .filter(m -> returnType.equals(m.getReturnType()))
                .sorted(Comparator.comparing(Method::getName).thenComparingInt(Method::getParameterCount))
                .toList();
    }

    private static List<BatchResultFixture> discoverFixtures() {
        List<BatchResultFixture> fixtures = new ArrayList<>();
        for (Method method : factoriesReturning(BatchResultFixture.class)) {
            for (Object[] arguments : argumentVectors(method, List.of())) {
                fixtures.add((BatchResultFixture) invoke(method, arguments));
            }
        }
        return fixtures;
    }

    private static List<StubBody> discoverStubBodies() {
        List<BatchResultFixture> fixtures = discoverFixtures();
        List<StubBody> bodies = new ArrayList<>();
        for (Method method : factoriesReturning(MappingBuilder.class)) {
            List<Object[]> vectors = argumentVectors(method, fixtures);
            for (Object[] arguments : vectors) {
                MappingBuilder builder = (MappingBuilder) invoke(method, arguments);
                ResponseDefinition response = builder.build().getResponse();
                String label = method.getName() + Arrays.toString(arguments);
                bodies.add(new StubBody(label,
                        response.getHeaders().getContentTypeHeader().mimeTypePart(),
                        response.getBody()));
            }
        }
        return bodies;
    }

    private static List<Object[]> argumentVectors(Method method, List<BatchResultFixture> fixtures) {
        List<Object[]> vectors = new ArrayList<>();
        vectors.add(new Object[0]);
        for (Parameter parameter : method.getParameters()) {
            List<Object> candidates = candidatesFor(method, parameter, fixtures);
            List<Object[]> expanded = new ArrayList<>();
            for (Object[] prefix : vectors) {
                for (Object candidate : candidates) {
                    Object[] next = Arrays.copyOf(prefix, prefix.length + 1);
                    next[prefix.length] = candidate;
                    expanded.add(next);
                }
            }
            vectors = expanded;
        }
        return vectors;
    }

    private static List<Object> candidatesFor(Method method, Parameter parameter,
            List<BatchResultFixture> fixtures) {
        if (List.class.equals(parameter.getType())) {
            return List.of(fixtures);
        }
        List<Object> registered = ARGUMENTS_BY_PARAMETER_NAME.get(parameter.getName());
        if (registered == null) {
            return fail("No contract-test argument registered for parameter '%s' of %s(). Register one "
                    + "so the new fixture is contract-checked rather than silently skipped.",
                    parameter.getName(), method.getName());
        }
        return registered;
    }

    private static Object invoke(Method method, Object[] arguments) {
        try {
            return method.invoke(null, arguments);
        } catch (ReflectiveOperationException e) {
            return fail("Could not invoke fixture factory " + method.getName(), e);
        }
    }

    // ---------------------------------------------------------------------
    // Value types
    // ---------------------------------------------------------------------

    private record StubBody(String label, String contentType, String body) {
    }

    private record ErrorVariant(String wireType, Predicate<ErrorObject> matches,
            Function<ErrorObject, String> message) {
    }
}
