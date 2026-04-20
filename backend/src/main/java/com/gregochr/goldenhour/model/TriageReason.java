package com.gregochr.goldenhour.model;

/**
 * Categorised user-facing reason a colour forecast was triaged out before Claude evaluation.
 *
 * <p>Each value maps 1:1 to a {@link TriageRule} and carries a formatting template for a
 * short explanation that references concrete numbers from the weather snapshot. Stored on
 * {@code forecast_evaluation.triage_reason} and surfaced via the REST API / map popover.
 *
 * <p>Separate from the briefing-side {@code StanddownReason} enum, which categorises
 * region-level aggregate verdicts. The two systems operate on different inputs.
 */
public enum TriageReason {

    /** Heavy cloud at the solar horizon — sun completely blocked. Maps from {@link TriageRule#HIGH_CLOUD}. */
    HIGH_CLOUD,

    /** Active precipitation forecast at event time. Maps from {@link TriageRule#PRECIPITATION}. */
    PRECIPITATION,

    /** Visibility too low (fog, heavy haze). Maps from {@link TriageRule#LOW_VISIBILITY}. */
    LOW_VISIBILITY,

    /** Preferred tide type (high/low/mid) doesn't fall in the golden/blue hour window.
     *  Maps from {@link TriageRule#TIDE_MISALIGNED}. */
    TIDE_MISALIGNED,

    /** Catch-all — regional sentinel skip, legacy sentinel rows without a category, etc. */
    GENERIC;

    /**
     * Returns the categorised reason corresponding to a triage rule.
     *
     * @param rule the internal triage rule, or {@code null}
     * @return the matching reason, or {@link #GENERIC} if {@code rule} is null or unmapped
     */
    public static TriageReason fromRule(TriageRule rule) {
        if (rule == null) {
            return GENERIC;
        }
        return switch (rule) {
            case HIGH_CLOUD -> HIGH_CLOUD;
            case PRECIPITATION -> PRECIPITATION;
            case LOW_VISIBILITY -> LOW_VISIBILITY;
            case TIDE_MISALIGNED -> TIDE_MISALIGNED;
        };
    }
}
