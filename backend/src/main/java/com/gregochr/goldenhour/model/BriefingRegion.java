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
 * @param bestRating                   the highest Claude rating across this region's
 *                                     <em>voting</em> slots for this date and event — the
 *                                     {@code best N★} figure the heat field's region rail prints.
 *                                     {@code null} when nothing that votes is scored.
 *
 *                                     <p><b>A spot signal, never a verdict</b> — the rule
 *                                     {@link BriefingWindow#bestRating} states one level up. It is
 *                                     one location's score while {@code meanRating} is the
 *                                     region's average, so {@code Poor · best 4★} is two true
 *                                     statements and the client must label it as one. There are
 *                                     now THREE {@code bestRating} fields on the wire — this one,
 *                                     {@link BriefingWindow#bestRating} and
 *                                     {@code CloseToHomeResponse}'s — over three populations at
 *                                     three nesting levels. They are not interchangeable.
 *
 *                                     <p><b>The canopy fallback is per REGION, and in one case
 *                                     that differs from the window's answer.</b>
 *                                     {@link BriefingSlot#votingSlots} falls back to canopy slots
 *                                     when <em>this region</em> has no sky slot, while
 *                                     {@code PlanWindowProjector.canopyCounts} falls back only
 *                                     when the <em>whole window</em> is canopy. So an all-woodland
 *                                     region inside a window that also holds sky regions reports
 *                                     its wood here (say 5) while the window header reports the
 *                                     sky's best (say 4) — and {@code PlanWindowProjector.rank}
 *                                     drops that region from the window's own list entirely. This
 *                                     is deliberate: the alternative is a {@code bestRating} that
 *                                     disagrees with the {@code meanRating} and
 *                                     {@code displayVerdict} printed beside it on this same
 *                                     record, both of which are already per-region. A surface
 *                                     showing both levels at once must not present them as one
 *                                     number.
 *
 *                                     <p><b>Served rather than derived in the browser.</b> A
 *                                     client-side max over {@code slots} would re-create the
 *                                     aggregation class Phase 3 of the verdict consolidation moved
 *                                     server-side — and would do it without the canopy rule, so a
 *                                     rated wood would supply the number for a sky region. Taken
 *                                     from the same {@code BriefingRatingStats.Stats} as
 *                                     {@code meanRating} and {@code displayVerdict}, over
 *                                     {@link BriefingSlot#votingSlots} (non-canopy, with the
 *                                     all-canopy fallback), so the three cannot disagree about
 *                                     which population they describe and the best can never sit
 *                                     below the mean. Nullable, {@code NON_NULL}, no migration —
 *                                     the {@code confidence} precedent; legacy cached payloads
 *                                     deserialise to null. ⚠️ That precedent is one-directional.
 *                                     Rolling BACK past this field makes the Jackson 2 mapper in
 *                                     {@code AppConfig} (default
 *                                     {@code FAIL_ON_UNKNOWN_PROPERTIES}) throw on every
 *                                     {@code daily_briefing_cache} row written since deploy, and
 *                                     {@code BriefingService.loadPersistedBriefing} swallows the
 *                                     failure — so the Plan tab stays empty until the next
 *                                     scheduled refresh. True of every additive field on this
 *                                     record rather than of this one; the fix is an
 *                                     {@code ignoreUnknown} setting, which is payload-wide and
 *                                     not this phase's to make.
 * @param meanRatingDelta              how far {@code meanRating} has moved since the previous
 *                                     briefing build — the Plan strip's movement chip. Positive is
 *                                     an improvement, 1dp, {@code null} when there is nothing to
 *                                     compare against (no previous build on record, this region
 *                                     absent from it, or either side unscored). A measured
 *                                     {@code 0.0} is a real answer and is NOT nulled: the strip
 *                                     marks "did not move" differently from "we do not know".
 *
 *                                     <p><b>The current side is the SERVE-time mean, so this
 *                                     includes post-build drift.</b> {@code enrichWithCachedScores}
 *                                     re-derives {@code meanRating} on every request, while the
 *                                     comparand was written once at the end of the previous
 *                                     {@code refreshBriefing}. So a batch that re-scored this
 *                                     region after that build shows up here. That is the honest
 *                                     quantity — it is what the reader's screen moved by — but it
 *                                     is not literally "the change the last build made", and a
 *                                     surface must not word it as one. The strip says "since the
 *                                     last forecast run", which is true of both readings.
 *
 *                                     <p>⚠️ <b>It is a weather signal that a ROSTER change also
 *                                     moves.</b> Both sides are means over each build's own voting
 *                                     population, so adding, disabling (including the automatic
 *                                     disable after three failures) or retyping a location shifts
 *                                     the number with no weather behind it — and the partial-failure
 *                                     branch of {@code refreshBriefing} deliberately snapshots a
 *                                     degraded roster, so the recovery of those locations reports as
 *                                     movement on the next build. {@code briefing_region_snapshot
 *                                     .voting_count} records the population precisely so a later
 *                                     reader can detect this; nothing consumes it yet, and no
 *                                     surface qualifies the chip for it. Suppressing on any change
 *                                     of population was considered and rejected — a batch scoring
 *                                     one more location changes it too, and that IS the movement.
 *
 *                                     <p>Attached at serve time, AFTER {@code BriefingHonestyFilter}
 *                                     — a blanked region carries a null {@code meanRating} and so
 *                                     can never acquire a delta. Nullable, {@code NON_NULL}, no
 *                                     migration: the {@code confidence} precedent, and legacy
 *                                     cached payloads deserialise to null.
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
        @JsonInclude(JsonInclude.Include.NON_NULL) Double meanRating,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer bestRating,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double meanRatingDelta) {

    public BriefingRegion {
        tideHighlights = List.copyOf(tideHighlights);
        slots = List.copyOf(slots);
    }

    /**
     * Backwards-compatible convenience constructor matching the pre-{@code meanRating} canonical
     * signature. Defaults both {@code meanRating} and {@code bestRating} to {@code null} (nothing
     * scored) so every existing 16-arg call site keeps compiling; the enrichment path attaches
     * derived values via {@link #withMeanRating} and {@link #withBestRating}, and the honesty
     * filter's zero-coverage rewrite deliberately leaves both null — a blanked region has no
     * rating of any kind to report.
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
                confidence, null, null, null);
    }

    /**
     * Backwards-compatible convenience constructor matching the pre-{@code bestRating} canonical
     * signature. Defaults {@code bestRating} to {@code null} (nothing that votes is scored), the
     * same shape as the {@code meanRating} and {@code confidence} constructors above and for the
     * same reason: the enrichment path attaches the derived value via {@link #withBestRating}, and
     * the other construction sites — the hierarchy builder, and the honesty filter's zero-coverage
     * rewrite — have no rating rollup to hand and must not invent one. (The gloss pass is NOT one
     * of them: it goes through {@link #withGloss}, which carries every field.)
     *
     * <p>Its real callers are two test helpers that rebuild a whole existing region positionally
     * — exactly the trap {@link #withBestRating} names — so they append
     * {@code .withBestRating(r.bestRating())} rather than rely on this default.
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
     * @param meanRating                       the 1dp voting mean, or null
     */
    public BriefingRegion(String regionName, Verdict verdict, String summary,
            List<String> tideHighlights, List<BriefingSlot> slots,
            Double regionTemperatureCelsius, Double regionApparentTemperatureCelsius,
            Double regionWindSpeedMs, Integer regionWeatherCode,
            String glossHeadline, String glossDetail,
            DisplayVerdict displayVerdict, int scoredLocationCount,
            String verdictLabel, boolean lightlyEvaluated, Confidence confidence,
            Double meanRating) {
        this(regionName, verdict, summary, tideHighlights, slots,
                regionTemperatureCelsius, regionApparentTemperatureCelsius,
                regionWindSpeedMs, regionWeatherCode, glossHeadline, glossDetail,
                displayVerdict, scoredLocationCount, verdictLabel, lightlyEvaluated,
                confidence, meanRating, null, null);
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
                scoredLocationCount, verdictLabel, lightlyEvaluated, confidence, newMeanRating,
                bestRating, meanRatingDelta);
    }

    /**
     * Returns a copy of this region carrying the given best rating.
     *
     * <p>A wither for the same reason as {@link #withMeanRating}: the enrichment path computes it
     * from statistics it already holds, and rebuilding the record positionally there is how a
     * later-added component gets silently defaulted away.
     *
     * @param newBestRating the highest rating across the voting slots, or null when none is scored
     * @return a copy carrying the best rating
     */
    public BriefingRegion withBestRating(Integer newBestRating) {
        return new BriefingRegion(regionName, verdict, summary, tideHighlights, slots,
                regionTemperatureCelsius, regionApparentTemperatureCelsius, regionWindSpeedMs,
                regionWeatherCode, glossHeadline, glossDetail, displayVerdict,
                scoredLocationCount, verdictLabel, lightlyEvaluated, confidence, meanRating,
                newBestRating, meanRatingDelta);
    }

    /**
     * Returns a copy of this region carrying the given movement figure.
     *
     * <p>A wither for the same reason as {@link #withMeanRating}, and used from the same place —
     * except that this one runs at serve time only, and <em>after</em> the honesty filter, so a
     * blanked region can never acquire one.
     *
     * @param newMeanRatingDelta the 1dp change since the previous build, or null when unknown
     * @return a copy carrying the delta
     */
    public BriefingRegion withMeanRatingDelta(Double newMeanRatingDelta) {
        return new BriefingRegion(regionName, verdict, summary, tideHighlights, slots,
                regionTemperatureCelsius, regionApparentTemperatureCelsius, regionWindSpeedMs,
                regionWeatherCode, glossHeadline, glossDetail, displayVerdict,
                scoredLocationCount, verdictLabel, lightlyEvaluated, confidence, meanRating,
                bestRating, newMeanRatingDelta);
    }

    /**
     * Backwards-compatible convenience constructor matching the pre-{@code meanRatingDelta}
     * canonical signature. Defaults {@code meanRatingDelta} to {@code null} (nothing to compare
     * against), the same shape and the same reason as the three constructors above it: movement is
     * attached on the serve path via {@link #withMeanRatingDelta}, and no construction site that
     * predates this field has a previous build to hand.
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
     * @param meanRating                       the 1dp voting mean, or null
     * @param bestRating                       the voting max, or null
     */
    public BriefingRegion(String regionName, Verdict verdict, String summary,
            List<String> tideHighlights, List<BriefingSlot> slots,
            Double regionTemperatureCelsius, Double regionApparentTemperatureCelsius,
            Double regionWindSpeedMs, Integer regionWeatherCode,
            String glossHeadline, String glossDetail,
            DisplayVerdict displayVerdict, int scoredLocationCount,
            String verdictLabel, boolean lightlyEvaluated, Confidence confidence,
            Double meanRating, Integer bestRating) {
        this(regionName, verdict, summary, tideHighlights, slots,
                regionTemperatureCelsius, regionApparentTemperatureCelsius,
                regionWindSpeedMs, regionWeatherCode, glossHeadline, glossDetail,
                displayVerdict, scoredLocationCount, verdictLabel, lightlyEvaluated,
                confidence, meanRating, bestRating, null);
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
                scoredLocationCount, verdictLabel, true, confidence, meanRating, bestRating,
                meanRatingDelta);
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
                scoredLocationCount, verdictLabel, lightlyEvaluated, newConfidence, meanRating,
                bestRating, meanRatingDelta);
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
                scoredLocationCount, verdictLabel, lightlyEvaluated, confidence, meanRating,
                bestRating, meanRatingDelta);
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
