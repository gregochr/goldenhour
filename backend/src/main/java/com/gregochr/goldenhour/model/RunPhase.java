package com.gregochr.goldenhour.model;

/**
 * Run-level phases a hand-started run reports to the live progress panel.
 *
 * <pre>
 * TRIAGE → FULL_EVALUATION → COMPLETE
 * </pre>
 */
public enum RunPhase {

    /** Phase 1: fetch weather and apply heuristic triage to all tasks. */
    TRIAGE,

    /** Phase 2: the run's main work (for Light Pollution, the Bortle enrichment itself). */
    FULL_EVALUATION,

    /** All phases completed normally. */
    COMPLETE
}
