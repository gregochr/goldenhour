package com.gregochr.goldenhour.model;

/**
 * Unified display verdict that drives card colour and label across the Plan grid,
 * mobile region cards, map markers, and quality slider.
 *
 * <p>Claude's 1-5 star rating is the primary signal. When no rating is available,
 * the triage {@link Verdict} is used as a fallback so early-pipeline cells still
 * show a sensible colour. {@link #AWAITING} means neither signal is present yet.
 *
 * <p>This enum exists alongside {@link Verdict} — Verdict still drives the triage
 * gradient bar and internal gating decisions, while DisplayVerdict is purely a
 * presentation signal.
 */
public enum DisplayVerdict {

    /** Claude rated 4-5 stars, or triage GO when no rating yet. */
    WORTH_IT,

    /** Claude rated 3 stars, or triage MARGINAL when no rating yet. */
    MAYBE,

    /** Claude rated 1-2 stars, or triage STANDDOWN when no rating yet. */
    STAND_DOWN,

    /** Neither Claude rating nor triage verdict is available. */
    AWAITING;

    /**
     * Resolves the display verdict for a single location.
     *
     * <p>Claude rating takes precedence — even when Claude contradicts the triage
     * verdict (e.g. triage STANDDOWN but Claude rated 5). This reflects that the
     * scored evaluation is strictly more informed than the triage fallback.
     *
     * @param claudeRating   the 1-5 star rating from Claude, or null if not scored
     * @param triageVerdict  the triage verdict, or null if no triage result
     * @return the resolved display verdict (never null)
     */
    public static DisplayVerdict resolve(Integer claudeRating, Verdict triageVerdict) {
        if (claudeRating != null) {
            if (claudeRating >= 4) {
                return WORTH_IT;
            }
            if (claudeRating == 3) {
                return MAYBE;
            }
            return STAND_DOWN;
        }
        if (triageVerdict == null) {
            return AWAITING;
        }
        return switch (triageVerdict) {
            case GO -> WORTH_IT;
            case MARGINAL -> MAYBE;
            case STANDDOWN -> STAND_DOWN;
        };
    }
}
