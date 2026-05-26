package com.gregochr.goldenhour.entity;

/**
 * Discriminates orchestrated pipeline cycles by their domain shape.
 *
 * <p>{@link #NIGHTLY} is the nightly forecast batch → briefing sequence built
 * out in V102. {@link #INTRADAY} is reserved for the planned intraday refresh
 * (decision-window events, stability-as-cost-gate) — it shares the same
 * orchestrator and the same {@code pipeline_run} table, with its own phase
 * set defined in code. No INTRADAY phases are wired today.
 */
public enum CycleType {
    /** Nightly forecast batch → briefing cycle. */
    NIGHTLY,

    /** Reserved for the intraday refresh — do not use yet. */
    INTRADAY
}
