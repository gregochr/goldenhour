package com.gregochr.goldenhour.model.runs;

/**
 * What one pipeline cycle's batches cost: the sum over the distinct job runs behind its batches (and its
 * disposition anchor), and the GBP-per-USD rate of the latest of them. Batch spend only; the briefing's cost sits
 * on its own unlinked run.
 *
 * @param costMicroDollars the total in micro-dollars, zero when no run carries a cost
 * @param costGbpRate      GBP per USD from the latest cost-bearing run, or {@code null} when none has a rate
 */
public record CycleCost(long costMicroDollars, Double costGbpRate) {
}
