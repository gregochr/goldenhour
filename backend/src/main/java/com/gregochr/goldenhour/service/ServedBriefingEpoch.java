package com.gregochr.goldenhour.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The serve epoch: a counter that moves whenever something the served briefing is derived from
 * changes, so {@link BriefingService#getCachedBriefingForApi()} knows its 30-second memo is stale.
 *
 * <p>The served briefing is not a function of the build alone: ratings are re-read live, hot topics
 * and aurora are live overlays, and the location and region rosters feed hot topics (see
 * {@code docs/engineering/performance-review-2026-10-plan.md} P1b). The memo's time limit bounds
 * staleness at 30 seconds; this epoch makes a known write visible at once.
 *
 * <p>{@link #bump()} is called by <b>writers</b> and has no dependencies of its own, so a writer can
 * hold it without creating a bean cycle with {@link BriefingService}. Inside an active transaction
 * the increment waits for the <em>commit</em>: a bump before the commit would let a concurrent
 * serve rebuild from the old rows and stamp the result with the new epoch, so the memo would then
 * outlive the write. A rolled-back transaction never bumps. With no transaction (in-memory setters,
 * non-transactional services) the bump is immediate.
 */
@Component
public class ServedBriefingEpoch {

    private final AtomicLong epoch = new AtomicLong();

    /**
     * The current epoch. {@link BriefingService} reads it <b>before</b> assembling a result and
     * stores that value with the result, so a result built across a write is never stamped with the
     * newer epoch.
     *
     * @return the current epoch
     */
    public long current() {
        return epoch.get();
    }

    /**
     * Marks the served briefing stale: after the surrounding transaction commits, or immediately
     * when there is none.
     */
    public void bump() {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    epoch.incrementAndGet();
                }
            });
        } else {
            epoch.incrementAndGet();
        }
    }
}
