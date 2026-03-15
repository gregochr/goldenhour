package com.gregochr.goldenhour.model;

/**
 * Result of weather triage evaluation — indicates why a forecast task was triaged out.
 *
 * @param reason human-readable explanation (e.g. "Low cloud cover 85% — sun blocked")
 * @param rule   the triage rule that triggered the skip
 */
public record TriageResult(String reason, TriageRule rule) {
}
