package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.BatchListParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchRequestCounts;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

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
 *
 * <p><b>Why the guard also needs to exclude TRACKED batches and refuse an ambiguous match
 * (round 2, a Codex review of #949, P1-A).</b> The first cut's match test — {@code createdAt} at
 * or after the first attempt's start (with a small negative clock-skew tolerance) and the same
 * total request count — is not enough on its own: a SIBLING bucket of the SAME size, submitted
 * moments earlier in the same cycle, satisfies both conditions just as well as this attempt's own
 * orphan would. {@link BatchSubmissionService#submit} persists a bucket's {@code forecast_batch}
 * row immediately after {@code create()} returns and BEFORE the next bucket is even built, so by
 * construction every genuinely-succeeded sibling is already tracked by the time a later bucket's
 * retry runs this check — {@link #findAdoptableBatch} excludes any candidate {@link
 * ForecastBatchRepository#existsByAnthropicBatchId} already knows about. Once siblings are
 * excluded, at most one untracked candidate is the common case and is adopted as before; if MORE
 * THAN ONE untracked candidate still matches, there is no further evidence to break the tie (the
 * list API exposes no request contents to fingerprint against), so guessing risks attaching the
 * WRONG batch id to this bucket's tasks — worse than the attempt simply failing. That case logs
 * every candidate id at ERROR as a possible orphan for manual recovery and throws {@link
 * AmbiguousBatchAdoptionException} rather than adopting OR creating another batch.
 *
 * <p><b>Clock-skew tolerance was dropped, not narrowed (round 2, P1-A).</b> The first cut allowed
 * a candidate created up to 5 seconds BEFORE the first attempt's own start, to absorb clock skew
 * between this JVM and Anthropic's reported {@code createdAt}. Once tracked siblings are excluded,
 * that tolerance's only remaining job is absorbing genuine clock skew — but it also reopens the
 * exact risk it was meant to guard against, in miniature: an unrelated, untracked (e.g. genuinely
 * orphaned) batch of the same size created moments earlier can still fall inside the window and
 * either be wrongly adopted (if it is the only one) or manufacture a false ambiguity (if this
 * attempt's own orphan is also present). This JVM's host is NTP-synced (production runs in Docker
 * on a Mac mini on a home network, not a cloud host with a guaranteed-close clock to Anthropic's,
 * but NTP still keeps sub-second skew the realistic case), so the cutoff is now exactly {@code
 * firstAttemptStart} with no tolerance in either direction — the boundary is inclusive
 * ({@code createdAt >= firstAttemptStart}). The residual cost of dropping it is real, not merely
 * theoretical, and worth stating plainly rather than as a safety net: if Anthropic's clock is
 * measurably behind this JVM's, a genuine orphan from THIS attempt is missed and a further
 * duplicate is created on the next attempt — and that duplicate is logged by NOTHING. It is not
 * the "ORPHANED BATCH" shape {@link BatchSubmissionService} surfaces (that log fires only when
 * {@code create()} succeeded and the subsequent {@code forecast_batch} save failed); here both
 * succeed, so the batch runs, is billed, and its results are never collected or processed. That
 * silent extra cost is still the preferred failure mode over the alternative: adopting the wrong
 * batch id risks wrong ratings landing against this bucket's tasks, or an outright unique-
 * constraint failure on {@code forecast_batch.anthropic_batch_id} — a cost that is silent AND
 * cheap beats one that is loud AND corrupting.
 *
 * <p><b>Why {@code create()} and {@code list()} run on a SEPARATE, transport-retry-disabled
 * client (round 2, P1-B).</b> The shared {@code AnthropicClient} injected here still retries
 * 408/409/429/5xx internally by default — so a single attempt THIS class's own retry loop counts
 * as one could, via a slow response after Anthropic already accepted the batch, fire up to three
 * non-idempotent {@code create()} calls at the transport layer before this class's own duplicate-
 * batch guard ever runs on the NEXT outer attempt. {@link #batchClient} is derived once, in the
 * constructor — {@code anthropicClient.withOptions(o -> o.maxRetries(0))} — and used for every
 * {@code create()} and {@code list()} call this class makes, so every actual HTTP attempt is one
 * this class's own guard sees and can react to. A consequence: with the transport layer no longer
 * retrying 429 for this call, {@link com.gregochr.goldenhour.config.BatchSubmitRetryPredicate} now
 * retries 429 itself (the one exception to "never a 4xx" — see its own javadoc for why that is
 * safe here specifically).
 *
 * <p><b>Why batch creation is serialized within this JVM, and why a handed-out-id record is
 * still needed on top (round 3, a Codex review of #949's 8b8ed0dd).</b> Round 2 closed the
 * single-submitter case, but two DIFFERENT submitters can race: {@code
 * ScheduledBatchEvaluationService} guards forecast and aurora submission with two SEPARATE
 * {@code AtomicBoolean}s (so a forecast bucket and an aurora batch can submit concurrently), and
 * {@code SkyRatingEvalBatchService}/{@code SkyRatingEvalBatchClient} has no submission guard at
 * all. If submitter A's {@code create()} times out after Anthropic had already accepted it, while
 * submitter B's {@code create()} succeeds around the same moment, A's retry can list recent
 * batches and see B's batch as the only UNTRACKED same-size match — {@link
 * ForecastBatchRepository#existsByAnthropicBatchId} misses it because B has not yet persisted its
 * own {@code forecast_batch} row (that happens in B's CALLER, after {@link #createBatch} already
 * returned), and a sky-rating batch is never stored there at all. A then adopts B's batch, whose
 * {@code custom_id}s are wrong for A's tasks.
 *
 * <p>{@link #creationLock}, a {@link ReentrantLock} instance field (this bean is a singleton, so
 * one field serializes every caller in the process), is held for the ENTIRE body of {@link
 * #createBatch} — including its retry waits, not just the HTTP calls — so no two {@code
 * createBatch} invocations from this process can ever be in flight at once. A {@link
 * ReentrantLock} rather than a {@code synchronized} method deliberately: this project runs on
 * virtual threads, and a thread parked in {@code synchronized} pins its carrier thread for the
 * whole wait, while {@code ReentrantLock.lock()} does not. Waiting up to the retry policy's full
 * ~3.5 minutes behind another submitter's retries is acceptable here: batch creation is rare (a
 * handful of calls per cycle), forecast buckets are already submitted sequentially within one
 * cycle regardless, the aurora batch job is seeded PAUSED, and the sky-rating batch runs weekly.
 *
 * <p>The lock alone is not sufficient, though: it only prevents two calls being IN FLIGHT
 * together, not one call seeing a batch a JUST-FINISHED call returned. Once A releases the lock
 * (having adopted or created), B can acquire it, fail, list, and see A's batch — still untracked
 * in {@code forecast_batch}, because A's caller has not persisted it yet, or as with sky-rating,
 * never will. {@link #handedOutBatchIds} closes that gap: every id {@link #createBatch} is about
 * to return — created or adopted — is recorded into it BEFORE the lock is released, and {@link
 * #findAdoptableBatch} excludes any id already in it, alongside the {@code forecast_batch} check.
 * It is a plain {@link LinkedHashMap} with an overridden {@code removeEldestEntry}, guarded by
 * {@code synchronized} blocks (a quick map mutation, never a blocking wait, so pinning a virtual
 * thread's carrier here is not the concern {@link #creationLock} exists for) rather than {@link
 * java.util.Collections#synchronizedMap}, so the eviction check and the put stay visibly paired at
 * each call site. Bounded at {@value #HANDED_OUT_ID_CAP} entries, oldest inserted evicted first:
 * a batch old enough to fall out of a window this small was created before any submission this
 * process could still plausibly be retrying (the retry policy's own ~3.5-minute ceiling bounds how
 * long a single {@code createBatch} call, and therefore how stale an in-flight sibling's id, can
 * be), so evicting it is safe.
 *
 * <p><b>Remaining limit.</b> Both mechanisms are scoped to THIS JVM. A different process — another
 * instance of this application, or a local dev run pointed at the production API key — shares
 * neither the lock nor the handed-out-id record, and could in principle have its batch adopted by
 * this one in the rare case both create a same-size batch inside the same short retry window. The
 * ambiguity refusal in {@link #findAdoptableBatch} still catches the case where both that batch
 * and this attempt's own orphan are simultaneously visible and untracked; it cannot catch the case
 * where only the OTHER process's batch is visible and this one's own orphan is not (e.g. because
 * this attempt in fact failed outright and created nothing) — that is a single, unresolvable
 * candidate, adopted exactly as any other single match is.
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
     * Bound on {@link #handedOutBatchIds} — see the class javadoc's round-3 section for why this
     * size is safe.
     */
    static final int HANDED_OUT_ID_CAP = 500;

    /**
     * The shared client with transport-level retries disabled ({@code maxRetries = 0}), derived
     * once in the constructor. See the class javadoc's P1-B section for why every {@code create()}
     * and {@code list()} call in this class must go through this client rather than the raw one.
     */
    private final AnthropicClient batchClient;

    private final RetryRegistry retryRegistry;
    private final Clock clock;
    private final ForecastBatchRepository forecastBatchRepository;

    /**
     * Serializes every {@link #createBatch} call from this process — see the class javadoc's
     * round-3 section. A {@link ReentrantLock} rather than {@code synchronized}: this project runs
     * on virtual threads, and only {@code ReentrantLock.lock()} avoids pinning the carrier thread
     * for the whole wait.
     */
    private final ReentrantLock creationLock = new ReentrantLock();

    /**
     * Every batch id this instance's {@link #createBatch} has returned — created or adopted —
     * recorded before {@link #creationLock} is released. See the class javadoc's round-3 section
     * for why {@link ForecastBatchRepository} tracking alone is not enough and for the bound's
     * justification. Plain {@link LinkedHashMap}, not {@link java.util.Collections#synchronizedMap},
     * guarded by explicit {@code synchronized} blocks at each of its two call sites.
     */
    private final Map<String, Boolean> handedOutBatchIds = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > HANDED_OUT_ID_CAP;
        }
    };

    /**
     * Constructs the client.
     *
     * @param anthropicClient        raw Anthropic SDK client — used only to derive {@link
     *                               #batchClient} once, here; every call this class makes goes
     *                               through the derived client instead
     * @param retryRegistry          Resilience4j registry holding the declaratively-configured
     *                               {@value #RETRY_INSTANCE_NAME} retry instance (predicate from
     *                               {@link com.gregochr.goldenhour.config.BatchSubmitRetryPredicate},
     *                               numbers from {@code application-*.yml})
     * @param clock                  injectable clock — the first attempt's start instant anchors
     *                               the duplicate-batch adoption check
     * @param forecastBatchRepository used to exclude an already-tracked batch (most importantly, an
     *                               earlier bucket of the same cycle) from the adoption candidates
     */
    public AnthropicBatchClient(AnthropicClient anthropicClient, RetryRegistry retryRegistry, Clock clock,
            ForecastBatchRepository forecastBatchRepository) {
        this.batchClient = anthropicClient.withOptions(options -> options.maxRetries(0));
        this.retryRegistry = retryRegistry;
        this.clock = clock;
        this.forecastBatchRepository = forecastBatchRepository;
    }

    /**
     * Creates a batch, retrying transient failures with the {@value #RETRY_INSTANCE_NAME}
     * instance's backoff policy. On any attempt beyond the first, checks first whether the
     * previous attempt actually created the batch despite looking like a failure locally, and
     * adopts it rather than risking a paid duplicate — see {@link #findAdoptableBatch}.
     *
     * <p>Serialized against every other caller in this process via {@link #creationLock} — see
     * the class javadoc's round-3 section — for the whole call, including retry waits. The
     * returned batch's id is recorded into {@link #handedOutBatchIds} before the lock is released.
     *
     * @param params the batch creation parameters
     * @return the created (or adopted) batch
     * @throws BatchRetryExhaustedException if every attempt failed — wraps the last failure and
     *                                       records how many attempts were made. The wrapped cause
     *                                       is {@link AmbiguousBatchAdoptionException} when the
     *                                       failure was an unresolvable adoption ambiguity rather
     *                                       than an Anthropic-side error.
     */
    public MessageBatch createBatch(BatchCreateParams params) {
        creationLock.lock();
        try {
            Instant firstAttemptStart = Instant.now(clock);
            AtomicInteger attemptCounter = new AtomicInteger(0);
            Retry retry = retryRegistry.retry(RETRY_INSTANCE_NAME);
            MessageBatch batch;
            try {
                batch = retry.executeSupplier(
                        () -> attemptCreate(params, firstAttemptStart, attemptCounter));
            } catch (RuntimeException e) {
                throw new BatchRetryExhaustedException(attemptCounter.get(), e);
            }
            recordHandedOut(batch.id());
            return batch;
        } finally {
            creationLock.unlock();
        }
    }

    /**
     * Creates a cache-primer batch: one attempt, no retry, no duplicate-batch adoption and no
     * {@link #creationLock} (a primer must never wait behind, or delay, a real submission). The
     * returned id is still recorded as handed out, so a concurrent real submission's retry can
     * never adopt a primer as its own batch.
     *
     * @param params the one-request primer batch
     * @return the created batch
     */
    public MessageBatch createPrimerBatch(BatchCreateParams params) {
        MessageBatch batch = batchClient.messages().batches().create(params);
        recordHandedOut(batch.id());
        return batch;
    }

    /**
     * Retrieves a batch's current state, on the transport-retry-disabled client.
     *
     * @param batchId Anthropic batch id
     * @return the batch as Anthropic reports it
     */
    public MessageBatch retrieveBatch(String batchId) {
        return batchClient.messages().batches().retrieve(batchId);
    }

    private void recordHandedOut(String batchId) {
        synchronized (handedOutBatchIds) {
            handedOutBatchIds.put(batchId, Boolean.TRUE);
        }
    }

    private boolean wasHandedOutByThisProcess(String batchId) {
        synchronized (handedOutBatchIds) {
            return handedOutBatchIds.containsKey(batchId);
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
        return batchClient.messages().batches().create(params);
    }

    /**
     * Looks for a recently-created, UNTRACKED batch that matches the request this attempt is
     * about to (re)submit, so a retry after a failure that actually succeeded remotely adopts the
     * existing batch instead of creating a paid duplicate.
     *
     * <p>A candidate is a batch whose {@code createdAt} is at or after {@code firstAttemptStart}
     * (no tolerance — see the class javadoc's P1-A section), whose total request count
     * (processing + succeeded + errored + canceled + expired) equals {@code
     * params.requests().size()}, whose id {@link ForecastBatchRepository} does not already know
     * about (excludes a genuinely-succeeded sibling bucket, which is tracked by the time this
     * runs), and whose id is not already in {@link #handedOutBatchIds} (excludes a batch THIS
     * client already returned to a different, concurrent caller — see the class javadoc's
     * round-3 section for why the {@code forecast_batch} check alone misses that case). Exactly
     * one such candidate is adopted directly. Two or more is refused outright — see {@link
     * AmbiguousBatchAdoptionException}. The list call itself is best-effort: if it fails, this
     * returns empty so the caller proceeds to create a batch rather than blocking a retry on a
     * diagnostic-only check.
     *
     * @param params            the batch creation parameters for the attempt about to run
     * @param firstAttemptStart the original attempt's start instant
     * @param attemptNumber     the current attempt number, for logging only
     * @return the batch to adopt, or empty if none was found (or the check itself failed)
     * @throws AmbiguousBatchAdoptionException if more than one untracked candidate matches
     */
    private Optional<MessageBatch> findAdoptableBatch(BatchCreateParams params,
            Instant firstAttemptStart, int attemptNumber) {
        List<MessageBatch> recent;
        try {
            recent = batchClient.messages().batches()
                    .list(BatchListParams.builder().limit(RECENT_BATCH_LIST_LIMIT).build())
                    .items();
        } catch (RuntimeException e) {
            LOG.warn("Batch-adoption check failed on retry attempt {} — proceeding to create a new "
                    + "batch rather than block the retry on a diagnostic-only check: {}",
                    attemptNumber, e.getMessage());
            return Optional.empty();
        }

        int expectedRequestCount = params.requests().size();
        List<MessageBatch> untrackedMatches = recent.stream()
                .filter(b -> !b.createdAt().toInstant().isBefore(firstAttemptStart))
                .filter(b -> totalRequestCount(b) == expectedRequestCount)
                .filter(b -> !forecastBatchRepository.existsByAnthropicBatchId(b.id()))
                .filter(b -> !wasHandedOutByThisProcess(b.id()))
                .sorted(Comparator.comparing((MessageBatch b) -> b.createdAt().toInstant()).reversed())
                .toList();

        if (untrackedMatches.isEmpty()) {
            return Optional.empty();
        }
        if (untrackedMatches.size() > 1) {
            List<String> candidateIds = untrackedMatches.stream().map(MessageBatch::id).toList();
            LOG.error("Batch-adoption check on retry attempt {} found {} UNTRACKED candidate "
                    + "batches created at or after {} with {} total request(s), and cannot safely "
                    + "tell them apart — refusing to adopt any of them or create another. Possible "
                    + "orphans needing manual recovery: {}", attemptNumber, untrackedMatches.size(),
                    firstAttemptStart, expectedRequestCount, candidateIds);
            throw new AmbiguousBatchAdoptionException(
                    "Ambiguous batch adoption on retry attempt " + attemptNumber + ": "
                            + untrackedMatches.size() + " untracked candidates " + candidateIds);
        }
        MessageBatch adopted = untrackedMatches.get(0);
        LOG.warn("Adopted batch {} (created {}) instead of creating a duplicate — a previous "
                + "attempt that looked like it failed had already created it at Anthropic with "
                + "the same {} request(s) (retry attempt {})",
                adopted.id(), adopted.createdAt(), expectedRequestCount, attemptNumber);
        return Optional.of(adopted);
    }

    private static long totalRequestCount(MessageBatch batch) {
        MessageBatchRequestCounts counts = batch.requestCounts();
        return counts.processing() + counts.succeeded() + counts.errored()
                + counts.canceled() + counts.expired();
    }
}
