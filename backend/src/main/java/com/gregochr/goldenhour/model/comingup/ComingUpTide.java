package com.gregochr.goldenhour.model.comingup;

/**
 * The sparkline data for a tide-run chronology entry (plan §13, P3b).
 *
 * <p>{@code delta} is the displayed range minus ONE average per card: the entry's
 * {@code rangeAverageMetres} (mean daily range over the past 12 months) when it is served, else the
 * representative's all-history {@code TideStats.avgRangeMetres}. These are different statistics, not a
 * fresher window of one (the mean daily highest-high minus lowest-low is larger than avgHigh minus
 * avgLow at a semidiurnal port), so a served average shrinks the delta; that is the price of the day
 * bars' dashed line and the sparkline agreeing (tide-range-rank-plan.md §2).
 *
 * <p>{@code phase} is always {@code HW}: the card's sunrise and sunset rows name the high water only
 * (tide-both-lights-plan.md), so the sparkline can never draw a different water than the prose
 * describes.
 *
 * @param range  the run's peak tidal range, in metres
 * @param delta  signed difference from the card's average range (see above), in metres
 * @param phase  {@code HW} — the extremum the sparkline marks and inverts around
 */
public record ComingUpTide(double range, double delta, String phase) {
}
