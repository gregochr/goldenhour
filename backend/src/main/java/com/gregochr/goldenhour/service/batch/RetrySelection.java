package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;
import java.util.List;

/**
 * Outcome of selecting a cycle's retryable forecast failures for the
 * RETRY_FAILED phase.
 *
 * <p>The {@link Decision} encodes the cap-as-tripwire policy:
 * <ul>
 *   <li>{@link Decision#NONE} — no genuine failures in the cycle's precursor
 *       batches; the phase is a no-op (no retry batch submitted).</li>
 *   <li>{@link Decision#RETRY} — {@code 1..cap} failures; {@link #failures}
 *       lists exactly the requests to re-submit (one retry batch).</li>
 *   <li>{@link Decision#SYSTEMATIC} — more than {@code cap} failures; this is
 *       not transient noise but a systematic problem (prompt regression, model
 *       issue, bad input). NOT retried — re-submitting en masse would be both
 *       expensive and useless — and surfaced loudly for investigation.</li>
 * </ul>
 *
 * @param decision      what the phase should do
 * @param failures      the requests to retry (non-empty only for {@link Decision#RETRY})
 * @param failureCount  total distinct genuine failures found (drives the cap check
 *                      and the phase detail summary)
 * @param cap           the configured per-cycle retry cap in force at selection time
 */
public record RetrySelection(Decision decision, List<RetrySelection.RetryFailure> failures,
        int failureCount, int cap) {

    /** The phase action implied by the failure count relative to the cap. */
    public enum Decision {
        /** No genuine failures — nothing to retry. */
        NONE,
        /** Failures within the cap — retry them once. */
        RETRY,
        /** Failures exceed the cap — do not retry; record as systematic. */
        SYSTEMATIC
    }

    /**
     * A single genuinely-failed forecast request to reconstruct and re-submit.
     *
     * @param customId   the original batch custom id ({@code fc-{locationId}-{date}-{event}})
     * @param locationId location id parsed from the custom id
     * @param date       evaluation date parsed from the custom id
     * @param targetType SUNRISE / SUNSET / HOURLY parsed from the custom id
     */
    public record RetryFailure(String customId, Long locationId, LocalDate date,
            TargetType targetType) {
    }

    /**
     * A no-op selection (no failures).
     *
     * @param cap the configured cap (for the phase detail)
     * @return a {@link Decision#NONE} selection
     */
    public static RetrySelection none(int cap) {
        return new RetrySelection(Decision.NONE, List.of(), 0, cap);
    }

    /**
     * A retryable selection.
     *
     * @param failures the requests to retry
     * @param cap      the configured cap
     * @return a {@link Decision#RETRY} selection
     */
    public static RetrySelection retry(List<RetryFailure> failures, int cap) {
        return new RetrySelection(Decision.RETRY, List.copyOf(failures), failures.size(), cap);
    }

    /**
     * A systematic-failure selection (over cap — not retried).
     *
     * @param failureCount the total failure count that tripped the cap
     * @param cap          the configured cap
     * @return a {@link Decision#SYSTEMATIC} selection
     */
    public static RetrySelection systematic(int failureCount, int cap) {
        return new RetrySelection(Decision.SYSTEMATIC, List.of(), failureCount, cap);
    }
}
