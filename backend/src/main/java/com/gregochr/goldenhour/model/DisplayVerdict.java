package com.gregochr.goldenhour.model;

/**
 * Unified display verdict that drives card colour and label across the Plan tab's
 * matrix cards, its day × event grid, and the grid's drill-down verdict pill.
 *
 * <p>Claude's 1-5 star rating is the primary signal. When no rating is available, the triage
 * {@link Verdict} can only speak against a slot, never for it: a weather STANDDOWN is a decision
 * against the slot ({@link #STAND_DOWN}), but a weather GO or MARGINAL is a cheap pre-screen that
 * did not rule it out, not a call that it is good, so it reads {@link #UNRATED}. {@link #AWAITING}
 * means neither signal is present yet.
 *
 * <p>⚠️ <b>The old "sensible colour" rationale is retired</b> (2026-10-13,
 * {@code docs/engineering/unrated-verdict-plan.md} U1). This enum used to map a triage GO to
 * {@link #WORTH_IT} and MARGINAL to {@link #MAYBE} so early-pipeline cells still showed a colour;
 * since the verdict sample gate ({@code VerdictSampleGate}, 2026-09-29) that fallback is the COMMON
 * case at T+2 and T+3, and a weather pre-screen was wearing the same word as a Claude 4-star average
 * — a region with 38 ratings that failed the gate read "Worth it" on weather alone.
 *
 * <p>This enum exists alongside {@link Verdict} — Verdict still drives the triage
 * gradient bar and internal gating decisions, while DisplayVerdict is purely a
 * presentation signal.
 */
public enum DisplayVerdict {

    /** Claude rated 4-5 stars (average at least 3.5 for a region). */
    WORTH_IT,

    /** Claude rated 3 stars (average at least 2.5 for a region). */
    MAYBE,

    /** Claude rated 1-2 stars, or triage STANDDOWN when no rating yet. */
    STAND_DOWN,

    /**
     * No trustworthy rating has called it: the weather screen (triage GO or MARGINAL) did not rule
     * the slot out, but nothing rated it, or a region's rated sample failed the verdict sample gate.
     * Never a pick candidate and never worded as a quality claim; serialised by name.
     */
    UNRATED,

    /** Neither Claude rating nor triage verdict is available. */
    AWAITING;

    /**
     * Resolves the display verdict for a single location.
     *
     * <p>Claude rating takes precedence — even when Claude contradicts the triage
     * verdict (e.g. triage STANDDOWN but Claude rated 5). This reflects that the
     * scored evaluation is strictly more informed than the triage fallback. With no rating,
     * triage GO and MARGINAL both resolve to {@link #UNRATED} (a pre-screen is not a verdict),
     * STANDDOWN to {@link #STAND_DOWN} and no triage at all to {@link #AWAITING}.
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
            case GO, MARGINAL -> UNRATED;
            case STANDDOWN -> STAND_DOWN;
        };
    }
}
