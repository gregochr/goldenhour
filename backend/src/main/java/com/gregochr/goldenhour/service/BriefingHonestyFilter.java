package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.CoverageReason;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.service.EvaluationViewService.LatestDisposition;
import com.gregochr.goldenhour.service.evaluation.BestBetRanker;
import com.gregochr.goldenhour.util.ForecastHorizon;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-time transform that suppresses positive verdicts for regions where
 * <em>zero</em> per-location Claude evaluations are available, replacing the
 * triage-derived display fields with honest "we did not evaluate this"
 * messaging.
 *
 * <p><b>Role after the Gate 2 redesign:</b> failure-defence. Under the Gate 2
 * verdict-as-attribute redesign, weather-condition STANDDOWN slots reach Claude,
 * so zero coverage from a weather stand-down is
 * rare. It is NOT rare overall: it is the designed state of every window Gate 4
 * does not score (T+3 except SETTLED, and all of T+4) and of every travel day,
 * whose batch is skipped. Zero-coverage now comes from:
 * <ul>
 *   <li>Windows outside the scoring policy: T+3 regions that are not SETTLED,
 *       every T+4 region, and travel days. Nothing failed; nothing was
 *       attempted. Per-location tide facts survive this filter on
 *       {@code BriefingWindow.tideFacts}, which is built from the unfiltered
 *       slots before this runs.</li>
 *   <li>Batch API failures or partial results that write zero rows to a
 *       region's cache for a given (date, target).</li>
 *   <li>The narrow window between a briefing refresh and the first batch
 *       result writing back. Briefings refresh every ~8h; batches arrive
 *       within minutes-to-hours.</li>
 * </ul>
 *
 * <p><b>The wording follows the reason.</b> "Too unsettled to forecast" is true only when weather
 * triage stood the region down. The API path therefore folds the pipeline's own latest disposition
 * per slot and the horizon into a {@link CoverageReason} ({@link CoverageReasons}) and words the
 * blank accordingly: a travel day, a window never attempted at this range and a run that did not
 * finish read {@code AWAITING} ("Not forecast", "Not scored at this range", "Forecast unavailable"),
 * and so does anything the record cannot settle ({@code UNKNOWN}: "Not scored"). Only a real
 * weather stand-down keeps {@code STAND_DOWN} and "Too unsettled to forecast" (or "Fog forecast"
 * when most of the triage stand-downs were its visibility rule): a region whose record is
 * incomplete is never worded as poor weather.
 *
 * <p>A region in which no slot could carry a rating (an all-canopy region: woods are kept out of
 * the sky batch) is passed through unchanged, not rewritten.
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
 * <p><b>Coverage tiers.</b> The filter recognises two tiers of Claude coverage per region, keyed
 * on {@code scoredLocationCount}: <b>zero</b> (the full rewrite described above: verdict, summary,
 * gloss and slots all suppressed) and <b>anything else</b>, returned unchanged. A third,
 * "lightly evaluated" tier (a non-zero but low fraction of the roster scored) once flagged the
 * region with {@code BriefingRegion.lightlyEvaluated}; nothing read the flag and it was removed.
 * The {@code minCoverageRatio} parameter is retained on the overloads below for call-site
 * stability and no longer has any effect here ({@code BriefingGlossService} still reads the same
 * configuration key for its own hedge).
 */
final class BriefingHonestyFilter {

    /**
     * Pill label that replaces the default {@code STAND_DOWN} label when weather triage really
     * stood the region down ({@link CoverageReason#STOOD_DOWN}).
     */
    static final String VERDICT_LABEL = "Too unsettled to forecast";

    /**
     * Pill label for a weather stand-down whose slots were mostly stood down for visibility
     * (valley-mist-plan M1b, owner decision Q6): fog is forecast, not "unsettled" weather.
     */
    static final String FOG_VERDICT_LABEL = "Fog forecast";

    /** Single-line summary for a visibility stand-down. */
    static final String FOG_SUMMARY =
            "No per-location forecast — fog or very low visibility forecast";

    /** Long-form gloss for a visibility stand-down. */
    static final String FOG_GLOSS_DETAIL =
            "Visibility across most of this region is forecast to be too low to evaluate "
            + "confidently, so no per-location forecast was produced. The picture may change "
            + "closer to the date.";

    /** Single-line summary that replaces "Clear at N of M locations" when the override fires. */
    static final String REPLACEMENT_SUMMARY =
            "No per-location forecast — conditions too unsettled to evaluate";

    /** Long-form gloss that replaces the briefing's prose when the override fires. */
    static final String REPLACEMENT_GLOSS_DETAIL =
            "Conditions across this region were classified as too unsettled to evaluate "
            + "confidently at this horizon. No per-location forecast was produced. "
            + "The picture may firm up closer to the date — or it may remain unsettled.";

    /** Label for a window on a travel day; the client's own {@code AWAY_STATE_LABEL}. */
    static final String AWAY_LABEL = "Not forecast";

    /** Summary for a window on a travel day. */
    static final String AWAY_SUMMARY = "Marked as a travel day — no forecast was made";

    /** Gloss detail for a window on a travel day. */
    static final String AWAY_GLOSS_DETAIL =
            "This day is marked as a travel day, so no per-location forecast was made for it.";

    /** Label for a window the scoring policy never attempts. */
    static final String NOT_ATTEMPTED_LABEL = "Not scored at this range";

    /** Summary for a window the scoring policy never attempts. */
    static final String NOT_ATTEMPTED_SUMMARY =
            "Not scored this far ahead — forecasts firm up nearer the day";

    /** Gloss detail for a window the scoring policy never attempts. */
    static final String NOT_ATTEMPTED_GLOSS_DETAIL =
            "Forecasts this far ahead are only made when the weather pattern is stable enough, and not at "
            + "all from four days out, so there is no per-location forecast for this window yet.";

    /** Label for a window whose forecast run did not complete. */
    static final String FAILED_LABEL = "Forecast unavailable";

    /** Summary for a window whose forecast run did not complete. */
    static final String FAILED_SUMMARY =
            "No per-location forecast — the forecast run didn't complete";

    /** Gloss detail for a window whose forecast run did not complete. */
    static final String FAILED_GLOSS_DETAIL =
            "The forecast run for this window didn't complete, so there is no per-location "
            + "forecast. It may be tried again at the next run.";

    /** Label for a window whose record cannot say why it has no forecast. */
    static final String UNKNOWN_LABEL = "Not scored";

    /** Summary for a window whose record cannot say why it has no forecast. */
    static final String UNKNOWN_SUMMARY = "No per-location forecast — the record does not say why";

    /** Gloss detail for a window whose record cannot say why it has no forecast. */
    static final String UNKNOWN_GLOSS_DETAIL =
            "No per-location forecast was produced for this region at this window, and the "
            + "pipeline's own record of the cycle does not say why. Nothing here is a judgement "
            + "about the weather.";

    private BriefingHonestyFilter() {
    }

    /**
     * Backward-compatible overload (ratio {@code 0.0}): only the zero-coverage rewrite fires.
     * Retained for callers and tests that only exercise the zero-coverage guard.
     *
     * @param response the API-bound briefing response (may be {@code null})
     * @return transformed response, or {@code null} if input was {@code null}
     */
    static DailyBriefingResponse apply(DailyBriefingResponse response) {
        return apply(response, 0.0);
    }

    /**
     * Returns a transformed copy of {@code response}. Zero-coverage regions are
     * fully rewritten; every other region is reused unchanged. The
     * day/event/region hierarchy is otherwise structurally identical to the input.
     *
     * <p>Returns {@code null} when the input is {@code null}.
     *
     * @param response         the API-bound briefing response (may be {@code null})
     * @param minCoverageRatio retained for call-site stability; no longer has any effect
     * @return transformed response, or {@code null} if input was {@code null}
     */
    static DailyBriefingResponse apply(DailyBriefingResponse response, double minCoverageRatio) {
        return apply(response, minCoverageRatio, null, null);
    }

    /**
     * As {@link #apply(DailyBriefingResponse, double)}, but a zero-coverage region is worded by
     * <em>why</em> it has no forecast: {@link CoverageReasons#of} folds the pipeline's own latest
     * disposition per slot and the horizon into a {@link CoverageReason}, and
     * {@link #fullRewrite} picks the wording and verdict from it. Every reason still empties the
     * slots, so the region stays in {@code blanked} and a Best Bet on it is still withdrawn. The
     * overloads without a lookup bypass the fold: every region reads {@code UNKNOWN}, which is
     * worded {@code AWAITING} ("Not scored"), never as a weather stand-down.
     *
     * @param response         the API-bound briefing response (may be {@code null})
     * @param minCoverageRatio retained for call-site stability; no longer has any effect
     * @param latestBySlot     latest non-cached disposition per {@code location|date|type}; null
     *                         bypasses the fold
     * @param clock            the clock {@code daysAhead} is measured on; null bypasses the fold
     * @return transformed response, or {@code null} if input was {@code null}
     */
    static DailyBriefingResponse apply(DailyBriefingResponse response, double minCoverageRatio,
            Map<String, LatestDisposition> latestBySlot, Clock clock) {
        if (response == null) {
            return response;
        }
        Reasons reasons = latestBySlot == null || clock == null ? null
                : new Reasons(latestBySlot, clock);
        Set<String> blanked = new HashSet<>();
        List<BriefingDay> rewrittenDays = response.days().stream()
                .map(day -> rewriteDay(day, minCoverageRatio, blanked, reasons))
                .toList();
        List<BestBet> keptBets = withdrawUnsupportedBets(response.bestBets(), blanked);
        // Flagged rather than inferred: a one-pick list means "the advisor withheld a runner-up"
        // in every other case, and the banner says so in prose. Only this method knows the
        // difference, and only on this serve.
        Boolean withdrawn = response.bestBets() != null
                && keptBets.size() != response.bestBets().size() ? Boolean.TRUE : null;
        return response.withDays(rewrittenDays).withBestBets(keptBets, withdrawn);
    }

    /** The pipeline's record of what it did with each slot, and the clock horizons are read on. */
    private record Reasons(Map<String, LatestDisposition> latestBySlot, Clock clock) {
    }

    private static BriefingDay rewriteDay(BriefingDay day, double minCoverageRatio,
            Set<String> blanked, Reasons reasons) {
        List<BriefingEventSummary> rewrittenEvents = day.eventSummaries().stream()
                .map(es -> rewriteEvent(es, minCoverageRatio, day.date(), blanked, reasons))
                .toList();
        return new BriefingDay(day.date(), rewrittenEvents);
    }

    private static BriefingEventSummary rewriteEvent(BriefingEventSummary es,
            double minCoverageRatio, LocalDate date, Set<String> blanked, Reasons reasons) {
        List<BriefingRegion> rewrittenRegions = es.regions().stream()
                .map(region -> {
                    BriefingRegion rewritten = rewriteRegionByCoverage(region, minCoverageRatio,
                            date, es.targetType(), reasons);
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
     * safe and keeps rank 1. ⚠️ **This is no longer this method's own rule** — round 11 generalised
     * it onto {@link BestBetRanker#afterRemoval}, shared with {@link
     * BestBetRanker#dropUnevaluatedPicks}, {@link BestBetRanker#dropIneligiblePicks} and {@code
     * BestBetFallbackService}'s current-briefing eligibility re-check, after a Codex review found
     * the fallback re-check promoting an orphaned rank 2 exactly this method was written to refuse.
     * This method's own behaviour is unchanged; only where the rule lives moved.
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
        return BestBetRanker.afterRemoval(bets, kept);
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
     *   <li>otherwise → returned unchanged.</li>
     * </ul>
     */
    private static BriefingRegion rewriteRegionByCoverage(BriefingRegion r,
            double minCoverageRatio, LocalDate date, TargetType targetType, Reasons reasons) {
        // Canopy slots can never carry a Claude rating — they are deliberately excluded from the
        // sky batch — so they must not count toward the coverage this filter measures. Without
        // this, a region of woods reads as zero-coverage and gets the failure message below, which
        // would be a lie twice over: nothing failed, and a per-location forecast DOES exist (the
        // deterministic woodland verdict). It would also erase those verdicts every single day.
        //
        // This filter is failure-defence — its whole premise is "we expected Claude coverage and
        // did not get it". A canopy slot never expected any, so it is dropped from the count.
        // Same predicate as the confidence denominator (BriefingRegionEvaluationRollup.rosterOf):
        // a slot counts when it COULD carry a rating, not merely when it is not canopy. An
        // in-season canopy bluebell site is scored by the bluebell prompt, so excluding it would
        // put it in the numerator (scoredLocationCount) and not the denominator — a ratio above 1.
        List<BriefingSlot> scoreableSlots = r.slots().stream()
                .filter(BriefingSlot::couldCarryRating)
                .toList();
        long scoreable = scoreableSlots.size();
        if (scoreable == 0) {
            return r;
        }
        if (r.scoredLocationCount() == 0) {
            CoverageReason reason = reasons == null ? CoverageReason.UNKNOWN
                    : CoverageReasons.of(scoreableSlots, date, targetType,
                            ForecastHorizon.daysAhead(date, reasons.clock()),
                            reasons.latestBySlot());
            boolean fog = reason == CoverageReason.STOOD_DOWN && reasons != null
                    && CoverageReasons.fogMajority(scoreableSlots, date, targetType,
                            reasons.latestBySlot());
            return fullRewrite(r, reason, fog);
        }
        return r;
    }

    /** The verdict and three strings one coverage reason writes onto a blanked region. */
    private record Wording(DisplayVerdict verdict, String label, String summary, String detail) {
    }

    /**
     * Blanks a zero-coverage region, wording it by {@code reason}. Every reason empties the slots and
     * nulls exactly the fields it always did; only the verdict, label, summary and gloss detail
     * differ. Only {@code STOOD_DOWN} keeps "too unsettled" and {@code STAND_DOWN}, which is true only
     * of weather triage (worded "Fog forecast" instead when {@code fog}: a majority of the
     * stand-downs were weather triage's visibility rule); every other reason, {@code UNKNOWN} (the
     * record cannot say) included, is neutral ({@code AWAITING}) and makes no claim about the
     * weather.
     *
     * @param r      the zero-coverage region
     * @param reason why it has no forecast
     * @param fog    whether a {@code STOOD_DOWN} region's stand-downs were mostly visibility
     * @return the blanked region
     */
    private static BriefingRegion fullRewrite(BriefingRegion r, CoverageReason reason,
            boolean fog) {
        Wording w = switch (reason) {
            case AWAY -> new Wording(DisplayVerdict.AWAITING, AWAY_LABEL, AWAY_SUMMARY,
                    AWAY_GLOSS_DETAIL);
            case NOT_ATTEMPTED -> new Wording(DisplayVerdict.AWAITING, NOT_ATTEMPTED_LABEL,
                    NOT_ATTEMPTED_SUMMARY, NOT_ATTEMPTED_GLOSS_DETAIL);
            case FAILED -> new Wording(DisplayVerdict.AWAITING, FAILED_LABEL, FAILED_SUMMARY,
                    FAILED_GLOSS_DETAIL);
            case UNKNOWN -> new Wording(DisplayVerdict.AWAITING, UNKNOWN_LABEL, UNKNOWN_SUMMARY,
                    UNKNOWN_GLOSS_DETAIL);
            case STOOD_DOWN -> fog
                    ? new Wording(DisplayVerdict.STAND_DOWN, FOG_VERDICT_LABEL, FOG_SUMMARY,
                            FOG_GLOSS_DETAIL)
                    : new Wording(DisplayVerdict.STAND_DOWN, VERDICT_LABEL,
                            REPLACEMENT_SUMMARY, REPLACEMENT_GLOSS_DETAIL);
        };
        DisplayVerdict verdict = w.verdict();
        String label = w.label();
        String summary = w.summary();
        String detail = w.detail();
        return new BriefingRegion(
                r.regionName(),
                r.verdict(),
                summary,
                r.tideHighlights(),
                List.of(),
                r.regionTemperatureCelsius(),
                r.regionApparentTemperatureCelsius(),
                r.regionWindSpeedMs(),
                r.regionWeatherCode(),
                null,
                detail,
                verdict,
                0,
                label,
                null,
                null,
                null,
                null,
                null,
                null,
                reason);
    }
}
