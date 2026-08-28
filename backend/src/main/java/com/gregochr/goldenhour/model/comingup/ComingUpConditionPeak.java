package com.gregochr.goldenhour.model.comingup;

/**
 * A standing condition's gated peak occurrence in the window (plan §13) — the highest-scoring
 * occurrence that also lands within the peak-light window, or absent when none passes the gate.
 *
 * @param dateLabel  the peak's formatted date
 * @param valueLabel the peak's formatted intensity
 * @param bits       the peak's surprise score
 */
public record ComingUpConditionPeak(String dateLabel, String valueLabel, double bits) {
}
