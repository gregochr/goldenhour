package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;
import java.util.Set;

/**
 * Resolves the set of location names whose latest, non-{@code SKIPPED_CACHED}
 * {@code forecast_run_disposition} for one region/date/target was {@code SKIPPED_TRIAGED} — the
 * verdict-minimum-sample rule's disposition-sourced half of "examined" ({@code
 * VerdictSampleGate#examinedCount}, a Codex review of #943, P1-A).
 *
 * <p>Deliberately a SEPARATE seam from {@link RegionScoreResolver}, not a flag riding on {@link
 * com.gregochr.goldenhour.model.BriefingEvaluationResult}. A round of review against the first cut
 * (which stamped a synthetic marker onto the score map, but only when the resolver had nothing
 * else to return) found that production almost always writes a real {@code forecast_evaluation}
 * triage row alongside the disposition, so the marker's one branch was rarely hit and {@code
 * examinedCount} silently collapsed to rated-only on the exact production shape the gate exists to
 * protect. Handing {@link BriefingRegionEvaluationRollup} this set directly — independent of
 * whatever {@link RegionScoreResolver} resolves for the same slot — means a triaged slot counts
 * whether the score resolver's answer for it is a genuine triage result, nothing, or a retraction
 * marker; the two questions are asked and answered independently, never one derived from the other.
 *
 * <p>The build path hands it {@code EvaluationViewService::getTriagedByBatchLocationNames}; the
 * serve path hands it a closure over a bulk index loaded once for the whole payload — the same
 * shape {@link RegionScoreResolver}'s own two production implementations already take.
 */
@FunctionalInterface
public interface TriagedByBatchResolver {

    /**
     * Returns the location names in one region, on one date and solar event, whose latest
     * non-{@code SKIPPED_CACHED} batch disposition was {@code SKIPPED_TRIAGED}.
     *
     * @param regionName the region to resolve
     * @param date       the forecast date
     * @param targetType SUNRISE or SUNSET
     * @return triaged location names for that slot; empty when nothing qualifies
     */
    Set<String> resolve(String regionName, LocalDate date, TargetType targetType);
}
