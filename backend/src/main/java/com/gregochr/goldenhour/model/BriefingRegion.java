package com.gregochr.goldenhour.model;

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
 *                                    Claude rating average when scored locations exist, or from
 *                                    the triage {@code verdict} as fallback; never null
 * @param scoredLocationCount         number of locations in this region whose Claude rating was
 *                                    used to derive {@code displayVerdict} (0 means no Claude
 *                                    scores yet — the triage fallback produced the verdict)
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
        Confidence confidence) {

    public BriefingRegion {
        tideHighlights = List.copyOf(tideHighlights);
        slots = List.copyOf(slots);
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
                scoredLocationCount, verdictLabel, true, confidence);
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
                scoredLocationCount, verdictLabel, lightlyEvaluated, newConfidence);
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
                scoredLocationCount, verdictLabel, lightlyEvaluated, confidence);
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
