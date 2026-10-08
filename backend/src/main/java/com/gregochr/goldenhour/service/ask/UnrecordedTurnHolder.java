package com.gregochr.goldenhour.service.ask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * The bounded in-memory holder of Ask model turns whose {@code api_call_log} row could not be
 * written, and the accounting latch that goes with it (plan §2.3, "the accounting latch fails
 * closed"). Split out of {@link AskJobRunService}, which keeps the daily run, the row write and the
 * spend sum; this class owns the one lock that guards the holder, the latch <em>and</em> the spend
 * memo.
 *
 * <p><b>One critical section.</b> {@link #typedSpend} runs the caller's persisted-sum read and adds
 * the unrecorded typed cost under {@link #lock}; {@link #flushLocked()} removes a flushed entry and
 * tells the {@link Ledger} to drop the memo under the same lock. So a reader sees a held turn or its
 * persisted row, never both and never neither. The class is deliberately free of Spring, JPA and
 * repositories: the three things it needs from the outside are the {@link Ledger}'s.
 *
 * <p>The latch is set by the first failed write and cleared only by a flush that wrote every held
 * turn and every overflow total. While it is set {@link #available()} first tries the flush and only
 * answers true if it succeeded. At most {@value #UNRECORDED_CAP} turns are kept in detail; a further
 * turn is folded into one per-run total (cost is never dropped, only per-turn detail), written as one
 * summary row on recovery.
 */
final class UnrecordedTurnHolder {

    private static final Logger LOG = LoggerFactory.getLogger(UnrecordedTurnHolder.class);

    /** The most unrecorded turns kept in detail; later ones are folded into a per-run total. */
    static final int UNRECORDED_CAP = 100;

    /** What the holder needs from the outside: the writes it retries, and the settle callback. */
    interface Ledger {

        /**
         * Writes a held turn's {@code api_call_log} row.
         *
         * @param turn the turn
         * @throws RuntimeException when the database cannot take it
         */
        void writeTurn(AskJobRunService.Turn turn);

        /**
         * Writes the one summary row of an overflowed per-run total.
         *
         * @param runId the job run
         * @param typed true when the total is typed spend, false when Ready
         * @param turns how many turns' detail was lost
         * @param cost  their summed cost in micro-dollars
         * @throws RuntimeException when the database cannot take it
         */
        void writeOverflow(long runId, boolean typed, int turns, long cost);

        /**
         * A held cost reached the database and left the holder: bring the display total up and drop
         * the stale spend memo. Called with the holder's lock held.
         *
         * @param runId the job run
         * @param ready true for a Ready turn, whose spend is not typed spend
         * @param cost  the cost in micro-dollars
         */
        void settled(long runId, boolean ready, long cost);
    }

    /** A turn whose row could not be written, with the cost it is priced at. */
    private record Pending(AskJobRunService.Turn turn, long cost) {
    }

    /** The folded cost of turns that did not fit the holder's detail cap. */
    private static final class Overflow {
        private final boolean typed;
        private int turns;
        private long cost;

        Overflow(boolean typed) {
            this.typed = typed;
        }
    }

    private final Ledger ledger;

    /** Guards the holder, every flush and (through {@link #typedSpend}) the caller's spend memo. */
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Pending> pending = new ArrayDeque<>();
    private final Map<Long, Overflow> overflow = new LinkedHashMap<>();
    private volatile boolean latched;

    /**
     * Creates an empty holder.
     *
     * @param ledger the writes it retries and the settle callback
     */
    UnrecordedTurnHolder(Ledger ledger) {
        this.ledger = ledger;
    }

    /**
     * Keeps a turn whose row could not be written and sets the latch.
     *
     * @param turn  the turn
     * @param cost  the cost it is priced at, in micro-dollars
     * @param cause why the write failed
     */
    void hold(AskJobRunService.Turn turn, long cost, RuntimeException cause) {
        lock.lock();
        try {
            boolean first = !latched;
            if (pending.size() < UNRECORDED_CAP) {
                pending.addLast(new Pending(turn, cost));
            } else {
                Overflow bucket = overflow.computeIfAbsent(turn.runId(), id -> new Overflow(!turn.ready()));
                bucket.turns++;
                bucket.cost += cost;
            }
            latched = true;
            if (first) {
                LOG.error("[ASK] Could not write an api_call_log row for a paid model turn ({} micro-dollars): "
                        + "{}. Holding it in memory and refusing further Ask model calls until it is "
                        + "written. A restart now would lose it.", cost, cause.toString());
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Whether the engine may start another model turn: true when nothing is unrecorded, or when every
     * held turn has just been written. Otherwise false and the engine must make no call, for any
     * conversation. Takes the lock only while latched.
     *
     * @return true when every paid call is on record
     */
    boolean available() {
        if (!latched) {
            return true;
        }
        lock.lock();
        try {
            return !latched || flushLocked();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs a read of the persisted typed spend and adds the cost of every typed turn still unrecorded,
     * in one critical section with {@link #hold} and every flush. The read may keep state (the caller's
     * memo) that {@link Ledger#settled} invalidates: both run under this holder's lock.
     *
     * @param persisted the persisted typed spend, read while the lock is held
     * @return the persisted figure plus the unrecorded typed cost, in micro-dollars
     */
    long typedSpend(LongSupplier persisted) {
        lock.lock();
        try {
            return persisted.getAsLong() + unrecordedTypedCostLocked();
        } finally {
            lock.unlock();
        }
    }

    /** Writes the held turns, oldest first, then the overflow totals; stops at the first failure. */
    private boolean flushLocked() {
        Iterator<Pending> held = pending.iterator();
        while (held.hasNext()) {
            Pending next = held.next();
            try {
                ledger.writeTurn(next.turn());
            } catch (RuntimeException e) {
                LOG.debug("[ASK] Still cannot write held turns: {}", e.toString());
                return false;
            }
            held.remove();
            ledger.settled(next.turn().runId(), next.turn().ready(), next.cost());
        }
        Iterator<Map.Entry<Long, Overflow>> totals = overflow.entrySet().iterator();
        while (totals.hasNext()) {
            Map.Entry<Long, Overflow> entry = totals.next();
            Overflow bucket = entry.getValue();
            try {
                ledger.writeOverflow(entry.getKey(), bucket.typed, bucket.turns, bucket.cost);
            } catch (RuntimeException e) {
                LOG.debug("[ASK] Still cannot write the overflow total: {}", e.toString());
                return false;
            }
            totals.remove();
            ledger.settled(entry.getKey(), !bucket.typed, bucket.cost);
        }
        latched = false;
        LOG.info("[ASK] Every held model turn is now on record; Ask model calls resume.");
        return true;
    }

    private long unrecordedTypedCostLocked() {
        long sum = 0;
        for (Pending p : pending) {
            if (!p.turn().ready()) {
                sum += p.cost();
            }
        }
        for (Overflow bucket : overflow.values()) {
            if (bucket.typed) {
                sum += bucket.cost;
            }
        }
        return sum;
    }
}
