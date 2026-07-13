package com.gregochr.goldenhour.model;

import java.util.Map;
import java.util.Set;

/**
 * Carries the rollup JSON and derived validation sets out of the briefing rollup builder.
 *
 * @param json          the compact JSON string sent to Claude as the user message
 * @param validEvents   all event identifiers present in the rollup (e.g. {@code "2026-03-30_sunset"})
 * @param validRegions  all region names present in the rollup
 * @param validDayNames day names (e.g. {@code "Monday"}) for all dates in the forecast window
 * @param coverageByKey Claude-evaluation coverage per {@code event|region} key — the same
 *                      data the prompt sees, surfaced for the deterministic coverage gate
 */
public record RollupResult(String json, Set<String> validEvents,
        Set<String> validRegions, Set<String> validDayNames,
        Map<String, CandidateCoverage> coverageByKey) {
}
