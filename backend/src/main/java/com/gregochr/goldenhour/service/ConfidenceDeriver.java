package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.Confidence;

/**
 * Derives a display {@link Confidence} for a briefing region from the forecast
 * horizon and the spread/coverage of the region's Claude ratings.
 *
 * <p>This is the region/cell counterpart to the Claude-self-reported confidence
 * carried by a Best Bet pick. It exists so the Plan screen can render one uniform
 * confidence channel: a far-horizon "Worth it" should read visibly more provisional
 * than a same-day one, because weather certainty decays fast past T+1.
 *
 * <p>Rules (horizon dominant; spread/coverage can only downgrade):
 * <ul>
 *   <li>base by horizon — {@code daysAhead <= 1} → HIGH; {@code 2..3} → MEDIUM;
 *       {@code >= 4} → LOW;</li>
 *   <li>downgrade one band (floored at LOW) when the region's ratings disagree
 *       sharply ({@code ratingRange >= 2}) or coverage is thin (fewer than half
 *       the roster scored);</li>
 *   <li>return {@code null} — genuinely unknown — when no location is scored, so
 *       a missing signal reads as provisional on the frontend rather than
 *       falsely confident (unlike {@link Confidence#fromString} which defaults to
 *       MEDIUM).</li>
 * </ul>
 */
public final class ConfidenceDeriver {

    /** Highest {@code daysAhead} that still reads HIGH confidence (T+0, T+1). */
    static final int HIGH_HORIZON_MAX_DAYS = 1;

    /** Highest {@code daysAhead} that still reads MEDIUM confidence (T+2, T+3). */
    static final int MEDIUM_HORIZON_MAX_DAYS = 3;

    /** A rating range at or above this (e.g. a 2 next to a 4) downgrades one band. */
    static final int WIDE_SPREAD_RANGE = 2;

    /** Coverage below this fraction of the roster downgrades one band. */
    static final double THIN_COVERAGE_RATIO = 0.5;

    /**
     * Voting roster at or below which a region's verdict is <b>unconditionally</b> degenerate.
     *
     * <p>{@code BriefingVerdictEvaluator.rollUpVerdict} fires GO when {@code goCount} reaches 20%
     * of the <i>viable</i> (non-STANDDOWN) slots. Since {@code 1 * 100 >= viable * 20} holds for
     * every viable count up to 5, a single GO location carries the whole region — there is no
     * split of a five-location region in which one good spot does not read "Worth it".
     *
     * <p><b>Note the mismatch, because it bounds what this guard is worth.</b> The degenerate
     * quantity is {@code viable}, which is per-day, per-event and collapses on bad weather. The
     * roster is a static upper bound on it. So this catches a region that is <i>structurally</i>
     * too small — every day, whatever the weather — and cannot catch a large region whose
     * locations have nearly all stood down, which produces the same wrong "Worth it" off one
     * location. Closing that gap means deriving confidence from the runtime verdict counts, or
     * putting a minimum-n term in {@code rollUpVerdict} itself; neither is done here.
     */
    static final int DEGENERATE_VOTING_ROSTER = 5;

    /**
     * Voting roster below which a region reaches {@link #DEGENERATE_VOTING_ROSTER} on fewer than
     * half its locations standing down.
     *
     * <p>Falling into the degenerate band takes {@code voting - 5} stand-downs, which is less than
     * half the roster exactly when {@code voting < 10}. Above this a region has to be mostly
     * written off before one location can speak for it; below it, an ordinary mixed morning does.
     */
    static final int FRAGILE_VOTING_ROSTER = 10;

    /**
     * The two roster sizes a region carries. They are deliberately different numbers, and the
     * distinction is the whole reason this is a record rather than two int parameters that could
     * be transposed without a compiler complaint.
     *
     * @param scoreable locations that could carry a Claude rating — the coverage DENOMINATOR only.
     *                  Excludes a canopy slot while it holds no rating, since a wood is out of the
     *                  sky batch and counting it unconditionally would report a permanent,
     *                  unfixable shortfall. It no longer matches the numerator: since the canopy
     *                  fix the caller passes stats over the region's VOTING slots, so a rated wood
     *                  sits in this denominator and not in the count above it. That makes the ratio
     *                  slightly pessimistic for a wood-bearing region — it can only downgrade, the
     *                  safe direction — and is preferred to measuring coverage of a population the
     *                  verdict does not use
     * @param voting    locations that vote on the region verdict — non-canopy slots, or all slots
     *                  for an all-canopy region, mirroring
     *                  {@code BriefingHierarchyBuilder.buildRegion}. A non-positive value disables
     *                  the small-region rules.
     */
    public record RegionRoster(int scoreable, int voting) {
    }

    private ConfidenceDeriver() {
    }

    /**
     * Derives the confidence for a region.
     *
     * @param daysAhead forecast horizon in days from "today" (may be negative for
     *                  a past date, treated as HIGH by the {@code <= 1} rule)
     * @param stats     the region's rating stats; {@code null} or empty → unknown
     * @param roster    the region's two roster sizes; {@code null} disables both the
     *                  thin-coverage and the small-region rules
     * @return the derived confidence, or {@code null} when the signal is unknown
     *         (no scored locations)
     */
    public static Confidence derive(int daysAhead, BriefingRatingStats.Stats stats,
            RegionRoster roster) {
        if (stats == null || stats.isEmpty()) {
            return null;
        }
        int scoreable = roster == null ? 0 : roster.scoreable();
        int voting = roster == null ? 0 : roster.voting();

        // Floor, not a downgrade. Below this the verdict carries no information a confidence band
        // could qualify — one GO location produces "Worth it" for every possible split of the
        // region — so no horizon, however near, may present it as settled.
        if (voting > 0 && voting <= DEGENERATE_VOTING_ROSTER) {
            return Confidence.LOW;
        }

        Confidence base = fromHorizon(daysAhead);
        boolean wideSpread = stats.ratingRange() >= WIDE_SPREAD_RANGE;
        boolean thinCoverage = scoreable > 0
                && stats.count() < THIN_COVERAGE_RATIO * scoreable;
        // A small region cannot express disagreement — ratingRange is a min-max statistic, so it
        // saturates with n and fires almost always on a 70-location region and almost never on a
        // 7-location one. Without this term the quiet channel reads roster size, backwards.
        boolean fragileRoster = voting > 0 && voting < FRAGILE_VOTING_ROSTER;
        if (wideSpread || thinCoverage || fragileRoster) {
            base = downgrade(base);
        }
        return base;
    }

    /**
     * The horizon-only confidence base (never null). This is the whole story for a single
     * evaluation, which has no region spread to consider — it is persisted per row on
     * {@code forecast_evaluation.confidence} as a durable "how far ahead was this forecast"
     * attribute, and is the base term of the region-level {@link #derive} above.
     *
     * @param daysAhead forecast horizon in days
     * @return HIGH for T+0/1, MEDIUM for T+2/3, LOW for T+4+
     */
    public static Confidence fromHorizon(int daysAhead) {
        if (daysAhead <= HIGH_HORIZON_MAX_DAYS) {
            return Confidence.HIGH;
        }
        if (daysAhead <= MEDIUM_HORIZON_MAX_DAYS) {
            return Confidence.MEDIUM;
        }
        return Confidence.LOW;
    }

    private static Confidence downgrade(Confidence c) {
        return switch (c) {
            case HIGH -> Confidence.MEDIUM;
            case MEDIUM, LOW -> Confidence.LOW;
        };
    }
}
