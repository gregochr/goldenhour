package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.BackfillBatch;
import com.gregochr.goldenhour.model.BackfillStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CloudVerificationBackfillRunner}.
 *
 * <p>{@code run()} is invoked directly here, so it executes synchronously — {@code @Async} only
 * applies through the Spring proxy. That is deliberate: it makes the loop, the stop conditions and
 * the status transitions assertable without timing.
 */
@ExtendWith(MockitoExtension.class)
class CloudVerificationBackfillRunnerTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-26T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private CloudVerificationService verificationService;

    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    private CloudVerificationBackfillRunner runner;

    private static BackfillBatch batch(int n) {
        return new BackfillBatch(n, n);
    }

    @BeforeEach
    void setUp() {
        runner = new CloudVerificationBackfillRunner(
                verificationService, dynamicSchedulerService, CLOCK);
    }

    @Test
    @DisplayName("run works through batches until one comes back empty")
    void run_drainsBacklogThenStops() {
        when(verificationService.backfill(CloudVerificationBackfillRunner.BATCH_SIZE))
                .thenReturn(batch(100), batch(100), batch(40), BackfillBatch.EMPTY);
        when(verificationService.countRemaining()).thenReturn(140L, 40L, 0L);

        runner.run();

        verify(verificationService, times(4))
                .backfill(CloudVerificationBackfillRunner.BATCH_SIZE);
        BackfillStatus status = runner.status();
        assertThat(status.running()).isFalse();
        assertThat(status.verified()).isEqualTo(240);
        assertThat(status.batches()).isEqualTo(3);
        assertThat(status.lastError()).isNull();
        assertThat(status.finishedAt()).isNotNull();
    }

    @Test
    @DisplayName("run stops at the first failing batch rather than grinding through the rest")
    void run_stopsOnFailure() {
        // The usual cause is the archive rate-limiting us. Continuing would write thousands of
        // rows with no observations, which the anti-join would then never revisit.
        when(verificationService.backfill(anyInt()))
                .thenReturn(batch(100))
                .thenThrow(new RuntimeException("429 Too Many Requests"));

        runner.run();

        verify(verificationService, times(2)).backfill(anyInt());
        BackfillStatus status = runner.status();
        assertThat(status.running()).isFalse();
        assertThat(status.verified()).isEqualTo(100);
        assertThat(status.lastError()).contains("429");
    }

    @Test
    @DisplayName("start refuses a second concurrent run")
    void start_refusesConcurrentRun() {
        // A second run would select overlapping candidates and collide on the unique
        // forecast_evaluation_id, turning a duplicate request into a batch of failures.
        when(verificationService.backfill(anyInt())).thenAnswer(invocation -> {
            // While the first run is mid-flight, a second start must be refused.
            assertThat(runner.start()).isFalse();
            return BackfillBatch.EMPTY;
        });

        assertThat(runner.start()).isTrue();
        verify(verificationService, times(1)).backfill(anyInt());
    }

    @Test
    @DisplayName("start is allowed again once the previous run has finished")
    void start_allowedAfterCompletion() {
        when(verificationService.backfill(anyInt())).thenReturn(BackfillBatch.EMPTY);

        assertThat(runner.start()).isTrue();
        assertThat(runner.status().running()).isFalse();
        assertThat(runner.start()).isTrue();

        verify(verificationService, times(2)).backfill(anyInt());
    }

    @Test
    @DisplayName("a failing remaining-count degrades to unknown instead of aborting the run")
    void run_remainingCountFailure_doesNotAbort() {
        when(verificationService.backfill(anyInt())).thenReturn(batch(100), BackfillBatch.EMPTY);
        when(verificationService.countRemaining())
                .thenThrow(new RuntimeException("db busy"));

        runner.run();

        BackfillStatus status = runner.status();
        assertThat(status.verified()).isEqualTo(100);
        assertThat(status.remaining()).isNull();
        assertThat(status.lastError()).isNull();
    }

    @Test
    @DisplayName("run stops when a batch writes rows but gets no observations back")
    void run_blankBatch_stopsAndRecordsError() {
        // The silent-failure case this whole change exists for: Open-Meteo answers a rate limit
        // with HTTP 200 and an error envelope, so rows get written carrying nothing. Continuing
        // would blank the rest of the backlog, and the anti-join would never revisit any of it.
        when(verificationService.backfill(anyInt()))
                .thenReturn(batch(100))
                .thenReturn(new BackfillBatch(100, 0))
                .thenReturn(batch(100));

        runner.run();

        // Stopped at the blank batch — the third stub is never reached.
        verify(verificationService, times(2)).backfill(anyInt());
        BackfillStatus status = runner.status();
        assertThat(status.running()).isFalse();
        assertThat(status.lastError()).contains("no observations");
    }

    @Test
    @DisplayName("start clears blank rows first so a throttled run heals itself")
    void start_clearsBlankRowsBeforeRunning() {
        when(verificationService.backfill(anyInt())).thenReturn(BackfillBatch.EMPTY);

        runner.start();

        verify(verificationService).clearBlankVerifications();
    }

    @Test
    @DisplayName("a failing blank-clear does not prevent the run from starting")
    void start_clearFailure_stillRuns() {
        when(verificationService.clearBlankVerifications())
                .thenThrow(new RuntimeException("db busy"));
        when(verificationService.backfill(anyInt())).thenReturn(BackfillBatch.EMPTY);

        assertThat(runner.start()).isTrue();
        verify(verificationService).backfill(anyInt());
    }

    @Test
    @DisplayName("a scheduled tick takes a bounded slice, leaving the rest for the next hour")
    void runScheduled_stopsAtTheBatchCeiling() {
        // The whole point of the schedule: ~25k evaluations at four sample points each is far more
        // than an hour's archive allowance, so a tick must yield rather than drain.
        when(verificationService.backfill(anyInt())).thenReturn(batch(100));

        runner.runScheduled();

        verify(verificationService, times(CloudVerificationBackfillRunner.SCHEDULED_BATCHES_PER_RUN))
                .backfill(anyInt());
        assertThat(runner.status().running()).isFalse();
        assertThat(runner.status().verified())
                .isEqualTo(100 * CloudVerificationBackfillRunner.SCHEDULED_BATCHES_PER_RUN);
    }

    @Test
    @DisplayName("a scheduled tick clears blanks first, so a throttled tick heals on the next one")
    void runScheduled_clearsBlanksFirst() {
        when(verificationService.backfill(anyInt())).thenReturn(BackfillBatch.EMPTY);

        runner.runScheduled();

        verify(verificationService).clearBlankVerifications();
    }

    @Test
    @DisplayName("a scheduled tick is a no-op once the backlog is clear")
    void runScheduled_emptyBacklog_stopsImmediately() {
        when(verificationService.backfill(anyInt())).thenReturn(BackfillBatch.EMPTY);

        runner.runScheduled();

        // One probe, not twelve — leaving the job enabled costs nothing.
        verify(verificationService, times(1)).backfill(anyInt());
    }

    @Test
    @DisplayName("a scheduled tick defers to a manual run already in progress")
    void runScheduled_whileRunning_skips() {
        when(verificationService.backfill(anyInt())).thenAnswer(invocation -> {
            runner.runScheduled();
            return BackfillBatch.EMPTY;
        });

        assertThat(runner.start()).isTrue();
        // The nested tick was refused, so only the manual run's batch executed.
        verify(verificationService, times(1)).backfill(anyInt());
    }

    @Test
    @DisplayName("registerJob wires the tick to the scheduler under its job key")
    void registerJob_registersTarget() {
        runner.registerJob();

        verify(dynamicSchedulerService)
                .registerJobTarget(eq("cloud_verification_backfill"), any());
    }

    @Test
    @DisplayName("status starts idle before any run")
    void status_idleBeforeFirstRun() {
        BackfillStatus status = runner.status();

        assertThat(status.running()).isFalse();
        assertThat(status.verified()).isZero();
        assertThat(status.startedAt()).isNull();
        verify(verificationService, never()).backfill(anyInt());
    }
}
