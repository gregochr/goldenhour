package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pins the order in which a subscriber receives a task's states when the subscriber's replay is still
 * in progress while a worker publishes a newer state for the same task.
 *
 * <p>The defect (rows left on "Pending" or "Weather" in a run that had finished): {@code subscribe}
 * registered the emitter and then replayed from a copy of the tasks frozen at that moment, while
 * {@code onTaskEvent} took no lock, so a worker could send the live FAILED for a task while the replay
 * was still holding that task's older copy, which was then sent AFTER it. The panel applies the last
 * event it receives. The test is deterministic: the emitter blocks on a latch inside its FIRST send
 * (the replay's), a second thread then publishes FAILED for every task, and the replay is released
 * only once that thread has either finished (the old code) or is blocked behind the replay (the fixed
 * code). No sleeps, no timing assumption.
 */
@ExtendWith(MockitoExtension.class)
class RunProgressTrackerReplayOrderTest {

    private static final long RUN = 7L;
    private static final List<String> KEYS = List.of("A|2026-10-03|SUNSET", "B|2026-10-03|SUNSET",
            "C|2026-10-03|SUNSET");

    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    /** Blocks inside its first send, before recording it; records everything else as it is sent. */
    private static final class GatedEmitter extends SseEmitter {
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean gateTaken = new AtomicBoolean();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        GatedEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (gateTaken.compareAndSet(false, true)) {
                entered.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IOException("gate never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            StringBuilder text = new StringBuilder();
            builder.build().forEach(part -> text.append(part.getData()));
            events.add(text.toString());
        }

        /** The state of the last task-update this emitter received for the task, or null if none. */
        String lastStateOf(String taskKey) {
            String last = null;
            synchronized (events) {
                for (String e : events) {
                    if (e.contains("\"taskKey\":\"" + taskKey + "\"")) {
                        last = e.replaceAll("(?s).*\"state\":\"([A-Z_]+)\".*", "$1");
                    }
                }
            }
            return last;
        }
    }

    @Test
    @DisplayName("a worker's FAILED published while a subscriber's replay is mid-send is never overtaken by "
            + "the replay's older copy of that task")
    void replayInProgress_liveFailedIsTheLastStateEveryTaskIsSentAs() throws Exception {
        GatedEmitter emitter = new GatedEmitter();
        RunProgressTracker tracker = new RunProgressTracker(dynamicSchedulerService,
                mock(ScheduledExecutorService.class), 5_000L) {
            @Override
            SseEmitter newEmitter() {
                return emitter;
            }
        };
        List<String[]> tasks = new ArrayList<>();
        for (String key : KEYS) {
            String[] parts = key.split("\\|");
            tasks.add(new String[]{key, parts[0], parts[1], parts[2]});
        }
        tracker.initRun(RUN, tasks);

        Thread subscriber = new Thread(() -> tracker.subscribe(RUN), "subscriber");
        subscriber.start();
        assertThat(emitter.entered.await(30, TimeUnit.SECONDS)).as("replay reached its first send").isTrue();

        Thread publisher = new Thread(() -> {
            for (String[] t : tasks) {
                tracker.onTaskEvent(new LocationTaskEvent(this, RUN, t[0], t[1], t[2], t[3],
                        LocationTaskState.FAILED, "weather down", "FETCHING_WEATHER"));
            }
        }, "publisher");
        publisher.start();
        // Either the publisher got all its events out around the stalled replay (the defect), or it is
        // now blocked behind the replay (the fix). Both are reached without waiting on the clock.
        while (publisher.isAlive() && publisher.getState() != Thread.State.BLOCKED) {
            Thread.onSpinWait();
        }
        emitter.release.countDown();
        subscriber.join(30_000);
        publisher.join(30_000);

        for (String key : KEYS) {
            assertThat(emitter.lastStateOf(key)).as("last state sent for %s", key).isEqualTo("FAILED");
        }
    }
}
