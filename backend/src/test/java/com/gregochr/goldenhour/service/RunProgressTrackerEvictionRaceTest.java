package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gregochr.goldenhour.model.RunProgress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The eviction re-check in {@link RunProgressTracker#cleanupStaleEntries()}: a completed run is found as a
 * candidate without locks, so a run re-registered under the same id before the evict takes its locks must survive.
 * The interleaving is forced by holding the old run's stream lock, which the evict needs, while the cleanup thread
 * is parked on it.
 */
class RunProgressTrackerEvictionRaceTest {

    private static final long RUN = 7L;
    private static final long TIMEOUT_SECONDS = 10;
    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");

    private MutableTestClock clock;
    private RunProgressTracker tracker;

    @BeforeEach
    void setUp() {
        clock = new MutableTestClock(T0);
        tracker = new RunProgressTracker(mock(DynamicSchedulerService.class),
                mock(java.util.concurrent.ScheduledExecutorService.class), 5_000L, clock);
    }

    private static List<String[]> oneTask() {
        return List.<String[]>of(new String[]{"Loc1|2026-10-03|SUNSET", "Loc1", "2026-10-03", "SUNSET"});
    }

    @Test
    @DisplayName("a run re-registered under the same id after the cleanup picked the old one is not evicted")
    void cleanup_runReplacedBetweenLookupAndEvict_newerRunSurvives() throws Exception {
        tracker.initRun(RUN, oneTask());
        RunProgress old = tracker.getProgress(RUN);
        tracker.completeRun(RUN);
        clock.advance(Duration.ofHours(2));

        CompletableFuture<Thread> cleanupThread = new CompletableFuture<>();
        CompletableFuture<Void> cleanup;
        RunProgress replacement;
        synchronized (old.streamLock()) {
            cleanup = CompletableFuture.runAsync(() -> {
                cleanupThread.complete(Thread.currentThread());
                tracker.cleanupStaleEntries();
            });
            Thread thread = cleanupThread.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // The cleanup has chosen the old run as a candidate and is parked on its stream lock.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (thread.getState() != Thread.State.BLOCKED) {
                assertThat(System.nanoTime()).as("cleanup never reached the evict lock").isLessThan(deadline);
                Thread.onSpinWait();
            }
            tracker.initRun(RUN, oneTask());
            replacement = tracker.getProgress(RUN);
        }

        awaitDone(cleanup);
        assertThat(replacement).isNotSameAs(old);
        assertThat(tracker.getProgress(RUN)).isSameAs(replacement);
        assertThat(tracker.getProgress(RUN).getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("without a replacement the same interleaving does evict the completed run")
    void cleanup_runNotReplaced_isEvicted() throws Exception {
        tracker.initRun(RUN, oneTask());
        RunProgress old = tracker.getProgress(RUN);
        tracker.completeRun(RUN);
        clock.advance(Duration.ofHours(2));
        CompletableFuture<Void> cleanup;
        synchronized (old.streamLock()) {
            cleanup = CompletableFuture.runAsync(tracker::cleanupStaleEntries);
        }

        awaitDone(cleanup);
        assertThat(tracker.getProgress(RUN)).isNull();
    }

    private static void awaitDone(CompletableFuture<Void> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
