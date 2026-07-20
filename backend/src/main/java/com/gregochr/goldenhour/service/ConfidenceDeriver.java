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

    private ConfidenceDeriver() {
    }

    /**
     * Derives the confidence for a region.
     *
     * @param daysAhead  forecast horizon in days from "today" (may be negative for
     *                   a past date, treated as HIGH by the {@code <= 1} rule)
     * @param stats      the region's rating stats; {@code null} or empty → unknown
     * @param rosterSize total number of locations in the region (for coverage); a
     *                   non-positive value disables the thin-coverage downgrade
     * @return the derived confidence, or {@code null} when the signal is unknown
     *         (no scored locations)
     */
    public static Confidence derive(int daysAhead, BriefingRatingStats.Stats stats, int rosterSize) {
        if (stats == null || stats.isEmpty()) {
            return null;
        }
        Confidence base = fromHorizon(daysAhead);
        boolean wideSpread = stats.ratingRange() >= WIDE_SPREAD_RANGE;
        boolean thinCoverage = rosterSize > 0
                && stats.count() < THIN_COVERAGE_RATIO * rosterSize;
        if (wideSpread || thinCoverage) {
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
