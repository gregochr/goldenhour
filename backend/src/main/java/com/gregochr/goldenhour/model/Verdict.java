package com.gregochr.goldenhour.model;

/**
 * Quick pre-flight verdict for a briefing slot or region rollup.
 *
 * <p>Aligned with {@link com.gregochr.goldenhour.service.WeatherTriageEvaluator} thresholds:
 * STANDDOWN when conditions are clearly unsuitable, MARGINAL when borderline,
 * GO when nothing is flagged.
 */
public enum Verdict {

    /** Conditions look favourable — no weather blockers triggered. */
    GO,

    /** Borderline conditions — one or more marginal flags. */
    MARGINAL,

    /** Conditions clearly unsuitable — cloud, rain, or visibility blocker. */
    STANDDOWN
}
