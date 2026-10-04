package com.gregochr.goldenhour.service.batch;

import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.gregochr.goldenhour.config.BatchCachePrimerProperties;
import com.gregochr.goldenhour.service.evaluation.BatchRequestFactory;
import com.gregochr.goldenhour.service.evaluation.CustomIdFactory;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <p>So, per distinct cache prefix (model id plus coastal-or-inland prompt builder) among the
 * non-empty SKY buckets, this submits ONE one-request batch built through the same {@link
 * BatchRequestFactory} path as the real requests (identical system block, lifetime and output
 * config), then waits, for at most {@code wait-seconds} in total, for all of them to end.
 *
 * <p><b>Fail-open, always.</b> Nothing here may fail, delay beyond the cap, or alter the cycle: a
 * primer that cannot be submitted, errors, is cancelled or expired, or has not ended when the wait
 * runs out is logged at WARN and the cycle carries on. Every exception is caught inside {@link
 * #prime}; an interrupt restores the thread's interrupt flag and returns.
 *
 * <p><b>Invisible to the batch tables.</b> A primer is created directly through {@link
 * AnthropicBatchClient#createPrimerBatch}, never through {@code BatchSubmissionService}, so no
 * {@code forecast_batch}, {@code job_run}, {@code api_call_log} or disposition row exists for it:
 * {@code BatchPollingService}, {@code BatchResultProcessor}, {@code BatchRetryService} and {@code
 * LocationFailureService} never learn of it, and its result is never read. Its custom id ({@code
 * pw-n}) is rejected by {@link CustomIdFactory#parse}. The client records its id as handed out, so a
 * concurrent real submission's duplicate-batch adoption can never take it for its own.
 */
@Component
public class BatchCachePrimer {

    private static final Logger LOG = LoggerFactory.getLogger(BatchCachePrimer.class);

    /** Outcome of one primer, for the cycle's single INFO line. */
    enum Outcome {
        /** The primer batch ended with its request succeeded. */
        ENDED,
        /** The primer did not end within the wait cap. */
        TIMED_OUT,
        /** The primer could not be submitted, errored, or ended without a success. */
        FAILED
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

    /**
     * Constructs the primer with a real-time sleeper.
     *
     * @param requestFactory builds the primer request through the real requests' own path
     * @param batchClient    creates and retrieves the primer batches
     * @param properties     the {@code photocast.batch.cache-primer} settings
     * @param clock          measures the wait
     */
    @Autowired
    public BatchCachePrimer(BatchRequestFactory requestFactory, AnthropicBatchClient batchClient,
            BatchCachePrimerProperties properties, Clock clock) {
        this(requestFactory, batchClient, properties, clock,
                duration -> Thread.sleep(duration.toMillis()));
    }

    BatchCachePrimer(BatchRequestFactory requestFactory, AnthropicBatchClient batchClient,
            BatchCachePrimerProperties properties, Clock clock, Sleeper sleeper) {
        this.requestFactory = requestFactory;
        this.batchClient = batchClient;
        this.properties = properties;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * Primes the cache for the distinct prefixes among the given SKY buckets, then waits for the
     * primers to end (at most {@code wait-seconds}). Never throws.
     *
     * @param skyBuckets the cycle's SKY buckets (near/far, inland/coastal), empty ones included;
     *                   woodland and bluebell buckets must not be passed
     */
    public void prime(List<List<EvaluationTask.Forecast>> skyBuckets) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            primeInternal(skyBuckets);
        } catch (RuntimeException e) {
            LOG.warn("[BATCH PRIMER] Cache primer failed unexpectedly - carrying on without it: {}",
                    e.toString());
        }
    }

    private void primeInternal(List<List<EvaluationTask.Forecast>> skyBuckets) {
        Map<String, EvaluationTask.Forecast> representatives = new LinkedHashMap<>();
        for (List<EvaluationTask.Forecast> bucket : skyBuckets) {
            if (bucket == null) {
                continue;
            }
            for (EvaluationTask.Forecast task : bucket) {
                if (task.promptKind() != EvaluationTask.Forecast.PromptKind.SKY) {
                    continue;
                }
                representatives.putIfAbsent(requestFactory.cachePrefixKey(task.model(), task.data()), task);
                break;
            }
        }
        if (representatives.isEmpty()) {
            return;
        }

        Instant start = Instant.now(clock);
        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        Map<String, String> pending = new LinkedHashMap<>();
        int ordinal = 0;
        for (Map.Entry<String, EvaluationTask.Forecast> entry : representatives.entrySet()) {
            String prefix = entry.getKey();
            try {
                EvaluationTask.Forecast task = entry.getValue();
                BatchCreateParams params = BatchCreateParams.builder()
                        .addRequest(requestFactory.buildCachePrimerRequest(
                                CustomIdFactory.forCachePrimer(ordinal++), task.model(), task.data(),
                                task.model().getMaxTokens()))
                        .build();
                pending.put(prefix, batchClient.createPrimerBatch(params).id());
            } catch (RuntimeException e) {
                LOG.warn("[BATCH PRIMER] Primer for cache prefix {} could not be submitted - its "
                        + "bucket(s) will write the cache as before: {}", prefix, e.toString());
                outcomes.put(prefix, Outcome.FAILED);
            }
        }

        boolean interrupted = waitForEnd(start, pending, outcomes);
        for (String prefix : pending.keySet()) {
            outcomes.put(prefix, Outcome.TIMED_OUT);
            LOG.warn("[BATCH PRIMER] Primer for cache prefix {} had not ended after {}s{} - "
                    + "continuing without waiting further", prefix, properties.getWaitSeconds(),
                    interrupted ? " (interrupted)" : "");
        }
        long waitedMs = Duration.between(start, Instant.now(clock)).toMillis();
        LOG.info("[BATCH PRIMER] Primed {} cache prefix(es) in {} ms: {}", representatives.size(),
                waitedMs, outcomes);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Polls until every pending primer has ended, the cap is reached or the thread is interrupted.
     * Entries that end or fail are moved from {@code pending} into {@code outcomes}.
     *
     * @return {@code true} if interrupted
     */
    private boolean waitForEnd(Instant start, Map<String, String> pending,
            Map<String, Outcome> outcomes) {
        Instant deadline = start.plusSeconds(properties.getWaitSeconds());
        Duration pollInterval = Duration.ofSeconds(Math.max(1, properties.getPollSeconds()));
        while (!pending.isEmpty()) {
            for (Map.Entry<String, String> entry : new ArrayList<>(pending.entrySet())) {
                Outcome outcome = pollOnce(entry.getKey(), entry.getValue());
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

    /** Returns the primer's final outcome, or {@code null} while it is still running. */
    private Outcome pollOnce(String prefix, String batchId) {
        try {
            MessageBatch batch = batchClient.retrieveBatch(batchId);
            if (!MessageBatch.ProcessingStatus.ENDED.equals(batch.processingStatus())) {
                return null;
            }
            if (batch.requestCounts().succeeded() > 0) {
                return Outcome.ENDED;
            }
            LOG.warn("[BATCH PRIMER] Primer batch {} for cache prefix {} ended without a successful "
                    + "request (errored={}, canceled={}, expired={}) - the cache may not be warm",
                    batchId, prefix, batch.requestCounts().errored(), batch.requestCounts().canceled(),
                    batch.requestCounts().expired());
            return Outcome.FAILED;
        } catch (RuntimeException e) {
            LOG.warn("[BATCH PRIMER] Could not read primer batch {} for cache prefix {} - no longer "
                    + "waiting for it: {}", batchId, prefix, e.toString());
            return Outcome.FAILED;
        }
    }
}
