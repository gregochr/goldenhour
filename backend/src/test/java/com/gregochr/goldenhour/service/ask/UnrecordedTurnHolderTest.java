package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UnrecordedTurnHolder} on its own seam, with a hand-written ledger: the holder's
 * bound, its order of writes, its latch and the one-critical-section rule between a flush and the spend
 * read. {@link AskUnrecordedCostTest} drives the same code end to end through {@link AskJobRunService}.
 */
class UnrecordedTurnHolderTest {

    private static final long TYPED_RUN = 5L;
    private static final long READY_RUN = 9L;

    private final FakeLedger ledger = new FakeLedger();
    private final UnrecordedTurnHolder holder = new UnrecordedTurnHolder(ledger);

    private static AskJobRunService.Turn turn(long runId, boolean ready) {
        return new AskJobRunService.Turn(runId, ready, EvaluationModel.HAIKU, 1, 200, true, null,
                new TokenUsage(1, 1, 0, 0));
    }

    private void hold(long runId, boolean ready, long cost) {
        holder.hold(turn(runId, ready), cost, new IllegalStateException("db down"));
    }

    @Test
    @DisplayName("an empty holder is available, sums nothing, and touches the ledger not at all")
    void emptyHolderIsAvailable() {
        assertThat(holder.available()).isTrue();
        assertThat(holder.typedSpend(() -> 7L)).isEqualTo(7L);
        assertThat(ledger.calls).isEmpty();
    }

    @Test
    @DisplayName("holding a turn latches; the typed cost is added to the persisted figure, a Ready one is not")
    void holdLatchesAndSumsTypedOnly() {
        hold(TYPED_RUN, false, 300);
        hold(READY_RUN, true, 999);

        assertThat(holder.typedSpend(() -> 1_000L)).isEqualTo(1_300L);
        ledger.failTurnWritesAfter = 0;
        assertThat(holder.available()).isFalse();
    }

    @Test
    @DisplayName("a flush writes held turns oldest first, settles each as it leaves, then unlatches")
    void flushWritesInOrderAndSettlesEach() {
        hold(TYPED_RUN, false, 100);
        hold(READY_RUN, true, 200);
        hold(TYPED_RUN, false, 300);

        assertThat(holder.available()).isTrue();

        assertThat(ledger.calls).containsExactly(
                "write:5", "settled:5:typed:100",
                "write:9", "settled:9:ready:200",
                "write:5", "settled:5:typed:300");
        assertThat(holder.typedSpend(() -> 0L)).as("nothing left unrecorded").isZero();
        assertThat(holder.available()).isTrue();
    }

    @Test
    @DisplayName("a failing write stops the flush there: the rest stay held, nothing after it settles, the "
            + "latch holds and the held cost keeps counting")
    void failureStopsTheFlush() {
        hold(TYPED_RUN, false, 100);
        hold(TYPED_RUN, false, 200);
        ledger.failTurnWritesAfter = 1;

        assertThat(holder.available()).isFalse();

        assertThat(ledger.calls).containsExactly("write:5", "settled:5:typed:100", "write:5-failed");
        assertThat(holder.typedSpend(() -> 0L)).isEqualTo(200L);
    }

    @Test
    @DisplayName("past the cap the cost folds into one total per run and kind: one summary write each, typed "
            + "cost still counted, Ready still not")
    void overflowFoldsIntoTotals() {
        for (int i = 0; i < UnrecordedTurnHolder.UNRECORDED_CAP; i++) {
            hold(TYPED_RUN, false, 10);
        }
        hold(TYPED_RUN, false, 40);
        hold(TYPED_RUN, false, 50);
        hold(READY_RUN, true, 70);

        long expectedTyped = UnrecordedTurnHolder.UNRECORDED_CAP * 10L + 90L;
        assertThat(holder.typedSpend(() -> 0L)).isEqualTo(expectedTyped);

        assertThat(holder.available()).isTrue();

        assertThat(ledger.overflows).containsExactly("5:typed:2:90", "9:ready:1:70");
        assertThat(ledger.settledCalls).contains("5:typed:90");
        assertThat(holder.typedSpend(() -> 0L)).isZero();
    }

    @Test
    @DisplayName("an overflow total that cannot be written keeps the latch and its cost (the detailed turns before it "
            + "are written and leave), and is retried")
    void overflowWriteFailureStaysLatched() {
        for (int i = 0; i <= UnrecordedTurnHolder.UNRECORDED_CAP; i++) {
            hold(TYPED_RUN, false, 10);
        }
        ledger.failOverflow.set(true);

        assertThat(holder.available()).isFalse();
        // The detailed turns were written and left; only the overflow total is still unrecorded.
        assertThat(holder.typedSpend(() -> 0L)).isEqualTo(10L);

        ledger.failOverflow.set(false);
        assertThat(holder.available()).isTrue();
    }

    /** Records every call in order; fails writes on demand. */
    private static final class FakeLedger implements UnrecordedTurnHolder.Ledger {
        private final List<String> calls = new ArrayList<>();
        private final List<String> overflows = new ArrayList<>();
        private final List<String> settledCalls = new ArrayList<>();
        private final AtomicBoolean failOverflow = new AtomicBoolean();
        private int failTurnWritesAfter = Integer.MAX_VALUE;

        @Override
        public void writeTurn(AskJobRunService.Turn turn) {
            if (failTurnWritesAfter-- <= 0) {
                calls.add("write:" + turn.runId() + "-failed");
                throw new IllegalStateException("db down again");
            }
            calls.add("write:" + turn.runId());
        }

        @Override
        public void writeOverflow(long runId, boolean typed, int turns, long cost) {
            if (failOverflow.get()) {
                throw new IllegalStateException("summary fails");
            }
            overflows.add(runId + ":" + (typed ? "typed" : "ready") + ":" + turns + ":" + cost);
        }

        @Override
        public void settled(long runId, boolean ready, long cost) {
            String entry = runId + ":" + (ready ? "ready" : "typed") + ":" + cost;
            settledCalls.add(entry);
            calls.add("settled:" + entry);
        }
    }
}
