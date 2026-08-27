package com.gregochr.goldenhour.service.batch;

/**
 * Thrown when a batch was accepted by Anthropic but its local tracking row could not be persisted.
 *
 * <p>This is the one submission outcome that must not be reported as a normal failure.
 * {@code BatchSubmissionService.submit} returns {@code null} for "nothing was submitted", and every
 * caller reads it that way — {@code EvaluationServiceImpl} maps it to an empty handle, from which
 * the orchestrator concludes it is running a zero-batch cycle, treats that as terminal, and
 * refreshes the briefing from stale cache while paid work is still in flight.
 *
 * <p>The batch itself is real, billable and running; only the record of it is missing. Recovery is
 * to insert a {@code forecast_batch} row for {@link #getAnthropicBatchId()}, after which the
 * ordinary poller will pick it up. The id is carried on the exception, and logged at ERROR at the
 * throw site, precisely so that recovery does not depend on reading a stack trace.
 */
public class OrphanedBatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The Anthropic batch id that exists remotely but has no local tracking row. */
    private final String anthropicBatchId;

    /**
     * Constructs the exception.
     *
     * @param anthropicBatchId the batch id returned by Anthropic before persistence failed
     * @param cause            the persistence failure that stranded it
     */
    public OrphanedBatchException(String anthropicBatchId, Throwable cause) {
        super("Batch " + anthropicBatchId + " was submitted to Anthropic but its tracking row "
                + "could not be persisted; polling cannot discover it", cause);
        this.anthropicBatchId = anthropicBatchId;
    }

    /**
     * Returns the stranded batch id, for recovery.
     *
     * @return the Anthropic batch id
     */
    public String getAnthropicBatchId() {
        return anthropicBatchId;
    }
}
