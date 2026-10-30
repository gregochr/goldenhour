package com.gregochr.goldenhour.entity;

/**
 * Discriminates orchestrated pipeline cycles by their domain shape.
 *
 * <p>{@link #NIGHTLY} is the nightly forecast batch → briefing sequence built
 * out in V102. {@link #INTRADAY} is the intraday refresh (decision-window
 * events, stability-as-cost-gate) — it shares the same orchestrator and the
 * same {@code pipeline_run} table, with its own phase set defined in code.
 *
 * <p>{@link #EVENING} is a third, dormant-until-resumed cycle
 * ({@code evening_sunrise_refresh}, seeded PAUSED) that re-evaluates tomorrow's sunrise alone.
 *
 * <p>The first two cycles are live. INTRADAY is wired at
 * {@code PipelineOrchestrator.runIntradayCycle()}, registered against the
 * {@code intraday_forecast_refresh} job key, and seeded ACTIVE by V105; it
 * records a {@code STABILITY_RECLASSIFY} phase ahead of the shared
 * submit/wait/briefing sequence. This javadoc previously said the constant was
 * reserved and that no INTRADAY phases were wired — both were left over from
 * before V105 and were false for as long as the intraday cycle has been running.
 */
public enum CycleType {
    /** Nightly forecast batch → briefing cycle. */
    NIGHTLY,

    /** Intraday refresh cycle — decision-window events on the skip-settled cost gate. */
    INTRADAY,

    /**
     * Evening refresh — tomorrow's sunrise alone, at 21:00 UK time, so the bedtime decision rests on a
     * fresh look. Gives no failure evidence ({@code LocationFailureService} claims and ignores it).
     */
    EVENING;

    /** What a cycle's results tell {@code LocationFailureService} about a place. */
    public enum FailureEvidence {
        /** The cycle's failures and successes count towards a place's consecutive failures. */
        COUNTED,
        /** The cycle takes no part in failure counting. */
        NONE
    }

    /**
     * Whether the cycle publishes the stability snapshot (non-ephemeral submit).
     *
     * @return {@code true} for the nightly cycle only
     */
    public boolean publishesStabilitySnapshot() {
        return switch (this) {
            case NIGHTLY -> true;
            case INTRADAY, EVENING -> false;
        };
    }

    /**
     * Whether the cycle records a STABILITY_RECLASSIFY phase ahead of the submit phase.
     *
     * @return {@code true} for the intraday and evening cycles
     */
    public boolean recordsReclassifyPhase() {
        return switch (this) {
            case NIGHTLY -> false;
            case INTRADAY, EVENING -> true;
        };
    }

    /**
     * Whether the cycle's outcomes count as evidence for per-place failure counting.
     *
     * @return the evidence this cycle type gives
     */
    public FailureEvidence failureEvidence() {
        return switch (this) {
            case NIGHTLY, INTRADAY -> FailureEvidence.COUNTED;
            case EVENING -> FailureEvidence.NONE;
        };
    }
}
