package com.gregochr.goldenhour.service.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
}
