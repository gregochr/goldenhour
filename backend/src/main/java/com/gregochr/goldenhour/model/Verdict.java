package com.gregochr.goldenhour.model;

/**
 * Quick pre-flight verdict for a briefing slot or region rollup.
 *
 * <p>The rain and visibility thresholds match
 * {@link com.gregochr.goldenhour.service.WeatherTriageEvaluator}; the cloud thresholds do not
 * (triage's near-term cut is 60% on the solar horizon, this verdict's is 70% horizon / 80%
 * observer cloud): STANDDOWN when conditions are clearly unsuitable, MARGINAL when borderline,
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
