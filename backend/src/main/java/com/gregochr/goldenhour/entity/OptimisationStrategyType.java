package com.gregochr.goldenhour.entity;

/**
 * Types of cost optimisation strategy that can be toggled per {@link RunType}.
 *
 * <p>Each strategy controls whether a forecast slot is skipped or force-evaluated.
 * See {@code OptimisationSkipEvaluator} for evaluation order semantics.
 */
public enum OptimisationStrategyType {

    /** Skip if no prior evaluation exists or prior rating is below the configured threshold. */
    SKIP_LOW_RATED,

    /** Skip if a forecast already exists for this location/date/target. */
    SKIP_EXISTING,

    /** Override other skips for today's remaining events (imminent sunrise/sunset). */
    FORCE_IMMINENT,

    /** Override other skips if the latest evaluation was generated before today. */
    FORCE_STALE,

    /** Always evaluate every slot — no skips, no optimisation (JFDI mode). */
    EVALUATE_ALL,

    /** Submit evaluations via Anthropic Batch API (50% savings, async). Phase 2. */
    BATCH_API
}
