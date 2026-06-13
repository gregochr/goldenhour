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
     * Returns a transformed copy of {@code response} with zero-coverage regions
     * rewritten. Regions whose {@code scoredLocationCount > 0} are reused
     * unchanged; the day/event/region hierarchy is otherwise structurally
     * identical to the input.
     *
     * <p>Returns {@code null} when the input is {@code null}.
     *
     * @param response the API-bound briefing response (may be {@code null})
     * @return transformed response, or {@code null} if input was {@code null}
     */
    static DailyBriefingResponse apply(DailyBriefingResponse response) {
        if (response == null) {
            return response;
        }
        List<BriefingDay> rewrittenDays = response.days().stream()
                .map(BriefingHonestyFilter::rewriteDay)
                .toList();
        return new DailyBriefingResponse(
                response.generatedAt(), response.headline(), rewrittenDays,
                response.bestBets(), response.auroraTonight(), response.auroraTomorrow(),
                response.stale(), response.partialFailure(), response.failedLocationCount(),
                response.bestBetModel(), response.hotTopics(), response.seasonalFeatures(),
                response.bestBetStatus());
    }

    private static BriefingDay rewriteDay(BriefingDay day) {
        List<BriefingEventSummary> rewrittenEvents = day.eventSummaries().stream()
                .map(BriefingHonestyFilter::rewriteEvent)
                .toList();
        return new BriefingDay(day.date(), rewrittenEvents);
    }

    private static BriefingEventSummary rewriteEvent(BriefingEventSummary es) {
        List<BriefingRegion> rewrittenRegions = es.regions().stream()
                .map(BriefingHonestyFilter::rewriteRegionIfZeroCoverage)
                .toList();
        return new BriefingEventSummary(es.targetType(), rewrittenRegions, es.unregioned());
    }

    /**
     * Rewrites a single region when it has zero Claude-scored locations,
     * otherwise returns it unchanged. The triage {@code verdict} and weather
     * summary fields are preserved on the rewritten region — they remain
     * factually true and are useful to downstream consumers that may read the
     * API response directly. Only the user-facing presentation fields are
     * overridden.
     */
    private static BriefingRegion rewriteRegionIfZeroCoverage(BriefingRegion r) {
        if (r.scoredLocationCount() > 0) {
            return r;
        }
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
                VERDICT_LABEL);
    }
}
