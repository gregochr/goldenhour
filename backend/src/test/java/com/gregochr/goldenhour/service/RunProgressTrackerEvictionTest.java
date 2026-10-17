package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Eviction rules of {@link RunProgressTracker#cleanupStaleEntries()}, driven by a clock the test
 * moves: a run still going is never evicted on its age; a completed run is evicted a fixed time after
 * it COMPLETED (not after it started); a run that has not completed is never evicted, however silent.
 */
@ExtendWith(MockitoExtension.class)
class RunProgressTrackerEvictionTest {

    private static final long RUN = 7L;
    private static final String KEY = "Loc1|2026-10-03|SUNSET";
    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");

    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    private MutableTestClock clock;
    private ScheduledExecutorService graceScheduler;
    private RunProgressTracker tracker;
    private final List<RecordingEmitter> emitters = new ArrayList<>();

    /** Records every event as {@code name|payload}, and whether the stream was ended. */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> events = new ArrayList<>();
        private boolean completed;

        RecordingEmitter() {
            super(0L);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            StringBuilder text = new StringBuilder();
            builder.build().forEach(part -> text.append(part.getData()));
            events.add(text.toString());
            super.send(builder);
        }

        @Override
        public synchronized void complete() {
            completed = true;
            super.complete();
        }

        List<String> sent() {
            return events.stream()
                    .map(e -> e.replace("event:", "").replace("\ndata:", "|").replace("\n\n", ""))
                    .toList();
        }

        List<String> names() {
            return sent().stream().map(e -> e.substring(0, e.indexOf('|'))).toList();
        }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableTestClock(T0);
        graceScheduler = mock(ScheduledExecutorService.class);
        tracker = new RunProgressTracker(dynamicSchedulerService, graceScheduler, 5_000L, clock) {
            @Override
            SseEmitter newEmitter() {
                RecordingEmitter emitter = new RecordingEmitter();
                emitters.add(emitter);
                return emitter;
            }
        };
        tracker.initRun(RUN, List.<String[]>of(new String[]{KEY, "Loc1", "2026-10-03", "SUNSET"}));
    }

    private void taskEvent(LocationTaskState state) {
        tracker.onTaskEvent(new LocationTaskEvent(this, RUN, KEY, "Loc1", "2026-10-03", "SUNSET", state,
                state == LocationTaskState.FAILED ? "weather down" : null, null));
    }

    @Test
    @DisplayName("a run older than the old 30-minute TTL that is still receiving events survives cleanup, "
            + "and its completion is still delivered to a subscriber")
    void longRunStillReceivingEvents_survivesAndCompletes() {
        RecordingEmitter live = (RecordingEmitter) tracker.subscribe(RUN);

        clock.advance(Duration.ofMinutes(31));
        taskEvent(LocationTaskState.EVALUATING);
        tracker.cleanupStaleEntries();
        clock.advance(Duration.ofMinutes(31));
        taskEvent(LocationTaskState.COMPLETE); // 62 minutes after the run started
        tracker.cleanupStaleEntries();

        assertThat(tracker.getProgress(RUN)).isNotNull();
        tracker.completeRun(RUN);

        assertThat(live.names()).contains("run-complete").doesNotContain("run-expired");
        assertThat(live.sent().stream().filter(e -> e.startsWith("run-complete|")).findFirst().orElseThrow())
                .contains("\"status\":\"COMPLETE\"").contains("\"jobRunId\":7");
        assertThat(live.completed).isTrue();
        assertThat(tracker.isComplete(RUN)).isTrue();
    }

    @Test
    @DisplayName("a completed run is evicted 30 minutes after it COMPLETED, not before, however long it ran")
    void completedRun_evictedThirtyMinutesAfterCompletion() {
        clock.advance(Duration.ofMinutes(50));
        taskEvent(LocationTaskState.FAILED);
        tracker.completeRun(RUN); // the run took 50 minutes, so it is already past 30 minutes from its start

        tracker.cleanupStaleEntries();
        assertThat(tracker.getProgress(RUN)).isNotNull();

        clock.advance(Duration.ofMinutes(29).plusSeconds(59));
        tracker.cleanupStaleEntries();
        assertThat(tracker.getProgress(RUN)).isNotNull();
        assertThat(tracker.isComplete(RUN)).isTrue();

        clock.advance(Duration.ofSeconds(1));
        tracker.cleanupStaleEntries();
        assertThat(tracker.getProgress(RUN)).isNull();
        assertThat(tracker.isComplete(RUN)).isFalse();
    }

    @Test
    @DisplayName("an evicted completed run's retained completion goes with it, so a later subscriber is "
            + "replayed nothing and left to the unknown-run expiry")
    void evictedCompletedRun_isNotReplayed() {
        taskEvent(LocationTaskState.FAILED);
        tracker.completeRun(RUN);
        clock.advance(Duration.ofMinutes(30));

        tracker.cleanupStaleEntries();
        RecordingEmitter late = (RecordingEmitter) tracker.subscribe(RUN);

        assertThat(late.sent()).isEmpty();
        assertThat(late.completed).isFalse();
    }

    @Test
    @DisplayName("an unfinished run that has been silent for 10 hours (the prefetch can be) is never evicted, "
            + "and its completion is still delivered to the subscriber attached throughout")
    void silentUnfinishedRun_survivesAnyAgeAndCompletes() {
        RecordingEmitter live = (RecordingEmitter) tracker.subscribe(RUN);

        for (int hour = 1; hour <= 10; hour++) {
            clock.advance(Duration.ofHours(1)); // no task event and no phase change at all
            tracker.cleanupStaleEntries();
        }

        assertThat(tracker.getProgress(RUN)).isNotNull();
        assertThat(live.names()).doesNotContain("run-expired");
        assertThat(live.completed).isFalse();
        taskEvent(LocationTaskState.COMPLETE); // an event after the silence is applied, not discarded
        assertThat(tracker.getProgress(RUN).getCompleted()).isEqualTo(1);
        tracker.completeRun(RUN);

        assertThat(live.names()).contains("run-complete").doesNotContain("run-expired");
        assertThat(live.sent().stream().filter(e -> e.startsWith("run-complete|")).findFirst().orElseThrow())
                .contains("\"status\":\"COMPLETE\"").contains("\"jobRunId\":7");
        assertThat(live.completed).isTrue();
        assertThat(tracker.isComplete(RUN)).isTrue();
    }

    @Test
    @DisplayName("an unfinished run is not evicted whether or not anything is subscribed, so a retry of it "
            + "stays 'still going' rather than falling to nothing-to-retry")
    void silentUnfinishedRunWithNoSubscribers_survives() {
        clock.advance(Duration.ofHours(10));

        tracker.cleanupStaleEntries();

        assertThat(tracker.getProgress(RUN)).isNotNull();
        assertThat(tracker.isComplete(RUN)).isFalse();
        assertThat(emitters).isEmpty();
    }

    @Test
    @DisplayName("evicting a completed run leaves another run that is still unfinished")
    void eviction_isPerRun() {
        tracker.initRun(8L, List.<String[]>of(new String[]{"Loc2|2026-10-03|SUNSET", "Loc2", "2026-10-03",
                "SUNSET"}));
        taskEvent(LocationTaskState.COMPLETE);
        tracker.completeRun(RUN);
        clock.advance(Duration.ofMinutes(30));

        tracker.cleanupStaleEntries();

        assertThat(tracker.getProgress(RUN)).isNull();
        assertThat(tracker.getProgress(8L)).isNotNull();
    }

    @Test
    @DisplayName("an aborted run (failRun) is evicted 30 minutes after it FAILED, not before")
    void failedRun_evictedThirtyMinutesAfterFailure() {
        clock.advance(Duration.ofMinutes(5));
        tracker.failRun(RUN, "The run stopped unexpectedly. See the server log.");

        clock.advance(Duration.ofMinutes(29).plusSeconds(59));
        tracker.cleanupStaleEntries();
        assertThat(tracker.getProgress(RUN)).isNotNull();
        assertThat(tracker.isComplete(RUN)).isTrue();

        clock.advance(Duration.ofSeconds(1));
        tracker.cleanupStaleEntries();
        assertThat(tracker.getProgress(RUN)).isNull();
    }

    @Test
    @DisplayName("a subscriber arriving AFTER a completed run was evicted is, once the grace period "
            + "passes, sent run-expired and its stream ended")
    void subscriberAfterEviction_isToldRunExpired() {
        taskEvent(LocationTaskState.FAILED);
        tracker.completeRun(RUN);
        clock.advance(Duration.ofMinutes(30));
        tracker.cleanupStaleEntries();

        RecordingEmitter late = (RecordingEmitter) tracker.subscribe(RUN);

        assertThat(late.sent()).isEmpty();
        ArgumentCaptor<Runnable> expiry = ArgumentCaptor.forClass(Runnable.class);
        verify(graceScheduler).schedule(expiry.capture(), eq(5_000L), eq(TimeUnit.MILLISECONDS));
        expiry.getValue().run();
        assertThat(late.sent()).containsExactly("run-expired|{\"jobRunId\":7}");
        assertThat(late.completed).isTrue();
    }
}
