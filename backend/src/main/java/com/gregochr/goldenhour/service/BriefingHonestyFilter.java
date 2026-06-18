package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;

import java.util.List;

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
        List<BriefingDay> rewrittenDays = response.days().stream()
                .map(day -> rewriteDay(day, minCoverageRatio))
                .toList();
        return new DailyBriefingResponse(
                response.generatedAt(), response.headline(), rewrittenDays,
                response.bestBets(), response.auroraTonight(), response.auroraTomorrow(),
                response.stale(), response.partialFailure(), response.failedLocationCount(),
                response.bestBetModel(), response.hotTopics(), response.seasonalFeatures(),
                response.bestBetStatus());
    }

    private static BriefingDay rewriteDay(BriefingDay day, double minCoverageRatio) {
        List<BriefingEventSummary> rewrittenEvents = day.eventSummaries().stream()
                .map(es -> rewriteEvent(es, minCoverageRatio))
                .toList();
        return new BriefingDay(day.date(), rewrittenEvents);
    }

    private static BriefingEventSummary rewriteEvent(BriefingEventSummary es,
            double minCoverageRatio) {
        List<BriefingRegion> rewrittenRegions = es.regions().stream()
                .map(region -> rewriteRegionByCoverage(region, minCoverageRatio))
                .toList();
        return new BriefingEventSummary(es.targetType(), rewrittenRegions, es.unregioned());
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
        if (r.scoredLocationCount() == 0) {
            return fullRewrite(r);
        }
        int total = r.slots().size();
        if (total > 0 && r.scoredLocationCount() < minCoverageRatio * total) {
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
