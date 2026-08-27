package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;

import java.time.LocalDate;
import java.util.Map;

/**
 * Resolves the cached Claude scores for one region/date/target, keyed by location name.
 *
 * <p>This is the seam that lets one rollup serve two timings. The build path hands it a
 * per-region lookup ({@code EvaluationViewService::getScoresForEnrichment}); the serve path hands
 * it a closure over a bulk index loaded once for the whole payload. Neither timing is visible to
 * {@link BriefingRegionEvaluationRollup}, which is the point — <em>where the scores come from</em>
 * is the caller's business, <em>how a scored region is derived from them</em> is the rollup's.
 *
 * <p>Top-level rather than nested on a service, so that neither the rollup nor
 * {@link ServedBriefingAssembler} has to reach into another class's namespace to name it.
 */
@FunctionalInterface
public interface RegionScoreResolver {

    /**
     * Returns the cached evaluation results for one region on one date and solar event.
     *
     * @param regionName the region to resolve
     * @param date       the forecast date
     * @param targetType SUNRISE or SUNSET
     * @return results keyed by location name; empty when nothing is cached for that key
     */
    Map<String, BriefingEvaluationResult> resolve(String regionName, LocalDate date,
            TargetType targetType);
}
