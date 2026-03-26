package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.AlertLevel;

import java.util.List;

/**
 * Tonight's aurora summary, derived from the cached aurora forecast scores and cloud triage.
 * Present in the briefing response only when the aurora state machine is active.
 *
 * @param alertLevel          current alert level (MINOR / MODERATE / STRONG)
 * @param kp                  Kp index that triggered the current alert, or {@code null}
 * @param clearLocationCount  number of locations that passed cloud triage
 * @param regions             aurora-eligible locations grouped by geographic region
 */
public record AuroraTonightSummary(
        AlertLevel alertLevel,
        Double kp,
        int clearLocationCount,
        List<AuroraRegionSummary> regions) {

    /** Defensive compact constructor. */
    public AuroraTonightSummary {
        regions = List.copyOf(regions);
    }
}
