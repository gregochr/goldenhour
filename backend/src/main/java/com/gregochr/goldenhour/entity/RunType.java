package com.gregochr.goldenhour.entity;

/**
 * Identifies the type of forecast run — what was requested, not which model was used.
 *
 * <p>Replaces both {@code JobName} (which conflated run identity with model choice) and
 * {@code ModelConfigType} (which was named as if it were about models rather than time horizons).
 */
public enum RunType {

    /** Wildlife comfort data — no Claude evaluation. */
    WEATHER,

    /** Tide extremes refresh. */
    TIDE,

    /** Light pollution (Bortle class) enrichment. */
    LIGHT_POLLUTION,

    /** Daily briefing — zero-Claude-cost weather pre-flight check. */
    BRIEFING,

    /** Briefing best-bet advisor — Claude-generated photography recommendations. */
    BRIEFING_BEST_BET,

    /** Briefing region gloss — per-region Claude-generated one-line commentary. */
    BRIEFING_GLOSS,

    /** Aurora location evaluation — Claude-scored aurora photography conditions. */
    AURORA_EVALUATION,

    /** Aurora region gloss — per-region Claude-generated one-line commentary. */
    AURORA_GLOSS,

    /** Bluebell region gloss — per-region Claude-generated one-line commentary. */
    BLUEBELL_GLOSS,

    /** Scheduled Anthropic Batch API evaluation — covers both forecast and aurora batch jobs. */
    SCHEDULED_BATCH,

    /** Near-term batch evaluation (T+0, T+1) — high-confidence forecasts, always evaluated. */
    BATCH_NEAR_TERM,

    /** Far-term batch evaluation (T+2, T+3) — only evaluated when weather is SETTLED. */
    BATCH_FAR_TERM,

    /**
     * Ask PhotoCast typed questions and admin dry-runs — one run per UK civil day, so the day's
     * spend is one Operations row. Its model is the {@code photocast.ask.model} property, never a
     * {@code model_selection} row.
     */
    ASK,

    /** Ask PhotoCast Ready-answer precompute — one run per precompute, user-less. */
    ASK_READY;

    /** Furthest day ahead any run type forecasts (T+5). */
    public static final int FORECAST_HORIZON_DAYS = 5;
}
