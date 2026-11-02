package com.gregochr.goldenhour.model.runs;

import java.math.BigDecimal;
import java.util.List;

/**
 * The external calls of one run, aggregated per service.
 *
 * @param runId    the run asked about ({@code cycle-12}, {@code job-34} or {@code eval-5})
 * @param logged   false when calls are not logged for this kind of run (a sky-rating eval)
 * @param services one entry per service and call kind, ordered by label; empty when none was logged
 */
public record RunCallsResponse(String runId, boolean logged, List<CallService> services) {

    /** How a service's calls went. */
    public enum CallStatus {
        /** No call errored. */
        OK,
        /** Some calls errored. */
        ERRORS,
        /** Every call errored. */
        FAILED
    }

    /**
     * One service's calls.
     *
     * @param service          the logged service name
     * @param label            the service's display label
     * @param purpose          what the calls were for, chosen by the server
     * @param calls            how many calls (for an Anthropic batch, how many requests)
     * @param durationMs       summed call time; for Anthropic batches, the batches' wall time; null when unknown
     * @param batches          Anthropic batch rows only: how many batches
     * @param requests         Anthropic batch rows only: how many requests
     * @param costMicroDollars summed cost in micro-dollars
     * @param costGbp          that cost in pounds, to the penny
     * @param costUsd          that cost in dollars, to the cent
     * @param errors           how many calls errored
     * @param status           OK, ERRORS or FAILED
     * @param errorSummary     the commonest error message (at most 120 characters), or null
     */
    public record CallService(
            String service,
            String label,
            String purpose,
            long calls,
            Long durationMs,
            Integer batches,
            Long requests,
            long costMicroDollars,
            BigDecimal costGbp,
            BigDecimal costUsd,
            long errors,
            CallStatus status,
            String errorSummary) {
    }
}
