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
        int scoredLocationCount) {

    public BriefingRegion {
        tideHighlights = List.copyOf(tideHighlights);
        slots = List.copyOf(slots);
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
                DisplayVerdict.resolve(null, verdict), 0);
    }
}
