package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BestBetStatus;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.service.pipeline.BestBetFallbackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the ordered, serve-time composition that turns a stored briefing snapshot into what a
 * client is served: re-enrichment, the best-bet fallback, the honesty filter, movement, the tide
 * rollup, and the window projection.
 *
 * <p>{@link BriefingService} keeps cache access and refresh (build-path) orchestration; this class
 * owns the composition alone, so a serve-path change lands in one class with one job rather than
 * inside the 1300+-line orchestrator. See {@code docs/engineering/served-briefing-assembler-plan.md}
 * for the extraction's rationale and the two wiring failure modes this class's construction is
 * shaped to avoid.
 *
 * <p><b>Not wired via a {@link BriefingScoreEnricher} bean satisfied by a
 * {@code BriefingService::enrichWithCachedScores} method reference.</b> That factory method would
 * need a fully-constructed {@code BriefingService} bean, and {@code BriefingService} needs this
 * class — a startup cycle that the ordinary local test gate does not catch, because it excludes the
 * context-booting tests. Instead, this bean is built with no reference to {@code BriefingService} at
 * all, and {@code BriefingService}'s own constructor binds the enricher afterwards via {@link
 * #bindScoreEnricher}, once both beans exist.
 */
@Service
class ServedBriefingAssembler {

    private static final Logger LOG = LoggerFactory.getLogger(ServedBriefingAssembler.class);

    private final BestBetFallbackService bestBetFallbackService;
    private final BriefingRegionSnapshotService regionSnapshotService;
    private final WindowTideRollupBuilder windowTideRollupBuilder;
    private final EvaluationViewService evaluationViewService;
    private final Clock clock;

    /**
     * The enrichment socket, now injected like any other collaborator.
     *
     * <p>This was a non-final field bound by a setter from {@code BriefingService}'s constructor,
     * because the only implementation was {@code BriefingService::enrichWithCachedScores} and a
     * constructor parameter would have made the two beans depend on each other. Extracting
     * {@link BriefingRegionEvaluationRollup} removed the cycle at its source — the rollup needs
     * nothing but a {@code Clock} — so ordinary constructor injection works, and with it goes the
     * window in which this field could be observed null.
     */
    private final BriefingScoreEnricher scoreEnricher;

    ServedBriefingAssembler(BestBetFallbackService bestBetFallbackService,
            BriefingRegionSnapshotService regionSnapshotService,
            WindowTideRollupBuilder windowTideRollupBuilder,
            @Lazy EvaluationViewService evaluationViewService,
            Clock clock,
            BriefingScoreEnricher scoreEnricher) {
        this.bestBetFallbackService = bestBetFallbackService;
        this.regionSnapshotService = regionSnapshotService;
        this.windowTideRollupBuilder = windowTideRollupBuilder;
        this.evaluationViewService = evaluationViewService;
        this.clock = clock;
        this.scoreEnricher = scoreEnricher;
    }

    /**
     * Assembles the served briefing <b>without</b> the Plan-tab window projection: re-enrichment,
     * the serve-time best-bet fallback, and the honesty filter, in that order. Backs {@link
     * BriefingService#getServedBriefing()} — see that method's javadoc for the panel-sharing
     * contract (shared by {@code CloseToHomeService}) this composition exists to support.
     *
     * @param snapshot         the cached briefing, or {@code null} if none has been built yet
     * @param minCoverageRatio the honesty filter's lightly-evaluated threshold; see {@link
     *                         BriefingHonestyFilter#apply(DailyBriefingResponse, double)}
     * @return the enriched, fallback-applied, honesty-filtered response, or {@code null} if
     *         {@code snapshot} was {@code null}
     */
    DailyBriefingResponse assembleWithoutPlan(DailyBriefingResponse snapshot,
            double minCoverageRatio) {
        return BriefingHonestyFilter.apply(
                applyBestBetFallback(reEnrichVerdicts(snapshot)),
                minCoverageRatio);
    }

    /**
     * Assembles the served briefing <b>with</b> the Plan-tab window projection attached:
     * {@link #assembleWithoutPlan}, then movement, the per-window tide rollup, and finally
     * {@link PlanWindowProjector}. Backs {@link BriefingService#getCachedBriefingForApi()}.
     *
     * @param snapshot         the cached briefing, or {@code null} if none has been built yet
     * @param minCoverageRatio the honesty filter's lightly-evaluated threshold
     * @return the fully assembled API payload, or {@code null} if {@code snapshot} was {@code null}
     */
    DailyBriefingResponse assembleForPlan(DailyBriefingResponse snapshot, double minCoverageRatio) {
        // The window projection is deliberately OUTERMOST. It reads regions to name a Best Bet, a
        // verdict and a rating, so it must see the same regions the caller does — and the honesty
        // filter blanks a zero-coverage region to STAND_DOWN with an empty slot list on the way
        // past. Projected any earlier, a window would confidently describe a region the same
        // response reports as unevaluable. See PlanWindowProjector.
        //
        // The honesty filter wraps the fallback rather than sitting inside it, so that EVERY pick
        // on the way out has been checked against the regions blanked on this serve. With the
        // order reversed, a fallback swapped in afterwards was the one list never checked — and it
        // is the most likely to fail the check, being picks from an earlier forecast offered
        // precisely because this one's advisor failed. The stale chip tells a reader the picks are
        // old; it does not tell them the region behind one has no forecast at all.
        //
        // The fallback still runs on the ENRICHED response, so its FAILED test and the filter's
        // coverage test both read current state. Nothing else about it is order-sensitive: it
        // reads only bestBetStatus and passes the day hierarchy through untouched.
        // Movement is attached HERE and not inside assembleWithoutPlan (which backs
        // BriefingService.getServedBriefing()), and the distinction is the same one that method's
        // own javadoc was written to make: CloseToHomeService shares that accessor and reads slots
        // and bestBets only, so a movement step inside it would cost /api/user/settings/reach two
        // queries and a full rebuild of the day hierarchy for a field it discards — the exact "paid
        // twice per page, one copy thrown away" pattern the projection was moved out here to stop.
        // Nothing renders a delta except the Plan tab, and this is the Plan tab's payload.
        //
        // Before the projector, deliberately: the projector is the outermost step and rebuilds the
        // response through withPlan, which carries previousGeneratedAt and every region untouched.
        DailyBriefingResponse filtered = attachMovement(assembleWithoutPlan(snapshot, minCoverageRatio));
        // The tide rollup is derived here rather than inside the projector so the projector stays a
        // pure function of the response: it needs three repositories, and the projector has none.
        // It is fed the FILTERED days for one reason only — the same day list the windows are
        // projected from, so a rollup can never be keyed to a window that no longer exists.
        //
        // UTC, deliberately not LONDON: BriefingSlot.solarEventTime (and every other stored clock
        // time this "now" is compared against inside the projector's isPast/afterglow check) is
        // UTC. Reading "now" through Europe/London put it an hour ahead of that axis during BST,
        // which declared a window past up to an hour early — see plan-verdict-consolidation-plan.md
        // §1 D4. This is the "one clock, two calendars" rule from the daysAhead work: DATES are
        // London, INSTANT comparisons are UTC, and this is an instant comparison.
        return PlanWindowProjector.apply(
                filtered,
                LocalDateTime.now(clock.withZone(ZoneOffset.UTC)),
                filtered == null ? Map.of()
                        : windowTideRollupBuilder.build(filtered.days()));
    }

    /**
     * Attaches each region's movement since the previous briefing build, and names that build.
     *
     * <p>A sibling step beside {@link BriefingService#enrichWithCachedScores} rather than a clause
     * inside it, because the two run on different paths for different reasons. Enrichment runs on
     * the build path as well, and a delta computed there would be persisted into
     * {@code daily_briefing_cache} — where it would be a frozen figure measured against a build
     * that is by then two behind, re-served for hours. Movement is a serve-time quantity only.
     *
     * <p><b>After {@link BriefingHonestyFilter}.</b> The filter blanks a zero-coverage region by
     * rewriting it with a null {@code meanRating}, so running afterwards means a blanked region can
     * never acquire a delta — no null check about it is needed here, and none can be forgotten.
     * Running before would also have handed the filter's positional rewrite a field to drop
     * silently.
     *
     * <p><b>Its cost is two indexed queries per Plan-tab serve, and the try/catch is what makes it
     * safe.</b> {@code previousBuild} throws rather than swallowing — see its own javadoc for why a
     * catch inside a transactional method cannot work — so the isolation is here, outside the bean
     * boundary, exactly as {@link BriefingService#recordRegionSnapshots} does on the write side.
     * Every failure mode (no snapshot rows, no earlier build, a repository error) then resolves to
     * no delta anywhere, which the frontend renders as nothing at all.
     *
     * @param response the honesty-filtered response (may be {@code null})
     * @return a copy carrying per-region deltas and the basis they were measured against
     */
    private DailyBriefingResponse attachMovement(DailyBriefingResponse response) {
        if (response == null || response.days().isEmpty()) {
            return response;
        }
        BriefingRegionSnapshotService.PreviousBuild previous;
        try {
            previous = regionSnapshotService.previousBuild(response.generatedAt());
        } catch (Exception e) {
            LOG.warn("Movement lookup failed — the Plan tab renders no movement this serve: {}",
                    e.getMessage());
            return response;
        }
        if (previous.isEmpty()) {
            // No basis: publish neither deltas nor a stamp. A `previousGeneratedAt` with no delta
            // behind it would name a comparison the payload does not contain.
            return response;
        }
        List<BriefingDay> withMovement = new ArrayList<>(response.days().size());
        for (BriefingDay day : response.days()) {
            List<BriefingEventSummary> events = new ArrayList<>(day.eventSummaries().size());
            for (BriefingEventSummary es : day.eventSummaries()) {
                List<BriefingRegion> regions = es.regions().stream()
                        .map(region -> region.withMeanRatingDelta(
                                BriefingRegionSnapshotService.delta(
                                        region.meanRating(),
                                        previous.meanByKey().get(BriefingRegionSnapshotService.key(
                                                region.regionName(), day.date(),
                                                es.targetType().name())))))
                        .toList();
                events.add(es.withRegions(regions));
            }
            // The WITHER, never `new BriefingDay(date, events)`: that form defaults `peak` away,
            // and it is safe here only because the sole producer of a peak runs strictly after this
            // step. The two-arg constructor's own javadoc names this as the trap it exists to avoid.
            withMovement.add(day.withEventSummaries(events));
        }
        return response.withMovement(withMovement, previous.generatedAt());
    }

    /**
     * Re-derives each region's Claude-rating rollup (slot scores, {@code displayVerdict},
     * {@code scoredLocationCount}, gloss validity) from the <em>current</em> evaluation state at
     * serve time, so the verdict a cell shows never lags behind the rating batch that has since
     * re-scored it.
     *
     * <p>The region {@code displayVerdict} is otherwise frozen when the briefing is built
     * (every ~8h), while the per-location scores the frontend badges are read live through
     * {@link EvaluationViewService}. That gap let a stale "Worth it" label sit above a fresh low
     * rating. Re-running the same enrichment used at build time — sourced from the same
     * {@link EvaluationViewService} the badge reads — makes the label and the rating coherent by
     * construction, with no logic pushed into the render layer.
     *
     * <p>Scoped to the API read path only, for the same reason as {@link BriefingHonestyFilter}:
     * internal callers of {@link BriefingService#getCachedBriefing()} (batch task collector,
     * model-comparison harness) need the untransformed triage slots to decide what to evaluate.
     *
     * <p>The current scores are pulled with a single
     * {@link EvaluationViewService#getScoresForEnrichmentBulk} load over the plan window rather
     * than a lookup per region/date/target, so re-enriching on every request stays to O(locations)
     * queries.
     *
     * @param response the cached briefing (may be {@code null})
     * @return a copy whose regions carry freshly re-derived verdicts, or {@code null} if input was
     */
    private DailyBriefingResponse reEnrichVerdicts(DailyBriefingResponse response) {
        if (response == null || response.days().isEmpty()) {
            return response;
        }
        LocalDate start = response.days().getFirst().date();
        LocalDate end = response.days().getLast().date();
        Set<TargetType> types = response.days().stream()
                .flatMap(day -> day.eventSummaries().stream())
                .map(BriefingEventSummary::targetType)
                .collect(java.util.stream.Collectors.toCollection(
                        () -> java.util.EnumSet.noneOf(TargetType.class)));
        if (types.isEmpty()) {
            return response;
        }
        Map<String, Map<String, BriefingEvaluationResult>> index =
                evaluationViewService.getScoresForEnrichmentBulk(start, end, types);
        RegionScoreResolver resolver = (regionName, date, targetType) ->
                index.getOrDefault(regionName + "|" + date + "|" + targetType, Map.of());
        return response.withDays(scoreEnricher.enrich(response.days(), resolver));
    }

    /**
     * Fail-safe best-bet fallback applied at serve time: when the served response's best-bet
     * outcome is {@link BestBetStatus#FAILED} and a fresh-enough prior successful pick exists,
     * substitute those picks (the frontend renders them with a stale chip, since the status
     * stays {@code FAILED}). Re-evaluated on every request so freshness (event-not-passed, within
     * the age ceiling) is always current — the fallback is never baked into the persisted cache.
     *
     * <p>No-op unless the status is {@code FAILED}: an honest {@code SUCCESS_NO_PICKS} keeps its
     * empty state, and a {@code FAILED} with no fresh-enough prior pick falls through to the
     * honest empty state rather than resurrecting a stale or passed pick.
     *
     * <p><b>Runs BEFORE {@link BriefingHonestyFilter}, and must stay there.</b> Picks substituted
     * here are therefore still subject to withdrawal, which is the whole reason the filter wraps
     * this method rather than sitting inside it: a stale list is the one most likely to name a
     * region this serve cannot evaluate, being picks from an earlier forecast offered precisely
     * because this cycle's advisor failed. Hoist the filter back inside and this list becomes the
     * only one never coverage-checked — which is how a stale pick came to crown a blanked region.
     * One test guards the ordering: {@code fallbackPickOnBlankedRegion_isWithdrawn}.
     *
     * @param response the re-enriched response, not yet honesty-filtered (may be null)
     * @return the response, possibly decorated with fallback picks
     */
    private DailyBriefingResponse applyBestBetFallback(DailyBriefingResponse response) {
        if (response == null || response.bestBetStatus() != BestBetStatus.FAILED) {
            return response;
        }
        List<BestBet> fallback = bestBetFallbackService.findFreshFallback();
        if (fallback.isEmpty()) {
            return response;
        }
        return new DailyBriefingResponse(
                response.generatedAt(), response.headline(), response.days(),
                fallback, response.auroraTonight(), response.auroraTomorrow(),
                response.stale(), response.partialFailure(), response.failedLocationCount(),
                response.bestBetModel(), response.hotTopics(), response.seasonalFeatures(),
                // Carried, not cleared: this now runs BEFORE the honesty filter, so there is no
                // withdrawal yet to describe — the filter sets the flag afterwards, against
                // whichever list it ends up seeing, including this one.
                response.bestBetStatus(), response.bestBetsWithdrawn());
    }
}
