package com.gregochr.goldenhour.entity;

/**
 * Phases of a {@link CycleType#NIGHTLY} pipeline run.
 *
 * <p>The orchestrator advances through these in order. Each phase corresponds
 * to one {@link PipelineRunPhaseEntity} row. Gloss and best-bet are
 * intentionally <em>not</em> phases — they're synchronous in-process calls
 * inside {@code BriefingService.refreshBriefing()} and have no orchestration
 * concern.
 *
 * <p>Intraday will introduce its own phase set when built; do not extend
 * this enum with intraday phases pre-emptively.
 */
public enum PipelinePhase {
    /** Batches submitted to the Anthropic Batch API and tagged with the cycle id. */
    FORECAST_BATCH_SUBMIT,

    /** Polling the DB until every forecast_batch for this cycle reaches a terminal status. */
    FORECAST_BATCH_WAIT,

    /** Running BriefingService.refreshBriefing() (which calls gloss + best-bet inline). */
    BRIEFING
}
