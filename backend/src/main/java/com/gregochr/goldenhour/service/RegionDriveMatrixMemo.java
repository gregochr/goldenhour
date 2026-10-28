package com.gregochr.goldenhour.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Memo for the shared region drive-time matrix served by {@code GET /api/regions/drive-times}.
 *
 * <p>Holds the last matrix as an <strong>unmodifiable</strong> map in an {@link AtomicReference}, so
 * the endpoint stops running {@code findAll()} on every request. A separate component rather than a
 * field on {@link RegionDriveDurationService} because the writer that changes the rows
 * ({@link RegionDriveTimeWriter}) must invalidate it, and the service already depends on the
 * writer.
 *
 * <p><b>Invalidated after commit, never inside the transaction.</b> Cleared inside it, a concurrent
 * reader could re-fill the memo from the still-uncommitted old rows and keep serving them after the
 * commit. A rolled-back write therefore does not clear it (the rows did not change). A generation
 * counter closes the other race: a reader that started loading before an invalidation never stores
 * what it loaded.
 */
@Component
public class RegionDriveMatrixMemo {

    private final AtomicReference<Map<Long, Map<Long, Integer>>> memo = new AtomicReference<>();
    private long generation;

    /**
     * Returns the memoised matrix, loading it with {@code loader} when none is held.
     *
     * @param loader builds the matrix from the database; its result is stored unmodifiable
     * @return the unmodifiable matrix: region id to (location id to whole minutes)
     */
    public Map<Long, Map<Long, Integer>> getOrLoad(Supplier<Map<Long, Map<Long, Integer>>> loader) {
        Map<Long, Map<Long, Integer>> held = memo.get();
        if (held != null) {
            return held;
        }
        long startedAt = currentGeneration();
        Map<Long, Map<Long, Integer>> loaded = loader.get();
        storeIfCurrent(startedAt, loaded);
        return loaded;
    }

    /**
     * Clears the memo once the surrounding transaction commits, or immediately when none is active.
     * A rollback leaves it untouched.
     */
    public void invalidateAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate();
                }
            });
        } else {
            invalidate();
        }
    }

    private synchronized long currentGeneration() {
        return generation;
    }

    private synchronized void storeIfCurrent(long startedAt, Map<Long, Map<Long, Integer>> loaded) {
        if (generation == startedAt) {
            memo.set(loaded);
        }
    }

    private synchronized void invalidate() {
        generation++;
        memo.set(null);
    }
}
