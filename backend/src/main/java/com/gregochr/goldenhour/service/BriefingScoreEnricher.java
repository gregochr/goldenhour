package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.BriefingDay;

import java.util.List;
import java.util.Set;

/**
 * The serve-time enrichment socket: re-derives each region's Claude-rating rollup from a
 * {@link RegionScoreResolver}, without the assembler needing to know how that
 * enrichment is actually computed.
 *
 * <p>Backed today by {@link BriefingService#enrichWithCachedScores(List,
 * RegionScoreResolver)} — the same method the briefing <em>build</em> path
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
     * region/date/target at a time via {@code resolver} — and, separately, decides which voting
     * slots the verdict-minimum-sample rule counts as "examined" via {@code triagedResolver}.
     *
     * <p>The two resolvers answer genuinely independent questions about the SAME region/date/target
     * key and must not be derived one from the other — see {@link TriagedByBatchResolver}'s own
     * javadoc for why a flag riding on {@code resolver}'s own return value could not reach the
     * rollup reliably (a Codex review of #943, P1-A, round 2).
     *
     * @param days            the hierarchy to enrich; the original is left unchanged
     * @param resolver        resolves cached Claude scores for one region/date/target
     * @param triagedResolver resolves the batch-disposition-triaged location names for the same key
     * @return a rebuilt hierarchy with enriched slots
     */
    List<BriefingDay> enrich(List<BriefingDay> days, RegionScoreResolver resolver,
            TriagedByBatchResolver triagedResolver);

    /**
     * Convenience overload for callers that have no triaged-location evidence to supply — every
     * slot then reads as not examined-by-triage (safe under-counting; see {@code
     * VerdictSampleGate}'s own documented direction). Production always calls the 3-arg method;
     * this exists so tests that predate the verdict-minimum-sample rule, and that assert nothing
     * about it, keep compiling and passing unchanged.
     *
     * @param days     the hierarchy to enrich; the original is left unchanged
     * @param resolver resolves cached Claude scores for one region/date/target
     * @return a rebuilt hierarchy with enriched slots
     */
    default List<BriefingDay> enrich(List<BriefingDay> days, RegionScoreResolver resolver) {
        return enrich(days, resolver, (regionName, date, targetType) -> Set.of());
    }
}
