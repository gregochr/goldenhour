package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * The outcome of a best-bet advisor run: an explicit {@link BestBetStatus} plus the picks
 * produced (empty unless the status is {@link BestBetStatus#SUCCESS_WITH_PICKS}).
 *
 * <p>Returning this instead of a bare {@code List<BestBet>} lets callers distinguish an honest
 * empty result from a failure — the ambiguity that let a truncation bug masquerade as
 * honest-decline. The advisor sets the status; downstream layers switch on it (serving UX,
 * persisted run history, the fail-safe fallback).
 *
 * @param status the outcome discriminator
 * @param picks  the picks produced (never null; empty for {@code SUCCESS_NO_PICKS}/{@code FAILED})
 */
public record BestBetResult(BestBetStatus status, List<BestBet> picks) {

    /**
     * Canonical constructor with defensive copy and null-safety on {@code picks}.
     *
     * @param status the outcome discriminator
     * @param picks  the picks produced (null treated as empty)
     */
    public BestBetResult {
        picks = picks == null ? List.of() : List.copyOf(picks);
    }

    /**
     * Result for a successful run that produced picks.
     *
     * @param picks the produced picks (must be non-empty in practice)
     * @return a {@code SUCCESS_WITH_PICKS} result
     */
    public static BestBetResult withPicks(List<BestBet> picks) {
        return new BestBetResult(BestBetStatus.SUCCESS_WITH_PICKS, picks);
    }

    /**
     * Result for a run that honestly found nothing worth crowning.
     *
     * @return a {@code SUCCESS_NO_PICKS} result with no picks
     */
    public static BestBetResult noPicks() {
        return new BestBetResult(BestBetStatus.SUCCESS_NO_PICKS, List.of());
    }

    /**
     * Result for a run that did not produce a usable outcome.
     *
     * @return a {@code FAILED} result with no picks
     */
    public static BestBetResult failed() {
        return new BestBetResult(BestBetStatus.FAILED, List.of());
    }
}
