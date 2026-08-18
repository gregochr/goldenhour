package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Region-level rollup in the daily briefing.
 *
 * @param regionName                   display name of the geographic region
 * @param verdict                      rolled-up verdict across all slots in this region
 * @param summary                      one-line human-readable summary of conditions
 * @param tideHighlights               count-based tide summaries (e.g. "Spring Tide at 3 coastal spots")
 * @param slots                        individual location assessments within this region
 * @param regionTemperatureCelsius     representative temperature for the region in °C (nullable)
 * @param regionApparentTemperatureCelsius feels-like temperature for the region in °C (nullable)
 * @param regionWindSpeedMs            representative wind speed in m/s (nullable)
 * @param regionWeatherCode            WMO weather code for the region (nullable)
 * @param glossHeadline               Claude-generated short headline (~7 words, nullable, GO/MARGINAL only)
 * @param glossDetail                 Claude-generated 2-3 sentence explanation (nullable, GO/MARGINAL only)
 * @param displayVerdict              unified colour/label signal for the region, derived from the
 *                                    mean Claude rating across its <em>voting</em> slots
 *                                    ({@link BriefingSlot#votingSlots} — non-canopy, falling back
 *                                    to all of them for an all-canopy region), or from the triage
 *                                    {@code verdict} when none of those is scored; never null. A
 *                                    woodland verdict runs on inverted polarity, so a rated wood
 *                                    does not set a sky band
 * @param scoredLocationCount         how many locations in this region carry a valid Claude
 *                                    rating — a <b>coverage</b> figure over every slot, canopy
 *                                    included, and deliberately NOT the population
 *                                    {@code displayVerdict} was derived from. The two differ for a
 *                                    wood-bearing region: a scored wood counts as evaluated (it is)
 *                                    and still does not vote. {@code BriefingHonestyFilter} tests
 *                                    this against its own canopy-inclusive scoreable count, and
 *                                    both flag arms render "N of M evaluated" against the whole
 *                                    slot list, so narrowing it would under-report a scored wood
 *                                    and could blank a region whose only evaluated location is one.
 *                                    0 means no Claude scores at all — the triage fallback produced
 *                                    the verdict
 * @param verdictLabel                 optional override for the pill label shown next to
 *                                     {@code displayVerdict}. {@code null} means "use the
 *                                     frontend's default label for the enum value". Currently
 *                                     populated only by the Gate 2 honesty override on the API
 *                                     read path when {@code scoredLocationCount == 0}.
 * @param lightlyEvaluated             {@code true} when a non-zero but low fraction of the
 *                                     region's locations were Claude-scored (the coverage ratio
 *                                     fell below the configured threshold). Set by the honesty
 *                                     filter on the API read path so the frontend can frame the
 *                                     region as covering only the evaluated spots rather than the
 *                                     whole roster. Always {@code false} on the internal
 *                                     (untransformed) path and on well-covered regions.
 * @param confidence                   how reliable this region's verdict is, as a quiet channel
 *                                     layered alongside (never replacing) the sky-quality signal.
 *                                     Derived server-side from forecast horizon + rating spread by
 *                                     {@code ConfidenceDeriver} on the enrichment path. {@code null}
 *                                     means unknown (no scored locations) — the frontend reads that
 *                                     as provisional rather than falsely confident. Nullable, and
 *                                     absent from legacy cached payloads (deserialises to null).
 * @param meanRating                   the mean Claude rating across this region's scored locations
 *                                     for this date and event, rounded to 1dp — the number the grid
 *                                     cell prints as its star. {@code null} when nothing here is
 *                                     scored, which is a different statement from a low mean and
 *                                     must not render as one.
 *
 *                                     <p><b>It is the same computation as {@code displayVerdict},
 *                                     and that is the point.</b> Both come from one
 *                                     {@code BriefingRatingStats.Stats} in
 *                                     {@code BriefingService.enrichWithCachedScores}, so a cell's
 *                                     star and its verdict word can no longer disagree. The grid
 *                                     used to derive the star in the browser from a second endpoint
 *                                     ({@code /api/briefing/evaluate/scores}) joined on a region-name
 *                                     prefix, with a silent fallback — two fetches, two cache
 *                                     lifetimes, one cell. See
 *                                     {@code docs/engineering/plan-verdict-consolidation-plan.md}
 *                                     §1 D2.
 *
 *                                     <p>Equal by construction to {@code BriefingWindow.Pick
 *                                     .averageRating} for the same region, which is derived from the
 *                                     same statistics over the same slots. Nullable, and absent from
 *                                     legacy cached payloads (deserialises to null); the serve path
 *                                     re-enriches, so a served payload always carries a fresh one.
 */
public record BriefingRegion(
        String regionName,
        Verdict verdict,
        String summary,
        List<String> tideHighlights,
        List<BriefingSlot> slots,
        Double regionTemperatureCelsius,
        Double regionApparentTemperatureCelsius,
        Double regionWindSpeedMs,
        Integer regionWeatherCode,
        String glossHeadline,
        String glossDetail,
        DisplayVerdict displayVerdict,
        int scoredLocationCount,
        String verdictLabel,
        boolean lightlyEvaluated,
        Confidence confidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double meanRating) {

    public BriefingRegion {
        tideHighlights = List.copyOf(tideHighlights);
        slots = List.copyOf(slots);
    }

    /**
     * Backwards-compatible convenience constructor matching the pre-{@code meanRating} canonical
     * signature. Defaults {@code meanRating} to {@code null} (nothing scored) so every existing
     * 16-arg call site keeps compiling; the enrichment path attaches a derived value via
     * {@link #withMeanRating}, and the honesty filter's zero-coverage rewrite deliberately leaves it
     * null — a blanked region has no mean to report.
     *
     * @param regionName                       display name
     * @param verdict                          triage verdict
     * @param summary                          one-line summary
     * @param tideHighlights                   tide summary lines
     * @param slots                            per-location assessments
     * @param regionTemperatureCelsius         representative temperature
     * @param regionApparentTemperatureCelsius feels-like temperature
     * @param regionWindSpeedMs                representative wind speed
     * @param regionWeatherCode                WMO weather code
     * @param glossHeadline                    Claude gloss headline
     * @param glossDetail                      Claude gloss detail
     * @param displayVerdict                   unified colour/label signal
     * @param scoredLocationCount              how many locations contributed a rating
     * @param verdictLabel                     pill-label override
     * @param lightlyEvaluated                 thin-coverage flag
     * @param confidence                       derived confidence, or null
     */
    public BriefingRegion(String regionName, Verdict verdict, String summary,
            List<String> tideHighlights, List<BriefingSlot> slots,
            Double regionTemperatureCelsius, Double regionApparentTemperatureCelsius,
            Double regionWindSpeedMs, Integer regionWeatherCode,
            String glossHeadline, String glossDetail,
            DisplayVerdict displayVerdict, int scoredLocationCount,
            String verdictLabel, boolean lightlyEvaluated, Confidence confidence) {
        this(regionName, verdict, summary, tideHighlights, slots,
                regionTemperatureCelsius, regionApparentTemperatureCelsius,
                regionWindSpeedMs, regionWeatherCode, glossHeadline, glossDetail,
                displayVerdict, scoredLocationCount, verdictLabel, lightlyEvaluated,
                confidence, null);
    }

    /**
     * Returns a copy of this region carrying the given mean rating.
     *
     * <p>A wither for the same reason as {@link #withConfidence}: the enrichment path computes it
     * from statistics it already holds, and rebuilding the record positionally there is how a
     * later-added component gets silently defaulted away.
     *
     * @param newMeanRating the 1dp mean across scored locations, or null when none is scored
     * @return a copy carrying the mean
     */
    public BriefingRegion withMeanRating(Double newMeanRating) {
        return new BriefingRegion(regionName, verdict, summary, tideHighlights, slots,
                regionTemperatureCelsius, regionApparentTemperatureCelsius, regionWindSpeedMs,
                regionWeatherCode, glossHeadline, glossDetail, displayVerdict,
                scoredLocationCount, verdictLabel, lightlyEvaluated, confidence, newMeanRating);
    }

    /**
     * Returns a copy of this region flagged as lightly evaluated. All other
     * fields (including slots, gloss, and the real triage summary) are preserved
     * — the flag is purely a presentation hint for the read path.
     *
     * @return a copy with {@code lightlyEvaluated == true}
     */
    public BriefingRegion withLightlyEvaluated() {
        return new BriefingRegion(regionName, verdict, summary, tideHighlights, slots,
                regionTemperatureCelsius, regionApparentTemperatureCelsius, regionWindSpeedMs,
                regionWeatherCode, glossHeadline, glossDetail, displayVerdict,
                scoredLocationCount, verdictLabel, true, confidence, meanRating);
    }

    /**
     * Returns a copy of this region carrying the given derived confidence. Used by
     * the enrichment path to attach the {@code ConfidenceDeriver} result after the
     * region has been (re)built with its fresh verdict, without reconstructing it.
     *
     * @param newConfidence the derived confidence (may be {@code null} for unknown)
     * @return a copy with {@code confidence == newConfidence}
     */
    public BriefingRegion withConfidence(Confidence newConfidence) {
        return new BriefingRegion(regionName, verdict, summary, tideHighlights, slots,
                regionTemperatureCelsius, regionApparentTemperatureCelsius, regionWindSpeedMs,
                regionWeatherCode, glossHeadline, glossDetail, displayVerdict,
                scoredLocationCount, verdictLabel, lightlyEvaluated, newConfidence, meanRating);
    }

    /**
     * Returns a copy of this region with new Claude gloss prose, preserving every other field.
     * Used by the gloss-generation pass to attach the headline/detail without reconstructing the
     * region — a plain constructor call would silently drop later-added fields (this is exactly
     * how the confidence channel was being wiped on the build path before this wither existed).
     *
     * @param newGlossHeadline the short gloss headline (nullable)
     * @param newGlossDetail   the 2-3 sentence gloss detail (nullable)
     * @return a copy carrying the new gloss, all other fields unchanged
     */
    public BriefingRegion withGloss(String newGlossHeadline, String newGlossDetail) {
        return new BriefingRegion(regionName, verdict, summary, tideHighlights, slots,
                regionTemperatureCelsius, regionApparentTemperatureCelsius, regionWindSpeedMs,
                regionWeatherCode, newGlossHeadline, newGlossDetail, displayVerdict,
                scoredLocationCount, verdictLabel, lightlyEvaluated, confidence, meanRating);
    }

    /**
     * Backwards-compatible convenience constructor matching the pre-confidence
     * canonical signature. Defaults {@code confidence} to {@code null} (unknown)
     * so the many existing 15-arg call sites — including {@code withLightlyEvaluated}
     * callers and the honesty filter's full rewrite — keep compiling unchanged; the
     * enrichment path attaches a derived value via {@link #withConfidence}.
     */
    public BriefingRegion(String regionName, Verdict verdict, String summary,
            List<String> tideHighlights, List<BriefingSlot> slots,
            Double regionTemperatureCelsius, Double regionApparentTemperatureCelsius,
            Double regionWindSpeedMs, Integer regionWeatherCode,
            String glossHeadline, String glossDetail,
            DisplayVerdict displayVerdict, int scoredLocationCount,
            String verdictLabel, boolean lightlyEvaluated) {
        this(regionName, verdict, summary, tideHighlights, slots,
                regionTemperatureCelsius, regionApparentTemperatureCelsius,
                regionWindSpeedMs, regionWeatherCode, glossHeadline, glossDetail,
                displayVerdict, scoredLocationCount, verdictLabel, lightlyEvaluated, null);
    }

    /**
     * Convenience constructor for enrichment paths that have computed the
     * Claude-rating rollup but do not need to override the pill label.
     * Defaults {@code verdictLabel} to {@code null} (frontend uses the default
     * label for the enum value).
     */
    public BriefingRegion(String regionName, Verdict verdict, String summary,
            List<String> tideHighlights, List<BriefingSlot> slots,
            Double regionTemperatureCelsius, Double regionApparentTemperatureCelsius,
            Double regionWindSpeedMs, Integer regionWeatherCode,
            String glossHeadline, String glossDetail,
            DisplayVerdict displayVerdict, int scoredLocationCount) {
        this(regionName, verdict, summary, tideHighlights, slots,
                regionTemperatureCelsius, regionApparentTemperatureCelsius,
                regionWindSpeedMs, regionWeatherCode, glossHeadline, glossDetail,
                displayVerdict, scoredLocationCount, null, false);
    }

    /**
     * Convenience constructor for callers that have not yet computed the
     * Claude-rating rollup. Defaults {@code displayVerdict} to the triage
     * fallback mapping of {@code verdict} and {@code scoredLocationCount} to
     * zero. Production enrichment paths overwrite both once the Claude scores
     * are merged onto the slots.
     */
    public BriefingRegion(String regionName, Verdict verdict, String summary,
            List<String> tideHighlights, List<BriefingSlot> slots,
            Double regionTemperatureCelsius, Double regionApparentTemperatureCelsius,
            Double regionWindSpeedMs, Integer regionWeatherCode,
            String glossHeadline, String glossDetail) {
        this(regionName, verdict, summary, tideHighlights, slots,
                regionTemperatureCelsius, regionApparentTemperatureCelsius,
                regionWindSpeedMs, regionWeatherCode, glossHeadline, glossDetail,
                DisplayVerdict.resolve(null, verdict), 0, null, false);
    }
}
