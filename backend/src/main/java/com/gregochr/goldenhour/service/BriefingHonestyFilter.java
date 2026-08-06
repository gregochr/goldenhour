package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Read-time transform that suppresses positive verdicts for regions where
 * <em>zero</em> per-location Claude evaluations are available, replacing the
 * triage-derived display fields with honest "we did not evaluate this"
 * messaging.
 *
 * <p><b>Role after the Gate 2 redesign:</b> failure-defence. Under the Gate 2
 * verdict-as-attribute redesign, weather-condition STANDDOWN slots reach Claude
 * via {@link BriefingGatingPolicy}, so policy-driven zero-coverage cases are
 * rare. Residual zero-coverage now comes from:
 * <ul>
 *   <li>Batch API failures or partial results that write zero rows to a
 *       region's cache for a given (date, target).</li>
 *   <li>Regions whose every slot is hard-constrained (e.g. all-tide-mismatched
 *       coastal regions at a particular tide phase). The region's triage
 *       verdict may still read GO/MARGINAL from a non-coastal subset of slots
 *       but the hard-constraint slots never reach Claude.</li>
 *   <li>The narrow window between a briefing refresh and the first batch
 *       result writing back. Briefings refresh every ~8h; batches arrive
 *       within minutes-to-hours.</li>
 * </ul>
 *
 * <p><b>Before the redesign</b> the filter also caught the dominant
 * policy-driven zero-coverage case where Gate 2 had filtered every slot
 * before Claude could score them. That case has been collapsed by the policy
 * change.
 *
 * <p>The filter is intentionally scoped to the API read path
 * ({@link BriefingService#getCachedBriefingForApi}). Internal callers of
 * {@link BriefingService#getCachedBriefing} (the batch task collector, the
 * SSE drill-down service, the model-comparison test harness) require the
 * untransformed briefing — they consult the original triage slots to decide
 * what to evaluate, so applying the override there would create a deadlock:
 * no slots to batch → no cached evaluations written → override stays forever.
 *
 * <p>The transform is pure and stateless; it allocates new immutable record
 * instances for any region it rewrites and reuses the originals otherwise.
 *
 * <p><b>Coverage tiers.</b> The filter recognises three tiers of Claude
 * coverage per region, keyed on {@code scoredLocationCount} relative to the
 * region's roster size:
 * <ul>
 *   <li><b>Zero</b> ({@code scoredLocationCount == 0}) — the full rewrite
 *       described above (verdict, summary, gloss, slots all suppressed).</li>
 *   <li><b>Lightly evaluated</b> ({@code 0 < scored/total < minCoverageRatio})
 *       — a NEW, lighter tier. The region keeps its real slots, gloss, scores
 *       and triage summary, but is flagged {@link BriefingRegion#lightlyEvaluated()}
 *       so the read layer frames it as covering only the few evaluated spots
 *       rather than the whole roster. Nothing is suppressed.</li>
 *   <li><b>Well covered</b> ({@code scored/total >= minCoverageRatio}) —
 *       returned unchanged.</li>
 * </ul>
 */
final class BriefingHonestyFilter {

    /** Pill label that replaces the default {@code STAND_DOWN} label when the override fires. */
    static final String VERDICT_LABEL = "Too unsettled to forecast";

    /** The top Best Bet's rank. Losing it withdraws the block; see {@link #withdrawUnsupportedBets}. */
    private static final int RANK_TOP = 1;

    /** Single-line summary that replaces "Clear at N of M locations" when the override fires. */
    static final String REPLACEMENT_SUMMARY =
            "No per-location forecast — conditions too unsettled to evaluate";

    /** Long-form gloss that replaces the briefing's prose when the override fires. */
    static final String REPLACEMENT_GLOSS_DETAIL =
            "Conditions across this region were classified as too unsettled to evaluate "
            + "confidently at this horizon. No per-location forecast was produced. "
            + "The picture may firm up closer to the date — or it may remain unsettled.";

    private BriefingHonestyFilter() {
    }

    /**
     * Backward-compatible overload that disables the lightly-evaluated tier
     * (ratio {@code 0.0} can never be exceeded by a positive coverage fraction,
     * so only the zero-coverage rewrite fires). Retained for callers and tests
     * that only exercise the zero-coverage guard.
     *
     * @param response the API-bound briefing response (may be {@code null})
     * @return transformed response, or {@code null} if input was {@code null}
     */
    static DailyBriefingResponse apply(DailyBriefingResponse response) {
        return apply(response, 0.0);
    }

    /**
     * Returns a transformed copy of {@code response}. Zero-coverage regions are
     * fully rewritten; regions whose coverage ratio is positive but below
     * {@code minCoverageRatio} are flagged {@link BriefingRegion#lightlyEvaluated()}
     * (no suppression); well-covered regions are reused unchanged. The
     * day/event/region hierarchy is otherwise structurally identical to the input.
     *
     * <p>Returns {@code null} when the input is {@code null}.
     *
     * @param response         the API-bound briefing response (may be {@code null})
     * @param minCoverageRatio coverage fraction below which a region is flagged
     *                         lightly evaluated; {@code 0.0} disables the tier
     * @return transformed response, or {@code null} if input was {@code null}
     */
    static DailyBriefingResponse apply(DailyBriefingResponse response, double minCoverageRatio) {
        if (response == null) {
            return response;
        }
        Set<String> blanked = new HashSet<>();
        List<BriefingDay> rewrittenDays = response.days().stream()
                .map(day -> rewriteDay(day, minCoverageRatio, blanked))
                .toList();
        List<BestBet> keptBets = withdrawUnsupportedBets(response.bestBets(), blanked);
        // Flagged rather than inferred: a one-pick list means "the advisor withheld a runner-up"
        // in every other case, and the banner says so in prose. Only this method knows the
        // difference, and only on this serve.
        Boolean withdrawn = response.bestBets() != null
                && keptBets.size() != response.bestBets().size() ? Boolean.TRUE : null;
        return new DailyBriefingResponse(
                response.generatedAt(), response.headline(), rewrittenDays,
                keptBets,
                response.auroraTonight(), response.auroraTomorrow(),
                response.stale(), response.partialFailure(), response.failedLocationCount(),
                response.bestBetModel(), response.hotTopics(), response.seasonalFeatures(),
                response.bestBetStatus(), withdrawn);
    }

    private static BriefingDay rewriteDay(BriefingDay day, double minCoverageRatio,
            Set<String> blanked) {
        List<BriefingEventSummary> rewrittenEvents = day.eventSummaries().stream()
                .map(es -> rewriteEvent(es, minCoverageRatio, day.date(), blanked))
                .toList();
        return new BriefingDay(day.date(), rewrittenEvents);
    }

    private static BriefingEventSummary rewriteEvent(BriefingEventSummary es,
            double minCoverageRatio, LocalDate date, Set<String> blanked) {
        List<BriefingRegion> rewrittenRegions = es.regions().stream()
                .map(region -> {
                    BriefingRegion rewritten = rewriteRegionByCoverage(region, minCoverageRatio);
                    if (rewritten != region && rewritten.slots().isEmpty()) {
                        blanked.add(betKey(region.regionName(), date, es.targetType()));
                    }
                    return rewritten;
                })
                .toList();
        // withRegions, not a positional rebuild: this filter empties the slot list of a
        // zero-coverage region, and the slots used to be the only carrier of the event's time.
        // Dropping solarEventTime here would put that back exactly as it was.
        return es.withRegions(rewrittenRegions);
    }

    /**
     * The identity a {@link BestBet} uses for a solar slot: its {@code region} plus its
     * {@code event}, which is {@code "2026-03-30_sunset"}.
     */
    private static String betKey(String regionName, LocalDate date, TargetType targetType) {
        return regionName + "|" + date + "_" + targetType.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Withdraws Best Bets that recommend a region this filter has just blanked.
     *
     * <p>The rewrite above replaces a zero-coverage region with "No per-location forecast" and an
     * empty slot list. A Best Bet naming that same region and event is the same claim the rewrite
     * exists to suppress, made louder — the response would crown a destination it simultaneously
     * reports as unevaluable, and the pick renders perfectly, so nothing looks wrong until someone
     * opens the drill-down. Best Bets are chosen at briefing BUILD time and frozen into the
     * payload; the coverage they were chosen on is re-derived at SERVE time. (Or they are a stale
     * fallback list swapped in on this serve — older still, and checked here for the same reason.)
     *
     * <p><b>Losing rank 1 withdraws the whole block.</b> Rank 2 is defined relative to rank 1 —
     * {@code relationship} and {@code differsBy} describe how it differs from that pick, and its
     * Claude-written prose was composed as the runner-up to it — so an orphaned rank 2 is a
     * comparison to something no longer on screen. The banner also reads {@code picks[0]} as the
     * top pick positionally while labelling by {@code rank}, so leaving one behind renders a lone
     * "② ALSO GOOD" card compared against itself. Promoting it instead would need its prose
     * rewritten, which cannot be done without another Claude call. Withdrawing rank 2 alone is
     * safe and keeps rank 1.
     *
     * <p>{@code bestBetStatus} is deliberately untouched: setting it to {@code FAILED} would be a
     * lie about the advisor, which succeeded. An empty list leaves the client on its honest empty
     * state, distinguished from a flat week by {@code bestBetsWithdrawn}.
     *
     * <p>This runs <em>after</em> {@code BriefingService.applyBestBetFallback}, so a stale
     * fallback list is subject to withdrawal like any other. It used to run before it, which left
     * the fallback as the one list never checked — and it is the most likely to fail the check,
     * being picks from an earlier forecast served precisely because this one's advisor failed.
     *
     * @param bets    the picks to check — the frozen build-time list, or the stale fallback
     *                that replaced it; possibly null or empty
     * @param blanked keys of the regions blanked on this serve, from {@link #betKey}
     * @return the picks that still describe something this response stands behind
     */
    private static List<BestBet> withdrawUnsupportedBets(List<BestBet> bets, Set<String> blanked) {
        if (bets == null || bets.isEmpty() || blanked.isEmpty()) {
            return bets;
        }
        List<BestBet> kept = bets.stream()
                .filter(bet -> !namesBlankedRegion(bet, blanked))
                .toList();
        if (kept.size() == bets.size()) {
            return bets;
        }
        boolean lostRankOne = bets.stream().anyMatch(bet -> bet.rank() == RANK_TOP)
                && kept.stream().noneMatch(bet -> bet.rank() == RANK_TOP);
        return lostRankOne ? List.of() : kept;
    }

    /**
     * Whether a pick recommends a blanked region.
     *
     * <p>A stay-home pick carries a null region and event, and an aurora pick's event is
     * {@code "aurora_tonight"} rather than a solar slot; neither can name a region this filter
     * blanked, and both are left alone.
     */
    private static boolean namesBlankedRegion(BestBet bet, Set<String> blanked) {
        if (bet.region() == null || bet.event() == null) {
            return false;
        }
        return blanked.contains(bet.region() + "|" + bet.event());
    }

    /**
     * Applies the coverage-tier transform to a single region:
     * <ul>
     *   <li>zero scored → full rewrite (triage {@code verdict} and weather
     *       fields preserved as they remain factually true; only user-facing
     *       presentation fields are overridden);</li>
     *   <li>positive but below {@code minCoverageRatio} → flagged
     *       {@link BriefingRegion#lightlyEvaluated()} with nothing suppressed;</li>
     *   <li>otherwise → returned unchanged.</li>
     * </ul>
     */
    private static BriefingRegion rewriteRegionByCoverage(BriefingRegion r,
            double minCoverageRatio) {
        // Canopy slots can never carry a Claude rating — they are deliberately excluded from the
        // sky batch — so they must not count toward the coverage this filter measures. Without
        // this, a region of woods reads as zero-coverage and gets the failure message below, which
        // would be a lie twice over: nothing failed, and a per-location forecast DOES exist (the
        // deterministic woodland verdict). It would also erase those verdicts every single day.
        //
        // This filter is failure-defence — its whole premise is "we expected Claude coverage and
        // did not get it". A canopy slot never expected any.
        // Same predicate as the confidence denominator in BriefingService: a slot counts when it
        // COULD carry a rating, not merely when it is not canopy. An in-season canopy bluebell
        // site is scored by the bluebell prompt, so excluding it would put it in the numerator
        // (scoredLocationCount) and not the denominator — a ratio above 1, silently disabling the
        // lightly-evaluated warning in the class whose whole job is not overstating coverage.
        long scoreable = r.slots().stream()
                .filter(s -> !(s.canopy() && s.claudeRating() == null))
                .count();
        if (scoreable == 0) {
            return r;
        }
        if (r.scoredLocationCount() == 0) {
            return fullRewrite(r);
        }
        if (r.scoredLocationCount() < minCoverageRatio * scoreable) {
            return r.withLightlyEvaluated();
        }
        return r;
    }

    private static BriefingRegion fullRewrite(BriefingRegion r) {
        return new BriefingRegion(
                r.regionName(),
                r.verdict(),
                REPLACEMENT_SUMMARY,
                r.tideHighlights(),
                List.of(),
                r.regionTemperatureCelsius(),
                r.regionApparentTemperatureCelsius(),
                r.regionWindSpeedMs(),
                r.regionWeatherCode(),
                null,
                REPLACEMENT_GLOSS_DETAIL,
                DisplayVerdict.STAND_DOWN,
                0,
                VERDICT_LABEL,
                false);
    }
}
