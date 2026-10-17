package com.gregochr.goldenhour.service.batch;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.gregochr.goldenhour.config.BatchCachePrimerProperties;
import com.gregochr.goldenhour.service.evaluation.BatchRequestFactory;
import com.gregochr.goldenhour.service.evaluation.CustomIdFactory;
import com.gregochr.goldenhour.model.CacheDiagnostics;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import com.gregochr.goldenhour.service.evaluation.PrimerMessageIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Warms the Anthropic prompt cache before a scheduled cycle submits its real sky buckets.
 *
 * <p>Every request in a batch bucket shares one cached system prompt. When a batch's requests
 * start concurrently before any has written the cache, each one WRITES it (1.25x input price)
 * instead of reading it (0.1x). Measured on 2026-10-04 against the live API: a cold batch of 12
 * gave 1 read and 11 writes; an ordinary warm-up call first gave 4 reads and 8 writes (a standard
 * call's cache is not reliably picked up by a batch); a one-request <em>primer batch</em> first,
 * waited to completion, then a batch of 12 with the identical system block and a one-hour cache
 * lifetime gave 12 reads and 0 writes.
 *
 * <p>Per distinct cache prefix (model id plus coastal-or-inland prompt builder) over EVERY SKY task
 * of the four colour buckets, this submits one one-request batch built through the same {@link
 * BatchRequestFactory} path as the real requests, then waits, for at most {@code wait-seconds} in
 * total, for them to end. {@link #prime} returns the prefixes whose primer ended with a succeeded
 * request: ONLY those prefixes' real requests are then built with the one-hour lifetime (a
 * one-hour write costs 2x input against 1.25x, so it must never be paid without a primed cache to
 * read). Every other case - flag off, {@code wait-seconds} 0, submit failure, primer ended without
 * a success, timeout, interrupt - warms nothing and the real requests are byte-identical to what
 * was sent before the primer existed.
 *
 * <p><b>Fail-open and bounded.</b> Nothing here may fail the cycle or delay it beyond the cap. The
 * deadline is checked before every create and every read, each call carries a short timeout, a
 * read that errors counts as "still pending" (a transient 429/5xx must not abandon a primer that
 * is still running), and every exception is caught inside {@link #prime}. An interrupt restores
 * the thread's flag, stops waiting and warms nothing.
 *
 * <p><b>Invisible to the batch tables.</b> A primer is created directly through {@link
 * AnthropicBatchClient#createPrimerBatch}, never through {@code BatchSubmissionService}, so no
 * {@code forecast_batch}, {@code job_run}, {@code api_call_log} or disposition row exists for it;
 * the pollers, result processor, retry service and {@code LocationFailureService} never learn of
 * it. Its result IS read, once, by this class alone and only to be logged: after a primer ends,
 * {@link #readPrimerMessages} reads its one response and puts what it wrote to the cache (total
 * and one-hour tokens), what it read, its Anthropic message id and the API's cache diagnostics on the
 * cycle's INFO line, which is how "did the primer's one-hour write land" is answered from the logs.
 * The message ids are also published to {@link PrimerMessageIds} for the requests the primer warmed
 * to name as their previous message (only when {@code diagnostics} is on). A failed read changes
 * nothing else: fail-open, like everything here. Consequently its own cost (one full request per prefix per
 * cycle, with a one-hour cache write) is not recorded anywhere. Its custom id ({@code pw-n}) is
 * rejected by {@link CustomIdFactory#parse}.
 */
@Component
public class BatchCachePrimer {

    private static final Logger LOG = LoggerFactory.getLogger(BatchCachePrimer.class);

    /** Timeout for one create or read call, further shortened to the time left before the cap. */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);

    /** Outcome of one primer, for the cycle's single INFO line. */
    public enum Outcome {
        /** The primer batch ended with its request succeeded: the prefix is warmed. */
        ENDED,
        /** The primer was not submitted in time, or had not ended when the wait ran out. */
        TIMED_OUT,
        /** The primer could not be submitted, or ended without a succeeded request. */
        FAILED,
        /** The wait was interrupted before the primer ended. */
        INTERRUPTED
    }

    /**
     * What a {@link #prime} call achieved.
     *
     * @param warmedPrefixes  prefix keys whose primer ended with a succeeded request
     * @param outcomes        every primed prefix's outcome, in submission order
     * @param unreadablePolls how many status reads failed and were treated as still pending
     */
    public record PrimeResult(Set<String> warmedPrefixes, Map<String, Outcome> outcomes,
            int unreadablePolls) {

        /**
         * Returns the result of a primer that did nothing.
         *
         * @return an empty result
         */
        public static PrimeResult nothing() {
            return new PrimeResult(Set.of(), Map.of(), 0);
        }
    }

    /** Pauses the wait loop; injectable so tests never sleep for real. */
    @FunctionalInterface
    interface Sleeper {
        /**
         * Sleeps for the given duration.
         *
         * @param duration how long to sleep
         * @throws InterruptedException if the thread is interrupted
         */
        void sleep(Duration duration) throws InterruptedException;
    }

    private final BatchRequestFactory requestFactory;
    private final AnthropicBatchClient batchClient;
    private final BatchCachePrimerProperties properties;
    private final Clock clock;
    private final Sleeper sleeper;
    private final PrimerMessageIds primerMessageIds;

    /**
     * Constructs the primer with a real-time sleeper.
     *
     * @param requestFactory   builds the primer request through the real requests' own path
     * @param batchClient      creates and retrieves the primer batches
     * @param properties       the {@code photocast.batch.cache-primer} settings
     * @param clock            measures the wait
     * @param primerMessageIds receives the message ids of the primers' responses
     */
    @Autowired
    public BatchCachePrimer(BatchRequestFactory requestFactory, AnthropicBatchClient batchClient,
            BatchCachePrimerProperties properties, Clock clock, PrimerMessageIds primerMessageIds) {
        this(requestFactory, batchClient, properties, clock,
                duration -> Thread.sleep(duration.toMillis()), primerMessageIds);
    }

    BatchCachePrimer(BatchRequestFactory requestFactory, AnthropicBatchClient batchClient,
            BatchCachePrimerProperties properties, Clock clock, Sleeper sleeper) {
        this(requestFactory, batchClient, properties, clock, sleeper, new PrimerMessageIds());
    }

    BatchCachePrimer(BatchRequestFactory requestFactory, AnthropicBatchClient batchClient,
            BatchCachePrimerProperties properties, Clock clock, Sleeper sleeper,
            PrimerMessageIds primerMessageIds) {
        this.requestFactory = requestFactory;
        this.batchClient = batchClient;
        this.properties = properties;
        this.clock = clock;
        this.sleeper = sleeper;
        this.primerMessageIds = primerMessageIds;
    }

    /**
     * Primes the cache for the distinct prefixes among the given SKY buckets and waits (at most
     * {@code wait-seconds}) for the primers to end. Never throws.
     *
     * @param skyBuckets the cycle's SKY buckets (near/far, inland/coastal), empty or null ones
     *                   included; woodland and bluebell buckets must not be passed
     * @return the warmed prefixes and per-prefix outcomes; empty when disabled or nothing to prime
     */
    public PrimeResult prime(List<List<EvaluationTask.Forecast>> skyBuckets) {
        if (!properties.isEnabled() || properties.getWaitSeconds() <= 0) {
            return PrimeResult.nothing();
        }
        try {
            return primeInternal(skyBuckets);
        } catch (RuntimeException e) {
            LOG.warn("[BATCH PRIMER] Cache primer failed unexpectedly - carrying on without it: {}",
                    e.toString());
            return PrimeResult.nothing();
        }
    }

    private PrimeResult primeInternal(List<List<EvaluationTask.Forecast>> skyBuckets) {
        Map<String, EvaluationTask.Forecast> representatives = new LinkedHashMap<>();
        for (List<EvaluationTask.Forecast> bucket : skyBuckets) {
            if (bucket == null) {
                continue;
            }
            for (EvaluationTask.Forecast task : bucket) {
                if (task.promptKind() == EvaluationTask.Forecast.PromptKind.SKY) {
                    representatives.putIfAbsent(
                            requestFactory.cachePrefixKey(task.model(), task.data()), task);
                }
            }
        }
        if (representatives.isEmpty()) {
            return PrimeResult.nothing();
        }

        Instant start = Instant.now(clock);
        Instant deadline = start.plusSeconds(properties.getWaitSeconds());
        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        Map<String, String> pending = new LinkedHashMap<>();
        Map<String, String> batchIds = new LinkedHashMap<>();
        int ordinal = 0;
        for (Map.Entry<String, EvaluationTask.Forecast> entry : representatives.entrySet()) {
            String prefix = entry.getKey();
            Duration callTimeout = callTimeout(deadline);
            if (callTimeout == null) {
                outcomes.put(prefix, Outcome.TIMED_OUT);
                LOG.warn("[BATCH PRIMER] Primer for cache prefix {} not submitted: the {}s cap passed "
                        + "first", prefix, properties.getWaitSeconds());
                continue;
            }
            try {
                EvaluationTask.Forecast task = entry.getValue();
                BatchCreateParams params = BatchCreateParams.builder()
                        .addRequest(requestFactory.buildCachePrimerRequest(
                                CustomIdFactory.forCachePrimer(ordinal++), task.model(), task.data(),
                                task.model().getMaxTokens()))
                        .build();
                String batchId = batchClient.createPrimerBatch(params, callTimeout).id();
                pending.put(prefix, batchId);
                batchIds.put(prefix, batchId);
            } catch (RuntimeException e) {
                LOG.warn("[BATCH PRIMER] Primer for cache prefix {} could not be submitted - its "
                        + "bucket(s) will write the cache as before: {}", prefix, e.toString());
                outcomes.put(prefix, Outcome.FAILED);
            }
        }

        int[] unreadable = new int[1];
        boolean interrupted = waitForEnd(deadline, pending, outcomes, unreadable);
        for (String prefix : pending.keySet()) {
            outcomes.put(prefix, interrupted ? Outcome.INTERRUPTED : Outcome.TIMED_OUT);
            LOG.warn("[BATCH PRIMER] Primer for cache prefix {} had not ended ({}) - continuing "
                    + "without it", prefix, interrupted ? "interrupted" : "cap reached");
        }
        Set<String> warmed = new LinkedHashSet<>();
        if (!interrupted) {
            outcomes.forEach((prefix, outcome) -> {
                if (outcome == Outcome.ENDED) {
                    warmed.add(prefix);
                }
            });
        }
        Map<String, String> primerResults = interrupted ? Map.of()
                : readPrimerMessages(deadline, warmed, batchIds);
        LOG.info("[BATCH PRIMER] Primed {} cache prefix(es) in {} ms, {} warmed, {} unreadable status "
                + "read(s): {}; cache results: {}", representatives.size(),
                Duration.between(start, Instant.now(clock)).toMillis(), warmed.size(), unreadable[0],
                outcomes, primerResults.isEmpty() ? "none read" : primerResults);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return new PrimeResult(Set.copyOf(warmed), outcomes, unreadable[0]);
    }

    /**
     * Reads each warmed primer's one response, publishes the message ids to {@link PrimerMessageIds}
     * (replacing the last cycle's, even when none could be read) and returns a log line per prefix:
     * message id, cache write (and its one-hour part) and read tokens, and the API's cache
     * diagnostics. Fail-open: a read that errors or finds no message is skipped, the deadline is
     * honoured, and nothing here can change which prefixes are warmed.
     */
    private Map<String, String> readPrimerMessages(Instant deadline, Set<String> warmed,
            Map<String, String> batchIds) {
        Map<String, String> ids = new LinkedHashMap<>();
        Map<String, String> lines = new LinkedHashMap<>();
        for (String prefix : warmed) {
            Duration callTimeout = callTimeout(deadline);
            if (callTimeout == null) {
                break;
            }
            try {
                Message message = batchClient.readFirstSucceededMessage(batchIds.get(prefix), callTimeout)
                        .orElse(null);
                if (message == null) {
                    continue;
                }
                ids.put(prefix, message.id());
                TokenUsage usage = TokenUsage.from(message.usage());
                lines.put(prefix, message.id() + " wrote " + usage.cacheCreationInputTokens()
                        + " tokens (" + usage.cacheCreationOneHourTokens() + " at 1h), read "
                        + usage.cacheReadInputTokens() + ", diagnostics "
                        + CacheDiagnostics.from(message).summary());
            } catch (RuntimeException e) {
                LOG.debug("[BATCH PRIMER] Could not read the result of primer batch {} for cache prefix {}: "
                        + "{}", batchIds.get(prefix), prefix, e.toString());
            }
        }
        primerMessageIds.replace(ids);
        return lines;
    }

    /** The timeout for a call made now: {@link #CALL_TIMEOUT} or the time left, or null if none. */
    private Duration callTimeout(Instant deadline) {
        Duration remaining = Duration.between(Instant.now(clock), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            return null;
        }
        return remaining.compareTo(CALL_TIMEOUT) < 0 ? remaining : CALL_TIMEOUT;
    }

    /**
     * Polls until every pending primer has ended, the cap is reached or the thread is interrupted.
     * Entries that end or fail are moved from {@code pending} into {@code outcomes}.
     *
     * @return {@code true} if interrupted
     */
    private boolean waitForEnd(Instant deadline, Map<String, String> pending,
            Map<String, Outcome> outcomes, int[] unreadable) {
        Duration pollInterval = Duration.ofSeconds(properties.getPollSeconds());
        while (!pending.isEmpty()) {
            for (Map.Entry<String, String> entry : new ArrayList<>(pending.entrySet())) {
                Duration callTimeout = callTimeout(deadline);
                if (callTimeout == null) {
                    return false;
                }
                Outcome outcome = pollOnce(entry.getKey(), entry.getValue(), callTimeout, unreadable);
                if (outcome != null) {
                    pending.remove(entry.getKey());
                    outcomes.put(entry.getKey(), outcome);
                }
            }
            Duration remaining = Duration.between(Instant.now(clock), deadline);
            if (pending.isEmpty() || remaining.isNegative() || remaining.isZero()) {
                return false;
            }
            try {
                sleeper.sleep(remaining.compareTo(pollInterval) < 0 ? remaining : pollInterval);
            } catch (InterruptedException e) {
                return true;
            }
        }
        return false;
    }

    /** Returns the primer's final outcome, or {@code null} while it is (or may still be) running. */
    private Outcome pollOnce(String prefix, String batchId, Duration callTimeout, int[] unreadable) {
        try {
            MessageBatch batch = batchClient.retrieveBatch(batchId, callTimeout);
            if (!MessageBatch.ProcessingStatus.ENDED.equals(batch.processingStatus())) {
                return null;
            }
            if (batch.requestCounts().succeeded() > 0) {
                return Outcome.ENDED;
            }
            LOG.warn("[BATCH PRIMER] Primer batch {} for cache prefix {} ended without a successful "
                    + "request (errored={}, canceled={}, expired={}) - the cache is not warm",
                    batchId, prefix, batch.requestCounts().errored(), batch.requestCounts().canceled(),
                    batch.requestCounts().expired());
            return Outcome.FAILED;
        } catch (RuntimeException e) {
            unreadable[0]++;
            LOG.debug("[BATCH PRIMER] Could not read primer batch {} for cache prefix {} - treating "
                    + "it as still pending: {}", batchId, prefix, e.toString());
            return null;
        }
    }
}
