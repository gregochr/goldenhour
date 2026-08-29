package com.gregochr.goldenhour.model.comingup;

/**
 * The sparkline data for a tide-run chronology entry (plan §13, P3b).
 *
 * <p>{@code delta} is exactly {@code TideRunBuilder}'s existing {@code rangeAnomaly} — a
 * string→typed promotion, not new maths — and {@code phase} names the extremum the entry's own
 * alignment fact already carries, so the sparkline can never draw a different water than the prose
 * describes.
 *
 * @param range  the run's peak tidal range, in metres
 * @param delta  signed anomaly against the representative port's own mean range, in metres
 * @param phase  {@code HW} or {@code LW} — which extremum the sparkline marks and inverts around
 */
public record ComingUpTide(double range, double delta, String phase) {
}
