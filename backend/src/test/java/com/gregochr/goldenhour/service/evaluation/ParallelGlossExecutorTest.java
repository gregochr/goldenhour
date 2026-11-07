package com.gregochr.goldenhour.service.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ParallelGlossExecutor}.
 */
class ParallelGlossExecutorTest {

    @Test
    @DisplayName("All items succeed — counts reflect full success")
    void allSucceed() {
        var executor = new ParallelGlossExecutor<String>(5, "Test");
        List<String> items = List.of("a", "b", "c");
        AtomicInteger processed = new AtomicInteger();

        var result = executor.execute(items, item -> processed.incrementAndGet());

        assertThat(result.succeeded()).isEqualTo(3);
        assertThat(result.failed()).isEqualTo(0);
        assertThat(processed.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("One item throws — failure isolated, others succeed")
    void oneFailure_othersSucceed() {
        var executor = new ParallelGlossExecutor<String>(5, "Test");
        List<String> items = List.of("ok", "fail", "ok2");
        List<String> processed = Collections.synchronizedList(new ArrayList<>());

        var result = executor.execute(items, item -> {
            if ("fail".equals(item)) {
                throw new RuntimeException("boom");
            }
            processed.add(item);
        });

        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(processed).containsExactlyInAnyOrder("ok", "ok2");
    }

    @Test
    @DisplayName("Empty list — no processing, zero counts, zero duration")
    void emptyList_noop() {
        var executor = new ParallelGlossExecutor<String>(5, "Test");

        var result = executor.execute(List.of(), item -> {
            throw new AssertionError("should not be called");
        });

        assertThat(result.succeeded()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(0);
    }

    @Test
    @DisplayName("Concurrency cap respected — no more than maxConcurrency run at once")
    void concurrencyCapRespected() {
        var executor = new ParallelGlossExecutor<Integer>(2, "Test");
        AtomicInteger concurrentCount = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        List<Integer> items = List.of(1, 2, 3, 4, 5);

        executor.execute(items, item -> {
            int current = concurrentCount.incrementAndGet();
            maxConcurrent.updateAndGet(max -> Math.max(max, current));
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrentCount.decrementAndGet();
        });

        assertThat(maxConcurrent.get()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("All items fail — succeeded is 0, failed matches total")
    void allFail() {
        var executor = new ParallelGlossExecutor<String>(5, "Test");
        List<String> items = List.of("a", "b", "c");

        var result = executor.execute(items, item -> {
            throw new RuntimeException("all fail");
        });

        assertThat(result.succeeded()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(3);
    }

    @Test
    @DisplayName("A worker interrupted while waiting for a slot counts as failed and never runs its item")
    void interruptedWhileWaitingForSlot_countsAsFailed() throws Exception {
        var executor = new ParallelGlossExecutor<String>(1, "Test");
        CountDownLatch firstRunning = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> processed = Collections.synchronizedList(new ArrayList<>());

        CompletableFuture<ParallelGlossExecutor.ExecutionResult> run = new CompletableFuture<>();
        Thread.startVirtualThread(() -> run.complete(executor.execute(List.of("a", "b"), item -> {
            processed.add(item);
            firstRunning.countDown();
            try {
                releaseFirst.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })));

        assertThat(firstRunning.await(10, TimeUnit.SECONDS)).isTrue();
        // The second item's worker is parked on the single permit; interrupt exactly that thread.
        Thread waiter = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (waiter == null && System.currentTimeMillis() < deadline) {
            for (var entry : Thread.getAllStackTraces().entrySet()) {
                StackTraceElement[] frames = entry.getValue();
                boolean parkedOnPermit = java.util.Arrays.stream(frames)
                        .anyMatch(f -> f.getClassName().equals("java.util.concurrent.Semaphore")
                                && f.getMethodName().startsWith("acquire"))
                        // Only this executor's own worker: a leftover thread from another test
                        // parked on some other semaphore must never match.
                        && java.util.Arrays.stream(frames)
                        .anyMatch(f -> f.getClassName().equals(ParallelGlossExecutor.class.getName()));
                if (parkedOnPermit) {
                    waiter = entry.getKey();
                }
            }
            if (waiter == null) {
                Thread.sleep(10);
            }
        }
        assertThat(waiter).as("a worker parked waiting for the permit").isNotNull();
        waiter.interrupt();
        releaseFirst.countDown();

        var result = run.get(10, TimeUnit.SECONDS);

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(processed).as("only the item that held the permit was processed").hasSize(1);
    }
}
