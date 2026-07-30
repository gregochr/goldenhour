package com.gregochr.goldenhour.entity;

/**
 * Discriminates orchestrated pipeline cycles by their domain shape.
 *
 * <p>{@link #NIGHTLY} is the nightly forecast batch → briefing sequence built
 * out in V102. {@link #INTRADAY} is the intraday refresh (decision-window
 * events, stability-as-cost-gate) — it shares the same orchestrator and the
 * same {@code pipeline_run} table, with its own phase set defined in code.
 *
 * <p>Both cycles are live. INTRADAY is wired at
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
    INTRADAY
}
