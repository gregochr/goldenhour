package com.gregochr.goldenhour.entity;

/**
 * Phases of a pipeline run. Which phases a run records — and in what order —
 * depends on its {@link CycleType}:
 *
 * <ul>
 *   <li>{@link CycleType#NIGHTLY}: FORECAST_BATCH_SUBMIT → FORECAST_BATCH_WAIT
 *       → BRIEFING. Stability classification happens inside the submit phase
 *       (and is persisted to the authoritative snapshot), so it is not a
 *       distinct phase.</li>
 *   <li>{@link CycleType#INTRADAY}: STABILITY_RECLASSIFY → FORECAST_BATCH_SUBMIT
 *       → FORECAST_BATCH_WAIT → BRIEFING. The reclassify step (an
 *       <em>ephemeral</em> re-classification that does <b>not</b> overwrite the
 *       morning's snapshot) is surfaced as its own phase precisely because the
 *       cost-gate decision lives there: without it, "why did intraday evaluate
 *       few or many locations today?" is invisible.</li>
 * </ul>
 *
 * <p>The orchestrator advances through the cycle's phases in order. Each phase
 * corresponds to one {@link PipelineRunPhaseEntity} row. Gloss and best-bet are
 * intentionally <em>not</em> phases — they're synchronous in-process calls
 * inside {@code BriefingService.refreshBriefing()} and have no orchestration
 * concern.
 */
public enum PipelinePhase {
    /**
     * Intraday-only. Ephemeral re-classification of the decision-window grid
     * cells with fresh weather — the cost-gate that decides which locations are
     * re-evaluated (TRANSITIONAL/UNSETTLED) versus skipped as settled
     * ({@code SKIPPED_NO_REFRESH_NEEDED}). Does not persist a stability snapshot.
     */
    STABILITY_RECLASSIFY,

    /** Batches submitted to the Anthropic Batch API and tagged with the cycle id. */
    FORECAST_BATCH_SUBMIT,

    /** Polling the DB until every forecast_batch for this cycle reaches a terminal status. */
    FORECAST_BATCH_WAIT,

    /**
     * Conditional phase (both cycle types). After WAIT completes, re-submits the
     * cycle's genuinely-failed forecast requests (parse failures / API errors) as
     * a single capped retry batch, so a transient model hiccup does not leave a
     * location unevaluated for the cycle. Runs only when there are retryable
     * failures within the cap; a clean cycle records no RETRY_FAILED phase, and a
     * cycle whose failures exceed the cap records the phase as a no-retry
     * systematic-failure marker. Placed before BRIEFING so recovered locations are
     * in the data the briefing synthesises. Deliberate skips (SKIPPED_*) are never
     * retried.
     */
    RETRY_FAILED,

    /** Running BriefingService.refreshBriefing() (which calls gloss + best-bet inline). */
    BRIEFING
}
