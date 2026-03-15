package com.gregochr.goldenhour.model;

/**
 * Run-level phases of the three-phase forecast pipeline.
 *
 * <pre>
 * TRIAGE → SENTINEL_SAMPLING → FULL_EVALUATION → COMPLETE
 *                ↓                    ↓
 *           EARLY_STOP           EARLY_STOP
 * </pre>
 */
public enum RunPhase {

    /** Phase 1: fetch weather and apply heuristic triage to all tasks. */
    TRIAGE,

    /** Phase 2: evaluate geographic sentinel locations per region. */
    SENTINEL_SAMPLING,

    /** Phase 3: full Claude evaluation of remaining tasks. */
    FULL_EVALUATION,

    /** All phases completed normally. */
    COMPLETE,

    /** Run terminated early — all regions triaged or sentinel-skipped. */
    EARLY_STOP
}
