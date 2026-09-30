package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.BatchListParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchRequestCounts;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Retry-hardened wrapper around a single Anthropic Batch API call: creating a batch.
 *
 * <p>The Anthropic SDK client built in {@code AppConfig.anthropicClient} already retries
 * 408/409/429/5xx and I/O failures internally (default {@code ClientOptions.maxRetries = 2},
 * so up to 3 attempts within a few seconds), which is why the 2026-09-29 incident's three
 * batch submissions still failed outright after that layer gave up. This class is a SECOND,
 * much longer-window retry on top: up to a few minutes of backoff, applied only around batch
 * creation, which fires a handful of times per cycle rather than once per location.
 *
 * <p><b>Why this is a separate bean rather than a self-call inside {@code
 * BatchSubmissionService}.</b> Resilience4j's declarative {@code @Retry} works by AOP proxy —
 * a method call has to cross the proxy boundary for the interceptor to run. A private or
 * self-invoked method on the same bean never crosses that boundary, so the annotation would be
 * silently inert. Putting {@link #createBatch} on its own {@code @Service} bean, injected
 * separately, is what makes the interception real.
 *
 * <p><b>Why the retry itself is driven programmatically rather than via {@code @Retry}.</b>
 * The duplicate-batch guard below needs two pieces of per-call state: whether the CURRENT
 * invocation is a retry (not the first attempt), and the first attempt's start instant, which
 * anchors the adoption search. An AOP-woven {@code @Retry} method is re-invoked from scratch on
 * every attempt with no visibility into which attempt it is — smuggling that through a
 * {@code ThreadLocal} would be far less clear than simply closing over an
 * {@link AtomicInteger} and an {@link Instant} in a lambda passed to
 * {@link Retry#executeSupplier}. The Resilience4j registry is still the single source of
 * policy: the {@value #RETRY_INSTANCE_NAME} instance's max attempts, backoff and exception
 * predicate all come from {@code application-*.yml} and {@code ResilienceConfig}'s
 * {@code anthropicBatchRetryCustomizer} exactly as they would for an annotated method — this
 * class only chooses to drive that instance's decorator directly instead of through a proxy.
 * It deliberately does not share the {@code "anthropic"} instance (or its circuit breaker):
 * batch creation is a handful of calls per cycle and must not trip, or be tripped by, the
 * per-location synchronous path's breaker state.
 *
 * <p><b>Why a retry needs a duplicate-batch guard at all.</b> Batch creation is not
 * idempotent — the SDK sends no idempotency key (see {@code AppConfig.anthropicClient}) — so a
 * retry after a failure where Anthropic had in fact already created the batch (a read timeout
 * after acceptance is the realistic case; the OkHttp call timeout is 90s) would create a
 * second, paid batch that nothing locally tracks: exactly the "ORPHANED BATCH" shape {@link
 * BatchSubmissionService} already treats as serious. {@link #findAdoptableBatch} runs before
 * every retry attempt (never the first) and, when it finds a batch Anthropic already created
 * for this same request, adopts it instead of creating a duplicate.
 */
@Service
public class AnthropicBatchClient {

    private static final Logger LOG = LoggerFactory.getLogger(AnthropicBatchClient.class);

    /** Name of the Resilience4j retry instance this client drives — see {@code application-*.yml}. */
    static final String RETRY_INSTANCE_NAME = "anthropic-batch";

    /**
     * How many recently-created batches to list when checking whether a failed-looking attempt
     * actually created a batch at Anthropic. Bounded deliberately small — the adoption check only
     * ever needs to see batches created in the last few minutes by this process's own retries.
     */
    private static final long RECENT_BATCH_LIST_LIMIT = 20L;

    /**
     * Tolerance for clock skew between this JVM and Anthropic's reported {@code createdAt} when
     * deciding whether a listed batch could be the one a failed-looking attempt actually created.
     */
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofSeconds(5);

    private final AnthropicClient anthropicClient;
    private final RetryRegistry retryRegistry;
    private final Clock clock;

    /**
     * Constructs the client.
     *
     * @param anthropicClient raw Anthropic SDK client
     * @param retryRegistry   Resilience4j registry holding the declaratively-configured
     *                        {@value #RETRY_INSTANCE_NAME} retry instance (predicate from {@link
     *                        com.gregochr.goldenhour.config.BatchSubmitRetryPredicate}, numbers
     *                        from {@code application-*.yml})
     * @param clock           injectable clock — the first attempt's start instant anchors the
     *                        duplicate-batch adoption check
     */
    public AnthropicBatchClient(AnthropicClient anthropicClient, RetryRegistry retryRegistry, Clock clock) {
        this.anthropicClient = anthropicClient;
        this.retryRegistry = retryRegistry;
        this.clock = clock;
    }

    /**
     * Creates a batch, retrying transient failures with the {@value #RETRY_INSTANCE_NAME}
     * instance's backoff policy. On any attempt beyond the first, checks first whether the
     * previous attempt actually created the batch despite looking like a failure locally, and
     * adopts it rather than risking a paid duplicate — see {@link #findAdoptableBatch}.
     *
     * @param params the batch creation parameters
     * @return the created (or adopted) batch
     * @throws BatchRetryExhaustedException if every attempt failed — wraps the last failure and
     *                                       records how many attempts were made
     */
    public MessageBatch createBatch(BatchCreateParams params) {
        Instant firstAttemptStart = Instant.now(clock);
        AtomicInteger attemptCounter = new AtomicInteger(0);
        Retry retry = retryRegistry.retry(RETRY_INSTANCE_NAME);
        try {
            return retry.executeSupplier(
                    () -> attemptCreate(params, firstAttemptStart, attemptCounter));
        } catch (RuntimeException e) {
            throw new BatchRetryExhaustedException(attemptCounter.get(), e);
        }
    }

    private MessageBatch attemptCreate(BatchCreateParams params, Instant firstAttemptStart,
            AtomicInteger attemptCounter) {
        int attemptNumber = attemptCounter.incrementAndGet();
        if (attemptNumber > 1) {
            Optional<MessageBatch> adopted = findAdoptableBatch(params, firstAttemptStart, attemptNumber);
            if (adopted.isPresent()) {
                return adopted.get();
            }
        }
        return anthropicClient.messages().batches().create(params);
    }

    /**
     * Looks for a recently-created batch that matches the request this attempt is about to
     * (re)submit, so a retry after a failure that actually succeeded remotely adopts the existing
     * batch instead of creating a paid duplicate.
     *
     * <p>A match is a batch whose {@code createdAt} is at or after {@code firstAttemptStart}
     * (minus {@link #CLOCK_SKEW_TOLERANCE}) and whose total request count (processing +
     * succeeded + errored + canceled + expired) equals {@code params.requests().size()}. Exactly
     * one match is adopted directly; more than one adopts the newest and logs every candidate id,
     * since two genuinely distinct same-size batches created seconds apart is the one shape this
     * id-free evidence cannot fully disambiguate. The list call itself is best-effort: if it
     * fails, this returns empty so the caller proceeds to create a batch rather than blocking a
     * retry on a diagnostic-only check.
     *
     * @param params            the batch creation parameters for the attempt about to run
     * @param firstAttemptStart the original attempt's start instant
     * @param attemptNumber     the current attempt number, for logging only
     * @return the batch to adopt, or empty if none was found (or the check itself failed)
     */
    private Optional<MessageBatch> findAdoptableBatch(BatchCreateParams params,
            Instant firstAttemptStart, int attemptNumber) {
        List<MessageBatch> recent;
        try {
            recent = anthropicClient.messages().batches()
                    .list(BatchListParams.builder().limit(RECENT_BATCH_LIST_LIMIT).build())
                    .items();
        } catch (RuntimeException e) {
            LOG.warn("Batch-adoption check failed on retry attempt {} — proceeding to create a new "
                    + "batch rather than block the retry on a diagnostic-only check: {}",
                    attemptNumber, e.getMessage());
            return Optional.empty();
        }

        int expectedRequestCount = params.requests().size();
        Instant cutoff = firstAttemptStart.minus(CLOCK_SKEW_TOLERANCE);
        List<MessageBatch> matches = recent.stream()
                .filter(b -> !b.createdAt().toInstant().isBefore(cutoff))
                .filter(b -> totalRequestCount(b) == expectedRequestCount)
                .sorted(Comparator.comparing((MessageBatch b) -> b.createdAt().toInstant()).reversed())
                .toList();

        if (matches.isEmpty()) {
            return Optional.empty();
        }
        MessageBatch newest = matches.get(0);
        if (matches.size() > 1) {
            LOG.warn("Batch-adoption check on retry attempt {} found {} candidate batches created "
                    + "at or after {} with {} total request(s) — adopting the newest, {}. All "
                    + "candidates: {}", attemptNumber, matches.size(), firstAttemptStart,
                    expectedRequestCount, newest.id(),
                    matches.stream().map(MessageBatch::id).toList());
        } else {
            LOG.warn("Adopted batch {} (created {}) instead of creating a duplicate — a previous "
                    + "attempt that looked like it failed had already created it at Anthropic with "
                    + "the same {} request(s) (retry attempt {})",
                    newest.id(), newest.createdAt(), expectedRequestCount, attemptNumber);
        }
        return Optional.of(newest);
    }

    private static long totalRequestCount(MessageBatch batch) {
        MessageBatchRequestCounts counts = batch.requestCounts();
        return counts.processing() + counts.succeeded() + counts.errored()
                + counts.canceled() + counts.expired();
    }
}
