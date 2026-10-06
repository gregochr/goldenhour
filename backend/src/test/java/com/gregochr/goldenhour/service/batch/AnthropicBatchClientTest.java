package com.gregochr.goldenhour.service.batch;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ClientOptions;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.BatchListPage;
import com.anthropic.models.messages.batches.BatchListParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.models.messages.batches.MessageBatchRequestCounts;
import com.anthropic.models.messages.batches.MessageBatchResult;
import com.anthropic.models.messages.batches.MessageBatchSucceededResult;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.messages.BatchService;
import com.gregochr.goldenhour.config.BatchSubmitRetryPredicate;
import com.gregochr.goldenhour.model.CacheDiagnosticsFixtures;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnthropicBatchClient} — the second, longer-window retry layer added
 * around batch creation after the 2026-09-29 incident (three HTTP 500s that exhausted the SDK's
 * own 3-attempt retry within seconds and then gave up outright), hardened further in round 2 (a
 * Codex review of #949) against adopting the WRONG batch (P1-A) and against the SDK's own
 * transport-level retry firing extra non-idempotent {@code create()} calls inside one guarded
 * attempt (P1-B).
 *
 * <p>Uses a REAL {@link Retry} instance (built with a 1ms wait so the suite stays fast) driven by
 * the REAL {@link BatchSubmitRetryPredicate}, so these tests exercise the exact retry/predicate
 * wiring production uses rather than a stand-in. {@link RetryRegistry} is mocked purely to hand
 * back that real instance under the {@code "anthropic-batch"} name.
 *
 * <p>{@code anthropicClient} (the raw client passed to the constructor) and {@code batchClient}
 * (what {@code anthropicClient.withOptions(...)} returns, per P1-B) are deliberately TWO separate
 * mocks, with only {@code batchClient} wired to {@code messageService}/{@code batchService}. This
 * is itself a regression guard: if production code were ever changed to call {@code create()} or
 * {@code list()} on the raw {@code anthropicClient} again, {@code anthropicClient.messages()}
 * would return an unstubbed {@code null} and NPE loudly, rather than the test silently continuing
 * to pass against the wrong client.
 */
@ExtendWith(MockitoExtension.class)
class AnthropicBatchClientTest {

    private static final Instant FIRST_ATTEMPT_INSTANT = Instant.parse("2026-09-29T14:04:53Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIRST_ATTEMPT_INSTANT, ZoneOffset.UTC);

    @Mock
    private AnthropicClient anthropicClient;
    @Mock
    private AnthropicClient batchClient;
    @Mock
    private MessageService messageService;
    @Mock
    private BatchService batchService;
    @Mock
    private RetryRegistry retryRegistry;
    @Mock
    private ForecastBatchRepository forecastBatchRepository;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger clientLogger;

    private AnthropicBatchClient client;

    @BeforeEach
    void setUp() {
        when(anthropicClient.withOptions(any())).thenReturn(batchClient);
        // lenient(): the "derives the client" test below never calls createBatch(), so these two
        // never fire there — every other test does exercise them.
        lenient().when(batchClient.messages()).thenReturn(messageService);
        lenient().when(messageService.batches()).thenReturn(batchService);
        // Default: nothing is tracked yet. Individual tests override per candidate id to exercise
        // the P1-A exclusion.
        lenient().when(forecastBatchRepository.existsByAnthropicBatchId(any())).thenReturn(false);

        client = new AnthropicBatchClient(anthropicClient, retryRegistry, FIXED_CLOCK,
                forecastBatchRepository);

        clientLogger = (Logger) LoggerFactory.getLogger(AnthropicBatchClient.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        clientLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        clientLogger.detachAppender(logAppender);
    }

    /** Real retry instance with the real predicate, tiny wait so tests stay fast. */
    private void useRetryWithMaxAttempts(int maxAttempts) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ofMillis(1))
                .retryOnException(new BatchSubmitRetryPredicate())
                .build();
        Retry retry = Retry.of("anthropic-batch-test", config);
        when(retryRegistry.retry(AnthropicBatchClient.RETRY_INSTANCE_NAME)).thenReturn(retry);
    }

    private static List<BatchCreateParams.Request> buildRequests(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> BatchCreateParams.Request.builder()
                        .customId("fc-" + i + "-2026-09-29-SUNSET")
                        .params(BatchCreateParams.Request.Params.builder()
                                .model("claude-sonnet-4-6")
                                .maxTokens(1024)
                                .addUserMessage("test")
                                .build())
                        .build())
                .toList();
    }

    /**
     * Builds a {@link MessageBatch} mock covering every field the adoption-matching logic might
     * read: {@code id()} (for logging, identity and the tracked-batch check), {@code createdAt()}
     * (the timing filter) and {@code requestCounts()} (the size filter). Which of these a given
     * test's production code path actually reaches depends on where in the filter chain that
     * particular candidate is dropped, so every stub here is {@code lenient()}: this is a shared
     * fixture builder, not the behaviour under test.
     */
    private static MessageBatch aBatch(String id, OffsetDateTime createdAt, long matchingRequestCount) {
        MessageBatch batch = mock(MessageBatch.class);
        MessageBatchRequestCounts counts = mock(MessageBatchRequestCounts.class);
        lenient().when(counts.processing()).thenReturn(0L);
        lenient().when(counts.succeeded()).thenReturn(matchingRequestCount);
        lenient().when(counts.errored()).thenReturn(0L);
        lenient().when(counts.canceled()).thenReturn(0L);
        lenient().when(counts.expired()).thenReturn(0L);
        lenient().when(batch.id()).thenReturn(id);
        lenient().when(batch.createdAt()).thenReturn(createdAt);
        lenient().when(batch.requestCounts()).thenReturn(counts);
        return batch;
    }

    /**
     * Builds a retryable {@link AnthropicServiceException} mock. Always assign the result to a
     * local variable before starting a {@code when(mock.method())....thenThrow(...)} chain —
     * passing this call directly as the {@code .thenThrow(...)} argument nests a second,
     * self-contained stubbing sequence inside the first's still-open one and corrupts Mockito's
     * ongoing-stubbing state (confirmed against this suite: it manifests as an
     * {@code UnfinishedStubbingException} on a LATER, unrelated {@code when()} call).
     *
     * <p>{@code statusCode()} is stubbed strictly — {@link BatchSubmitRetryPredicate} reads it on
     * every invocation. {@code getMessage()} is {@code lenient()}: it is read only when the
     * exception ultimately propagates out of {@link AnthropicBatchClient#createBatch} (into
     * {@link BatchRetryExhaustedException}'s message), which a test whose retry or adoption
     * eventually SUCCEEDS never reaches.
     */
    private static AnthropicServiceException serviceException(int statusCode) {
        AnthropicServiceException ex = mock(AnthropicServiceException.class);
        when(ex.statusCode()).thenReturn(statusCode);
        lenient().when(ex.getMessage()).thenReturn("status " + statusCode);
        return ex;
    }

    @Test
    @DisplayName("createBatch: derives the transport-retry-disabled client once in the "
            + "constructor, and every create()/list() call goes through it — never the raw "
            + "shared client (P1-B)")
    void derivesNoRetryClientOnceInConstructor() {
        useRetryWithMaxAttempts(4);
        BatchCreateParams params = BatchCreateParams.builder().requests(buildRequests(1)).build();
        MessageBatch expected = mock(MessageBatch.class);
        when(batchService.create(params)).thenReturn(expected);

        MessageBatch result = client.createBatch(params);

        assertThat(result).isSameAs(expected);
        // Constructed once in @BeforeEach; a second createBatch() call must not derive again.
        verify(anthropicClient, times(1)).withOptions(any());
        verify(anthropicClient, never()).messages();
    }

    @Test
    @DisplayName("createBatch: the derived client disables the SDK's transport-level retries "
            + "(maxRetries=0) — the exact ClientOptions mutation P1-B requires")
    @SuppressWarnings("unchecked")
    void derivedClientDisablesTransportRetries() {
        ArgumentCaptor<Consumer<ClientOptions.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(anthropicClient).withOptions(captor.capture());

        ClientOptions.Builder builder = mock(ClientOptions.Builder.class);
        when(builder.maxRetries(anyInt())).thenReturn(builder);
        captor.getValue().accept(builder);

        verify(builder).maxRetries(0);
    }

    @Test
    @DisplayName("createBatch: first attempt succeeds — no retry, no adoption check")
    void firstAttemptSucceeds_noRetryNoAdoptionCheck() {
        useRetryWithMaxAttempts(4);
        BatchCreateParams params = BatchCreateParams.builder().requests(buildRequests(2)).build();
        MessageBatch expected = mock(MessageBatch.class);
        when(batchService.create(params)).thenReturn(expected);

        MessageBatch result = client.createBatch(params);

        assertThat(result).isSameAs(expected);
        verify(batchService, times(1)).create(params);
        verify(batchService, never()).list(any(BatchListParams.class));
    }

    @Test
    @DisplayName("createBatch: retries a transient 500 then succeeds; no matching batch to adopt")
    void retryableFailureThenSuccess_noAdoptionMatch() {
        useRetryWithMaxAttempts(4);
        BatchCreateParams params = BatchCreateParams.builder().requests(buildRequests(2)).build();
        MessageBatch success = mock(MessageBatch.class);
        AnthropicServiceException serverError = serviceException(500);

        BatchListPage emptyPage = mock(BatchListPage.class);
        when(emptyPage.items()).thenReturn(List.of());
        when(batchService.list(any(BatchListParams.class))).thenReturn(emptyPage);
        when(batchService.create(params))
                .thenThrow(serverError)
                .thenReturn(success);

        MessageBatch result = client.createBatch(params);

        assertThat(result).isSameAs(success);
        verify(batchService, times(2)).create(params);
        verify(batchService, times(1)).list(any(BatchListParams.class));
    }

    @Test
    @DisplayName("createBatch: adopts the one untracked batch a previous attempt actually "
            + "created, without creating a duplicate")
    void adoptsBatchCreatedByFailedLookingAttempt() {
        useRetryWithMaxAttempts(4);
        BatchCreateParams params = BatchCreateParams.builder().requests(buildRequests(3)).build();

        MessageBatch orphaned = aBatch("msgbatch_orphaned_but_real",
                OffsetDateTime.ofInstant(FIRST_ATTEMPT_INSTANT.plusSeconds(1), ZoneOffset.UTC), 3L);
        BatchListPage page = mock(BatchListPage.class);
        when(page.items()).thenReturn(List.of(orphaned));
        when(batchService.list(any(BatchListParams.class))).thenReturn(page);
        when(batchService.create(params)).thenThrow(new AnthropicIoException("read timed out"));

        MessageBatch result = client.createBatch(params);

        assertThat(result).isSameAs(orphaned);
        // create() is called only for the FIRST attempt; the second attempt finds the adoption
        // match and returns without calling create() again.
        verify(batchService, times(1)).create(params);
        verify(batchService, times(1)).list(any(BatchListParams.class));
        verify(forecastBatchRepository).existsByAnthropicBatchId("msgbatch_orphaned_but_real");

        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("Adopted batch msgbatch_orphaned_but_real")
                    .contains("retry attempt 2");
        });
    }

    @Test
    @DisplayName("createBatch: a batch already tracked in forecast_batch (a sibling bucket) is "
            + "excluded from adoption even though it matches on timing and size — P1-A")
    void trackedSiblingBatch_isNotAdopted() {
        useRetryWithMaxAttempts(4);
        BatchCreateParams params = BatchCreateParams.builder().requests(buildRequests(2)).build();

        MessageBatch sibling = aBatch("msgbatch_sibling_already_tracked",
                OffsetDateTime.ofInstant(FIRST_ATTEMPT_INSTANT.plusSeconds(1), ZoneOffset.UTC), 2L);
        when(forecastBatchRepository.existsByAnthropicBatchId("msgbatch_sibling_already_tracked"))
                .thenReturn(true);
        BatchListPage page = mock(BatchListPage.class);
        when(page.items()).thenReturn(List.of(sibling));
        when(batchService.list(any(BatchListParams.class))).thenReturn(page);
        MessageBatch success = mock(MessageBatch.class);
        AnthropicServiceException serverError = serviceException(500);
        when(batchService.create(params))
                .thenThrow(serverError)
                .thenReturn(success);

        MessageBatch result = client.createBatch(params);

        // The tracked sibling is excluded, so no untracked candidate remains — the retry
        // proceeds to create a genuinely new batch rather than wrongly adopting the sibling's id.
        assertThat(result).isSameAs(success);
        verify(batchService, times(2)).create(params);
    }

    @Test
    @DisplayName("createBatch: exactly one UNTRACKED candidate among a tracked sibling and an "
            + "untracked orphan adopts only the untracked one")
    void oneTrackedOneUntracked_adoptsOnlyTheUntrackedOne() {
        useRetryWithMaxAttempts(4);
        BatchCreateParams params = BatchCreateParams.builder().requests(buildRequests(1)).build();

        MessageBatch trackedSibling = aBatch("msgbatch_tracked_sibling",
                OffsetDateTime.ofInstant(FIRST_ATTEMPT_INSTANT.plusSeconds(1), ZoneOffset.UTC), 1L);
        MessageBatch untrackedOrphan = aBatch("msgbatch_untracked_orphan",
                OffsetDateTime.ofInstant(FIRST_ATTEMPT_INSTANT.plusSeconds(2), ZoneOffset.UTC), 1L);
        when(forecastBatchRepository.existsByAnthropicBatchId("msgbatch_tracked_sibling"))
                .thenReturn(true);
        when(forecastBatchRepository.existsByAnthropicBatchId("msgbatch_untracked_orphan"))
                .thenReturn(false);
        BatchListPage page = mock(BatchListPage.class);
        when(page.items()).thenReturn(List.of(trackedSibling, untrackedOrphan));
        when(batchService.list(any(BatchListParams.class))).thenReturn(page);
        when(batchService.create(params)).thenThrow(new AnthropicIoException("timeout"));

        MessageBatch result = client.createBatch(params);

        assertThat(result).isSameAs(untrackedOrphan);
        verify(batchService, times(1)).create(params);
    }

    @Test
    @DisplayName("createBatch: two UNTRACKED candidates refuse to adopt or create again, and "
            + "throw an ambiguity error naming both ids — P1-A")
    void twoUntrackedMatches_refusesAndThrowsWithoutCreatingAgain() {
        useRetryWithMaxAttempts(4);
        BatchCreateParams params = BatchCreateParams.builder().requests(buildRequests(1)).build();

        MessageBatch first = aBatch("msgbatch_ambiguous_one",
                OffsetDateTime.ofInstant(FIRST_ATTEMPT_INSTANT.plusSeconds(1), ZoneOffset.UTC), 1L);
        MessageBatch second = aBatch("msgbatch_ambiguous_two",
                OffsetDateTime.ofInstant(FIRST_ATTEMPT_INSTANT.plusSeconds(2), ZoneOffset.UTC), 1L);
        BatchListPage page = mock(BatchListPage.class);
        when(page.items()).thenReturn(List.of(first, second));
        when(batchService.list(any(BatchListParams.class))).thenReturn(page);
        when(batchService.create(params)).thenThrow(new AnthropicIoException("timeout"));

        assertThatThrownBy(() -> client.createBatch(params))
                .isInstanceOf(BatchRetryExhaustedException.class)
                .cause().isInstanceOf(AmbiguousBatchAdoptionException.class);

        // Exactly the first attempt's create() call — the second attempt detects the ambiguity
        // and gives up before ever calling create() again.
        verify(batchService, times(1)).create(params);

        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .contains("2 UNTRACKED candidate")
                    .contains("msgbatch_ambiguous_one")
                    .contains("msgbatch_ambiguous_two")
                    .contains("refusing to adopt");
        });
    }

    @Test
    @DisplayName("createBatch: a batch created strictly before the first attempt started is "
            + "never adopted, however close — no clock-skew tolerance any more (P1-A)")
    void batchCreatedBeforeFirstAttemptIsNotAdopted() {
        useRetryWithMaxAttempts(4);
        BatchCreateParams params = BatchCreateParams.builder().requests(buildRequests(2)).build();

        // One nanosecond before the cutoff — must NOT be treated as a match. The old 5s
        // negative-skew tolerance was dropped in round 2 (P1-A): the cutoff is now exactly
        // firstAttemptStart, with no leeway in either direction.
        MessageBatch tooOld = aBatch("msgbatch_unrelated_older",
                OffsetDateTime.ofInstant(FIRST_ATTEMPT_INSTANT.minusNanos(1), ZoneOffset.UTC), 2L);
        BatchListPage page = mock(BatchListPage.class);
        when(page.items()).thenReturn(List.of(tooOld));
        when(batchService.list(any(BatchListParams.class))).thenReturn(page);
        MessageBatch success = mock(MessageBatch.class);
        AnthropicServiceException overloaded = serviceException(529);
        when(batchService.create(params))
                .thenThrow(overloaded)
                .thenReturn(success);

        MessageBatch result = client.createBatch(params);

        assertThat(result).isSameAs(success);
        verify(batchService, times(2)).create(params);
    }

    @Test
    @DisplayName("createBatch: a batch created at EXACTLY firstAttemptStart is adopted — the "
            + "cutoff is inclusive")
    void batchCreatedExactlyAtCutoffIsAdopted() {
        useRetryWithMaxAttempts(4);
        BatchCreateParams params = BatchCreateParams.builder().requests(buildRequests(2)).build();

        MessageBatch exactlyAtCutoff = aBatch("msgbatch_exact_cutoff",
                OffsetDateTime.ofInstant(FIRST_ATTEMPT_INSTANT, ZoneOffset.UTC), 2L);
        BatchListPage page = mock(BatchListPage.class);
        when(page.items()).thenReturn(List.of(exactlyAtCutoff));
        when(batchService.list(any(BatchListParams.class))).thenReturn(page);
        when(batchService.create(params)).thenThrow(new AnthropicIoException("timeout"));

        MessageBatch result = client.createBatch(params);

        assertThat(result).isSameAs(exactlyAtCutoff);
        verify(batchService, times(1)).create(params);
    }

    @Test
    @DisplayName("createBatch: a batch with a different request count is never adopted")
    void batchWithDifferentRequestCountIsNotAdopted() {
        useRetryWithMaxAttempts(4);
        BatchCreateParams params = BatchCreateParams.builder().requests(buildRequests(3)).build();

        MessageBatch wrongSize = aBatch("msgbatch_wrong_size",
                OffsetDateTime.ofInstant(FIRST_ATTEMPT_INSTANT.plusSeconds(1), ZoneOffset.UTC), 2L);
        BatchListPage page = mock(BatchListPage.class);
        when(page.items()).thenReturn(List.of(wrongSize));
        when(batchService.list(any(BatchListParams.class))).thenReturn(page);
        MessageBatch success = mock(MessageBatch.class);
        AnthropicServiceException serverError = serviceException(500);
        when(batchService.create(params))
                .thenThrow(serverError)
                .thenReturn(success);

        MessageBatch result = client.createBatch(params);

        assertThat(result).isSameAs(success);
        verify(batchService, times(2)).create(params);
    }

    @Test
    @DisplayName("createBatch: the adoption list call failing does not block the retry")
    void adoptionListCallFails_proceedsToCreate() {
        useRetryWithMaxAttempts(4);
        BatchCreateParams params = BatchCreateParams.builder().requests(buildRequests(2)).build();
        MessageBatch success = mock(MessageBatch.class);
        AnthropicServiceException serverError = serviceException(503);

        when(batchService.list(any(BatchListParams.class)))
                .thenThrow(new RuntimeException("list endpoint unavailable"));
        when(batchService.create(params))
                .thenThrow(serverError)
                .thenReturn(success);

        MessageBatch result = client.createBatch(params);

        assertThat(result).isSameAs(success);
        verify(batchService, times(2)).create(params);
        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("Batch-adoption check failed on retry attempt 2");
        });
    }

    @Test
    @DisplayName("createBatch: a non-retryable 400 fails after exactly one attempt")
    void nonRetryableFailure_failsAfterOneAttempt() {
        useRetryWithMaxAttempts(4);
        BatchCreateParams params = BatchCreateParams.builder().requests(buildRequests(1)).build();
        AnthropicServiceException badRequest = serviceException(400);
        when(batchService.create(params)).thenThrow(badRequest);

        assertThatThrownBy(() -> client.createBatch(params))
                .isInstanceOf(BatchRetryExhaustedException.class)
                .extracting(e -> ((BatchRetryExhaustedException) e).getAttempts())
                .isEqualTo(1);

        verify(batchService, times(1)).create(params);
        verify(batchService, never()).list(any(BatchListParams.class));
    }

    @Test
    @DisplayName("createBatch: a 429 IS retried — the SDK's own transport-level 429 handling is "
            + "disabled for this call (P1-B), so this predicate must cover it")
    void rateLimited429_isRetried() {
        useRetryWithMaxAttempts(4);
        BatchCreateParams params = BatchCreateParams.builder().requests(buildRequests(1)).build();
        MessageBatch success = mock(MessageBatch.class);
        AnthropicServiceException rateLimited = serviceException(429);
        BatchListPage emptyPage = mock(BatchListPage.class);
        when(emptyPage.items()).thenReturn(List.of());
        when(batchService.list(any(BatchListParams.class))).thenReturn(emptyPage);
        when(batchService.create(params))
                .thenThrow(rateLimited)
                .thenReturn(success);

        MessageBatch result = client.createBatch(params);

        assertThat(result).isSameAs(success);
        verify(batchService, times(2)).create(params);
    }

    @Test
    @DisplayName("createBatch: every attempt failing exhausts retries and reports the max attempt count")
    void allAttemptsFail_exhaustsRetriesWithMaxAttemptCount() {
        useRetryWithMaxAttempts(4);
        BatchCreateParams params = BatchCreateParams.builder().requests(buildRequests(1)).build();

        BatchListPage emptyPage = mock(BatchListPage.class);
        when(emptyPage.items()).thenReturn(List.of());
        when(batchService.list(any(BatchListParams.class))).thenReturn(emptyPage);
        AnthropicServiceException serverError = serviceException(500);
        when(batchService.create(params)).thenThrow(serverError);

        assertThatThrownBy(() -> client.createBatch(params))
                .isInstanceOf(BatchRetryExhaustedException.class)
                .extracting(e -> ((BatchRetryExhaustedException) e).getAttempts())
                .isEqualTo(4);

        verify(batchService, times(4)).create(params);
        verify(batchService, times(3)).list(any(BatchListParams.class));
    }

    // ── Round 3 (a Codex review of #949's 8b8ed0dd): cross-submitter serialization and the
    // handed-out-id record ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("createBatch: two concurrent callers are serialized — the second create() call "
            + "starts only after the first createBatch() call has fully returned")
    void concurrentCreateBatchCalls_areSerialized() throws InterruptedException {
        useRetryWithMaxAttempts(4);
        BatchCreateParams paramsA = BatchCreateParams.builder().requests(buildRequests(1)).build();
        BatchCreateParams paramsB = BatchCreateParams.builder().requests(buildRequests(2)).build();
        MessageBatch batchA = mock(MessageBatch.class);
        MessageBatch batchB = mock(MessageBatch.class);
        when(batchA.id()).thenReturn("msgbatch_a");
        when(batchB.id()).thenReturn("msgbatch_b");

        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);

        when(batchService.create(paramsA)).thenAnswer(invocation -> {
            order.add("A-enter");
            aEntered.countDown();
            assertThat(releaseA.await(5, TimeUnit.SECONDS)).isTrue();
            return batchA;
        });
        when(batchService.create(paramsB)).thenAnswer(invocation -> {
            order.add("B-enter");
            return batchB;
        });

        Thread threadA = new Thread(() -> client.createBatch(paramsA));
        threadA.start();
        assertThat(aEntered.await(5, TimeUnit.SECONDS)).isTrue();

        // B starts while A still holds creationLock (blocked inside its create() answer) — B
        // must block on the lock and cannot call its own create() until A releases it.
        Thread threadB = new Thread(() -> client.createBatch(paramsB));
        threadB.start();

        releaseA.countDown();
        threadA.join(5_000);
        threadB.join(5_000);

        assertThat(threadA.isAlive()).isFalse();
        assertThat(threadB.isAlive()).isFalse();
        assertThat(order).containsExactly("A-enter", "B-enter");
    }

    @Test
    @DisplayName("createBatch: the lock is released even when createBatch throws, so a "
            + "following call from ANOTHER thread proceeds rather than deadlocking")
    void lockIsReleasedOnFailure_followingCallFromAnotherThreadProceeds() throws InterruptedException {
        useRetryWithMaxAttempts(1);
        BatchCreateParams failingParams = BatchCreateParams.builder().requests(buildRequests(1)).build();
        AnthropicServiceException badRequest = serviceException(400);
        when(batchService.create(failingParams)).thenThrow(badRequest);

        assertThatThrownBy(() -> client.createBatch(failingParams))
                .isInstanceOf(BatchRetryExhaustedException.class);

        BatchCreateParams followingParams = BatchCreateParams.builder().requests(buildRequests(2)).build();
        MessageBatch success = mock(MessageBatch.class);
        when(batchService.create(followingParams)).thenReturn(success);

        AtomicReference<MessageBatch> resultHolder = new AtomicReference<>();
        Thread otherThread = new Thread(() -> resultHolder.set(client.createBatch(followingParams)));
        otherThread.start();
        otherThread.join(5_000);

        assertThat(otherThread.isAlive()).isFalse();
        assertThat(resultHolder.get()).isSameAs(success);
    }

    @Test
    @DisplayName("createBatch: a batch id this client already returned is never adopted later, "
            + "even though it is absent from forecast_batch (the sky-rating shape — no tracking "
            + "row is ever written for that caller)")
    void previouslyHandedOutBatchId_isNeverAdoptedEvenWhenUntracked() {
        useRetryWithMaxAttempts(4);
        // uniqueParams(...), not buildRequests(1) for both: two calls to buildRequests(1) build
        // structurally EQUAL BatchCreateParams (the same single deterministic customId each
        // time), which Mockito would then treat as the SAME stubbed invocation — corrupting both
        // the stub sequencing and the create()-count verification below. uniqueParams keeps the
        // same request COUNT (1, so the size filter still matches) while keeping the two
        // BatchCreateParams objects themselves distinct.
        BatchCreateParams paramsA = uniqueParams("handed-out-a");
        MessageBatch batchA = aBatch("msgbatch_sky_returned",
                OffsetDateTime.ofInstant(FIRST_ATTEMPT_INSTANT, ZoneOffset.UTC), 1L);
        when(batchService.create(paramsA)).thenReturn(batchA);

        MessageBatch firstResult = client.createBatch(paramsA);
        assertThat(firstResult).isSameAs(batchA);

        // A second, distinct request of the SAME size. Its first attempt fails; the retry's
        // adoption check lists recent batches and sees batchA — same size, createdAt equal to
        // this call's own firstAttemptStart (both calls share the fixed clock) — but batchA was
        // already handed out by THIS client and must never be adopted a second time, regardless
        // of forecastBatchRepository (which, by default, does not track it either — exactly the
        // sky-rating shape, where no ForecastBatchEntity row is ever written).
        BatchCreateParams paramsB = uniqueParams("handed-out-b");
        MessageBatch batchB = mock(MessageBatch.class);
        BatchListPage page = mock(BatchListPage.class);
        when(page.items()).thenReturn(List.of(batchA));
        when(batchService.list(any(BatchListParams.class))).thenReturn(page);
        when(batchService.create(paramsB))
                .thenThrow(new AnthropicIoException("timeout"))
                .thenReturn(batchB);

        MessageBatch secondResult = client.createBatch(paramsB);

        assertThat(secondResult).isSameAs(batchB);
        verify(batchService, times(2)).create(paramsB);
    }

    @Test
    @DisplayName("createBatch: the handed-out-id record is bounded — the eldest id is evicted "
            + "once the cap is exceeded, so it becomes adoptable again")
    void handedOutIdRecord_evictsEldestPastCap() {
        useRetryWithMaxAttempts(4);
        String firstId = "msgbatch_evict_me";
        BatchCreateParams firstParams = uniqueParams("first");
        MessageBatch firstBatch = aBatch(firstId,
                OffsetDateTime.ofInstant(FIRST_ATTEMPT_INSTANT, ZoneOffset.UTC), 1L);
        when(batchService.create(firstParams)).thenReturn(firstBatch);
        assertThat(client.createBatch(firstParams)).isSameAs(firstBatch);

        // Fill the record past its cap with distinct successful creations, evicting firstId —
        // each filler request is structurally unique (a distinct customId), so none of these
        // stubs collide with each other or with firstParams/retryParams below.
        for (int i = 0; i < AnthropicBatchClient.HANDED_OUT_ID_CAP; i++) {
            BatchCreateParams fillerParams = uniqueParams("filler-" + i);
            MessageBatch fillerBatch = mock(MessageBatch.class);
            when(fillerBatch.id()).thenReturn("msgbatch_filler_" + i);
            when(batchService.create(fillerParams)).thenReturn(fillerBatch);
            client.createBatch(fillerParams);
        }

        // A distinct request whose retry's adoption check would find firstBatch as a same-size,
        // same-timing candidate. It is no longer excluded by the handed-out-id record (evicted),
        // so — still untracked in forecastBatchRepository — it is adopted.
        BatchCreateParams retryParams = uniqueParams("retry");
        BatchListPage page = mock(BatchListPage.class);
        when(page.items()).thenReturn(List.of(firstBatch));
        when(batchService.list(any(BatchListParams.class))).thenReturn(page);
        when(batchService.create(retryParams)).thenThrow(new AnthropicIoException("timeout"));

        MessageBatch result = client.createBatch(retryParams);

        assertThat(result).isSameAs(firstBatch);
    }

    @Test
    @DisplayName("createPrimerBatch: one attempt under the given timeout, no retry registry, no tracking lookup")
    void createPrimerBatch_singleAttemptNoRetry() {
        BatchCreateParams params = uniqueParams("primer");
        MessageBatch primer = aBatch("msgbatch_primer", OffsetDateTime.now(), 1);
        when(batchService.create(eq(params), any(RequestOptions.class))).thenReturn(primer);

        MessageBatch result = client.createPrimerBatch(params, Duration.ofSeconds(15));

        assertThat(result).isSameAs(primer);
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(batchService, times(1)).create(eq(params), options.capture());
        assertThat(options.getValue().getTimeout().request())
                .isLessThanOrEqualTo(Duration.ofSeconds(15)).isGreaterThan(Duration.ofSeconds(10));
        verifyNoInteractions(retryRegistry);
        verifyNoInteractions(forecastBatchRepository);
    }

    @Test
    @DisplayName("createPrimerBatch: a failure propagates at once, unretried")
    void createPrimerBatch_failurePropagatesUnretried() {
        BatchCreateParams params = uniqueParams("primer-fail");
        AnthropicIoException failure = new AnthropicIoException("timeout");
        when(batchService.create(eq(params), any(RequestOptions.class))).thenThrow(failure);

        assertThatThrownBy(() -> client.createPrimerBatch(params, Duration.ofSeconds(15)))
                .isSameAs(failure);

        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(batchService, times(1)).create(eq(params), options.capture());
        assertThat(options.getValue().getTimeout().request())
                .isLessThanOrEqualTo(Duration.ofSeconds(15)).isGreaterThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("createPrimerBatch: the primer is recorded as handed out, so a real retry never adopts it")
    void createPrimerBatch_isNeverAdoptedByARealSubmissionsRetry() {
        useRetryWithMaxAttempts(2);
        BatchCreateParams primerParams = uniqueParams("primer-adopt");
        MessageBatch primer = aBatch("msgbatch_primer_adopt",
                OffsetDateTime.ofInstant(FIRST_ATTEMPT_INSTANT.plusSeconds(1), ZoneOffset.UTC), 1);
        when(batchService.create(eq(primerParams), any(RequestOptions.class))).thenReturn(primer);
        client.createPrimerBatch(primerParams, Duration.ofSeconds(15));

        BatchCreateParams realParams = uniqueParams("real");
        MessageBatch real = aBatch("msgbatch_real", OffsetDateTime.now(), 1);
        AnthropicServiceException serverError = serviceException(500);
        BatchListPage page = mock(BatchListPage.class);
        when(page.items()).thenReturn(List.of(primer));
        when(batchService.list(any(BatchListParams.class))).thenReturn(page);
        when(batchService.create(realParams)).thenThrow(serverError).thenReturn(real);

        MessageBatch result = client.createBatch(realParams);

        assertThat(result).isSameAs(real);
        verify(batchService, times(2)).create(realParams);
    }

    @Test
    @DisplayName("retrieveBatch: reads the batch under the given timeout")
    void retrieveBatch_delegates() {
        MessageBatch batch = aBatch("msgbatch_r", OffsetDateTime.now(), 1);
        when(batchService.retrieve(eq("msgbatch_r"), any(RequestOptions.class))).thenReturn(batch);

        assertThat(client.retrieveBatch("msgbatch_r", Duration.ofSeconds(7))).isSameAs(batch);

        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(batchService).retrieve(eq("msgbatch_r"), options.capture());
        assertThat(options.getValue().getTimeout().request()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    @DisplayName("readFirstSucceededMessage: returns the succeeded message under the timeout, closes the stream")
    void readFirstSucceededMessage_returnsTheMessageAndClosesTheStream() {
        com.anthropic.models.messages.Message message = CacheDiagnosticsFixtures.message(
                "msg_primer", java.util.Optional.empty(), "{}", 4726, 4726, 0);
        MessageBatchIndividualResponse errored = mock(MessageBatchIndividualResponse.class);
        MessageBatchResult erroredResult = mock(MessageBatchResult.class);
        when(errored.result()).thenReturn(erroredResult);
        when(erroredResult.succeeded()).thenReturn(java.util.Optional.empty());
        MessageBatchIndividualResponse succeeded = mock(MessageBatchIndividualResponse.class);
        MessageBatchResult succeededResult = mock(MessageBatchResult.class);
        MessageBatchSucceededResult payload = mock(MessageBatchSucceededResult.class);
        when(succeeded.result()).thenReturn(succeededResult);
        when(succeededResult.succeeded()).thenReturn(java.util.Optional.of(payload));
        when(payload.message()).thenReturn(message);
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> stream = mock(StreamResponse.class);
        when(stream.stream()).thenReturn(java.util.stream.Stream.of(errored, succeeded));
        when(batchService.resultsStreaming(eq("msgbatch_p"), any(RequestOptions.class))).thenReturn(stream);

        assertThat(client.readFirstSucceededMessage("msgbatch_p", Duration.ofSeconds(7))).containsSame(message);

        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(batchService).resultsStreaming(eq("msgbatch_p"), options.capture());
        assertThat(options.getValue().getTimeout().request()).isEqualTo(Duration.ofSeconds(7));
        verify(stream).close();
    }

    @Test
    @DisplayName("readFirstSucceededMessage: a batch with no succeeded request yields nothing")
    void readFirstSucceededMessage_noSuccess_isEmpty() {
        MessageBatchIndividualResponse errored = mock(MessageBatchIndividualResponse.class);
        MessageBatchResult erroredResult = mock(MessageBatchResult.class);
        when(errored.result()).thenReturn(erroredResult);
        when(erroredResult.succeeded()).thenReturn(java.util.Optional.empty());
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> stream = mock(StreamResponse.class);
        when(stream.stream()).thenReturn(java.util.stream.Stream.of(errored));
        when(batchService.resultsStreaming(eq("msgbatch_p"), any(RequestOptions.class))).thenReturn(stream);

        assertThat(client.readFirstSucceededMessage("msgbatch_p", Duration.ofSeconds(7))).isEmpty();
    }

    @Test
    @DisplayName("primer vs adoption: a real retry running while a primer create is in flight never adopts it")
    void inFlightPrimer_isNotAdoptedByAConcurrentRealRetry() throws Exception {
        useRetryWithMaxAttempts(2);
        BatchCreateParams primerParams = uniqueParams("primer-inflight");
        MessageBatch primer = aBatch("msgbatch_inflight",
                OffsetDateTime.ofInstant(FIRST_ATTEMPT_INSTANT.plusSeconds(1), ZoneOffset.UTC), 1);
        CountDownLatch primerInCreate = new CountDownLatch(1);
        CountDownLatch releasePrimer = new CountDownLatch(1);
        when(batchService.create(eq(primerParams), any(RequestOptions.class))).thenAnswer(inv -> {
            primerInCreate.countDown();
            assertThat(releasePrimer.await(10, TimeUnit.SECONDS)).isTrue();
            return primer;
        });
        BatchCreateParams realParams = uniqueParams("real-concurrent");
        MessageBatch real = aBatch("msgbatch_real_concurrent", OffsetDateTime.now(), 1);
        AnthropicServiceException serverError = serviceException(500);
        BatchListPage page = mock(BatchListPage.class);
        when(page.items()).thenReturn(List.of(primer));
        when(batchService.list(any(BatchListParams.class))).thenReturn(page);
        when(batchService.create(realParams)).thenThrow(serverError).thenReturn(real);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var primerFuture = pool.submit(() -> client.createPrimerBatch(primerParams, Duration.ofSeconds(15)));
            assertThat(primerInCreate.await(10, TimeUnit.SECONDS)).isTrue();
            var realFuture = pool.submit(() -> client.createBatch(realParams));
            // the real submission is queued behind the guard: it has not even tried yet
            Thread.sleep(200);
            verify(batchService, never()).create(realParams);
            releasePrimer.countDown();

            assertThat(primerFuture.get(10, TimeUnit.SECONDS)).isSameAs(primer);
            assertThat(realFuture.get(10, TimeUnit.SECONDS)).isSameAs(real);
        } finally {
            pool.shutdownNow();
        }
        verify(batchService, times(2)).create(realParams);
    }

    @Test
    @DisplayName("primer vs adoption: an ambiguous primer create leaves any batch it made un-adoptable")
    void ambiguousPrimerCreate_isNotAdoptedByASubsequentRealRetry() {
        useRetryWithMaxAttempts(2);
        BatchCreateParams primerParams = uniqueParams("primer-ambiguous");
        AnthropicIoException timeout = new AnthropicIoException("read timeout");
        when(batchService.create(eq(primerParams), any(RequestOptions.class))).thenThrow(timeout);
        assertThatThrownBy(() -> client.createPrimerBatch(primerParams, Duration.ofSeconds(15)))
                .isSameAs(timeout);
        // Anthropic did create it, unbeknownst to us
        MessageBatch phantomPrimer = aBatch("msgbatch_phantom_primer",
                OffsetDateTime.ofInstant(FIRST_ATTEMPT_INSTANT.plusSeconds(2), ZoneOffset.UTC), 1);

        BatchCreateParams realParams = uniqueParams("real-after-ambiguous");
        MessageBatch real = aBatch("msgbatch_real_after", OffsetDateTime.now(), 1);
        AnthropicServiceException serverError = serviceException(500);
        BatchListPage page = mock(BatchListPage.class);
        when(page.items()).thenReturn(List.of(phantomPrimer));
        when(batchService.list(any(BatchListParams.class))).thenReturn(page);
        when(batchService.create(realParams)).thenThrow(serverError).thenReturn(real);

        MessageBatch result = client.createBatch(realParams);

        assertThat(result).isSameAs(real);
        verify(batchService, times(2)).create(realParams);
    }

    @Test
    @DisplayName("primer vs adoption: a failed primer does not stop a real submission adopting a batch "
            + "created outside the primer window")
    void ambiguousPrimerWindow_doesNotHideABatchCreatedAfterIt() {
        useRetryWithMaxAttempts(2);
        BatchCreateParams primerParams = uniqueParams("primer-window");
        when(batchService.create(eq(primerParams), any(RequestOptions.class)))
                .thenThrow(new AnthropicIoException("read timeout"));
        assertThatThrownBy(() -> client.createPrimerBatch(primerParams, Duration.ofSeconds(15)))
                .isInstanceOf(AnthropicIoException.class);
        MessageBatch orphan = aBatch("msgbatch_orphan_later",
                OffsetDateTime.ofInstant(FIRST_ATTEMPT_INSTANT.plus(AnthropicBatchClient.PRIMER_WINDOW_GRACE)
                        .plusSeconds(1), ZoneOffset.UTC), 1);
        BatchCreateParams realParams = uniqueParams("real-later");
        AnthropicServiceException serverError = serviceException(500);
        BatchListPage page = mock(BatchListPage.class);
        when(page.items()).thenReturn(List.of(orphan));
        when(batchService.list(any(BatchListParams.class))).thenReturn(page);
        when(batchService.create(realParams)).thenThrow(serverError);

        MessageBatch result = client.createBatch(realParams);

        assertThat(result).isSameAs(orphan);
    }

    @Test
    @DisplayName("primer vs adoption: a real submission still adopts its own ambiguous create after a primer ran")
    void realOwnAmbiguousCreate_stillAdopted_afterASuccessfulPrimer() {
        useRetryWithMaxAttempts(2);
        BatchCreateParams primerParams = uniqueParams("primer-before");
        MessageBatch primer = aBatch("msgbatch_primer_before",
                OffsetDateTime.ofInstant(FIRST_ATTEMPT_INSTANT.plusSeconds(1), ZoneOffset.UTC), 1);
        when(batchService.create(eq(primerParams), any(RequestOptions.class))).thenReturn(primer);
        client.createPrimerBatch(primerParams, Duration.ofSeconds(15));

        BatchCreateParams realParams = uniqueParams("real-own");
        MessageBatch ownOrphan = aBatch("msgbatch_own_orphan",
                OffsetDateTime.ofInstant(FIRST_ATTEMPT_INSTANT.plusSeconds(3), ZoneOffset.UTC), 1);
        AnthropicIoException timeout = new AnthropicIoException("read timeout");
        BatchListPage page = mock(BatchListPage.class);
        when(page.items()).thenReturn(List.of(ownOrphan, primer));
        when(batchService.list(any(BatchListParams.class))).thenReturn(page);
        when(batchService.create(realParams)).thenThrow(timeout);

        MessageBatch result = client.createBatch(realParams);

        assertThat(result).isSameAs(ownOrphan);
        verify(batchService, times(1)).create(realParams);
    }

    @Test
    @DisplayName("createPrimerBatch: gives up within its budget when a long real submission holds the guard")
    void createPrimerBatch_guardHeld_givesUpWithinBudget() throws Exception {
        BatchCreateParams realParams = uniqueParams("real-long");
        BatchCreateParams primerParams = uniqueParams("primer-blocked");
        CountDownLatch realInCreate = new CountDownLatch(1);
        CountDownLatch releaseReal = new CountDownLatch(1);
        MessageBatch real = aBatch("msgbatch_real_long", OffsetDateTime.now(), 1);
        useRetryWithMaxAttempts(1);
        when(batchService.create(realParams)).thenAnswer(inv -> {
            realInCreate.countDown();
            assertThat(releaseReal.await(10, TimeUnit.SECONDS)).isTrue();
            return real;
        });
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var realFuture = pool.submit(() -> client.createBatch(realParams));
            assertThat(realInCreate.await(10, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            assertThatThrownBy(() -> client.createPrimerBatch(primerParams, Duration.ofMillis(200)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("guard busy");
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

            assertThat(elapsedMs).isLessThan(2000);
            verify(batchService, never()).create(eq(primerParams), any(RequestOptions.class));
            releaseReal.countDown();
            assertThat(realFuture.get(10, TimeUnit.SECONDS)).isSameAs(real);
        } finally {
            pool.shutdownNow();
        }
    }

    /** A {@link BatchCreateParams} with exactly one request, structurally unique per {@code tag}. */
    private static BatchCreateParams uniqueParams(String tag) {
        return BatchCreateParams.builder()
                .requests(List.of(BatchCreateParams.Request.builder()
                        .customId("unique-" + tag)
                        .params(BatchCreateParams.Request.Params.builder()
                                .model("claude-sonnet-4-6")
                                .maxTokens(1024)
                                .addUserMessage("test")
                                .build())
                        .build()))
                .build();
    }
}
