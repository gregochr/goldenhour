package com.gregochr.goldenhour.service.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Executes a list of work items in parallel with a concurrency cap,
 * per-item error isolation, and structured completion logging.
 *
 * <p>Used by {@link BriefingGlossService} and {@link AuroraGlossService}
 * to fan out Claude API calls without duplicating the execution plumbing.
 *
 * <p>Each work item is processed independently. A failure in one item
 * does not affect others — the consumer is responsible for catching
 * exceptions and recording the failure on the work item itself.
 *
 * @param <W> the work item type
 */
public final class ParallelGlossExecutor<W> {

    private static final Logger LOG = LoggerFactory.getLogger(ParallelGlossExecutor.class);

    private final int maxConcurrency;
    private final String label;

    /**
     * Creates a new executor.
     *
     * @param maxConcurrency maximum concurrent tasks
     * @param label          human-readable label for log messages (e.g. "Gloss", "Aurora gloss")
     */
    public ParallelGlossExecutor(int maxConcurrency, String label) {
        this.maxConcurrency = maxConcurrency;
        this.label = label;
    }

    /**
     * Result of a parallel execution run.
     *
     * @param succeeded number of items processed without exception
     * @param failed    number of items where the consumer threw
     * @param durationMs total wall-clock time in milliseconds
     */
    public record ExecutionResult(int succeeded, int failed, long durationMs) {
    }

    /**
     * Executes the given work items in parallel, calling {@code processor}
     * for each item. The processor MUST handle its own exceptions — if it
     * throws, the item is counted as failed but other items continue.
     *
     * @param items     work items to process
     * @param processor per-item processing function (called on a virtual thread)
     * @return execution result with counts and timing
     */
    public ExecutionResult execute(List<W> items, Consumer<W> processor) {
        if (items.isEmpty()) {
            LOG.debug("{}: no items to process", label);
            return new ExecutionResult(0, 0, 0);
        }

        long startMs = System.currentTimeMillis();
        Semaphore semaphore = new Semaphore(maxConcurrency);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        List<CompletableFuture<Void>> futures = items.stream()
                .map(item -> CompletableFuture.runAsync(() -> {
                    try {
                        semaphore.acquire();
                        try {
                            processor.accept(item);
                            succeeded.incrementAndGet();
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failed.incrementAndGet();
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    }
                }))
                .toList();

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        long totalMs = System.currentTimeMillis() - startMs;
        LOG.info("{} complete: {}/{} succeeded, {} failed ({}ms)",
                label, succeeded.get(), items.size(), failed.get(), totalMs);

        return new ExecutionResult(succeeded.get(), failed.get(), totalMs);
    }
}
