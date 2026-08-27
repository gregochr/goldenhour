package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.BriefingDay;

import java.util.List;

/**
 * The serve-time enrichment socket: re-derives each region's Claude-rating rollup from a
 * {@link BriefingService.RegionScoreResolver}, without the assembler needing to know how that
 * enrichment is actually computed.
 *
 * <p>Backed today by {@link BriefingService#enrichWithCachedScores(List,
 * BriefingService.RegionScoreResolver)} — the same method the briefing <em>build</em> path
 * shares via its own 1-arg overload — so the logic itself stays a single, shared implementation
 * rather than being duplicated or moved. {@code ServedBriefingAssembler} takes this narrow
 * functional dependency rather than the enrichment logic itself, so a future change to where or
 * how that logic lives (see {@code docs/engineering/served-briefing-assembler-plan.md} Proposal
 * 2) can swap the implementation without touching the assembler.
 */
@FunctionalInterface
public interface BriefingScoreEnricher {

    /**
     * Walks the day/event/region hierarchy and populates each slot's Claude fields, resolved one
     * region/date/target at a time via {@code resolver}.
     *
     * @param days     the hierarchy to enrich; the original is left unchanged
     * @param resolver resolves cached Claude scores for one region/date/target
     * @return a rebuilt hierarchy with enriched slots
     */
    List<BriefingDay> enrich(List<BriefingDay> days, BriefingService.RegionScoreResolver resolver);
}
