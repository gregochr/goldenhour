package com.gregochr.goldenhour.entity;

/**
 * Batch submission lifecycle for a {@code forecast_evaluation} row inserted at submit time for
 * the {@code fc-} (sky lane) batch path.
 *
 * <p>Explicit column state rather than an inference from null patterns — an abandoned slot
 * (batch expired, retry unreconstructable) must stay distinguishable from an in-flight one
 * forever, and "rating null AND triage null" cannot say which. Null on the entity means the row
 * predates this state (every sync-engine and triage write, and every row inserted before the
 * batch_state column existed) — never treat null as a fourth state.
 */
public enum BatchState {

    /** Inserted at batch submit time; not yet scored or closed out. */
    PENDING,

    /** The batch result landed and scored this row in place. */
    SCORED,

    /** Closed out without a score — batch expired/failed, or superseded by a retry (R6). */
    ABANDONED
}
