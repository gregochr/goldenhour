package com.gregochr.goldenhour.entity;

/**
 * Identifies the type of forecast run — what was requested, not which model was used.
 *
 * <p>Replaces both {@code JobName} (which conflated run identity with model choice) and
 * {@code ModelConfigType} (which was named as if it were about models rather than time horizons).
 */
public enum RunType {

    /** Very short-term: today and tomorrow (T, T+1). */
    VERY_SHORT_TERM,

    /** Short-term: today through T+2. */
    SHORT_TERM,

    /** Long-term: T+3 through the forecast horizon. */
    LONG_TERM,

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
    BATCH_FAR_TERM;

    /** Furthest day ahead any run type forecasts (T+5). */
    public static final int FORECAST_HORIZON_DAYS = 5;

    /**
     * The target dates this run type covers, relative to the given day.
     *
     * <p>The single source of truth for the run-type date table. It previously existed twice —
     * in {@code ForecastCommandFactory} (production) and {@code PromptTestService} (the admin
     * prompt-test harness) — and the two had drifted: the harness swept LONG_TERM out to T+7,
     * past {@link #FORECAST_HORIZON_DAYS} and past Gate 4's "T+4 and beyond is never evaluated"
     * policy, so it prompt-tested horizons production never scores. This table is production's.
     *
     * @param today the day to compute the range from (UTC)
     * @return the ordered target dates
     */
    public java.util.List<java.time.LocalDate> defaultDateRange(java.time.LocalDate today) {
        return switch (this) {
            case VERY_SHORT_TERM -> java.util.List.of(today, today.plusDays(1));
            case SHORT_TERM -> java.util.List.of(today, today.plusDays(1), today.plusDays(2));
            case LONG_TERM -> today.plusDays(3)
                    .datesUntil(today.plusDays(FORECAST_HORIZON_DAYS + 1))
                    .toList();
            case WEATHER, TIDE, LIGHT_POLLUTION, BRIEFING,
                    BRIEFING_BEST_BET, BRIEFING_GLOSS,
                    AURORA_EVALUATION, AURORA_GLOSS, BLUEBELL_GLOSS,
                    SCHEDULED_BATCH, BATCH_NEAR_TERM, BATCH_FAR_TERM ->
                    java.util.stream.IntStream.rangeClosed(0, FORECAST_HORIZON_DAYS)
                            .mapToObj(today::plusDays)
                            .toList();
        };
    }
}
