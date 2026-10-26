package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Structured data powering the expandable section of a {@link HotTopic} pill.
 *
 * <p>Each topic type populates a different subset of fields — all others are null.
 * {@code @JsonInclude(NON_NULL)} keeps the JSON compact.
 *
 * <p>All types use {@code regionGroups} for consistency: aurora regions contain
 * gloss + verdict + location weather; bluebell regions contain scores + exposure;
 * tide regions contain coastal location metadata.
 *
 * @param regionGroups     locations grouped by region (all types use this)
 * @param bluebellMetrics  pill-level bluebell metrics (non-null for BLUEBELL only)
 * @param tideMetrics      pill-level tide metrics (non-null for KING_TIDE/SPRING_TIDE only)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExpandedHotTopicDetail(
        List<RegionGroup> regionGroups,
        BluebellMetrics bluebellMetrics,
        TideMetrics tideMetrics) {

    /**
     * Returns this detail as a LITE reader may see it: the tide metrics narrowed to
     * {@link TideMetrics#forLite()}, everything else unchanged.
     *
     * @return the shaped copy, or this when there are no tide metrics
     */
    public ExpandedHotTopicDetail forLite() {
        return tideMetrics == null ? this
                : new ExpandedHotTopicDetail(regionGroups, bluebellMetrics, tideMetrics.forLite());
    }

    /**
     * A group of locations within a single region.
     *
     * @param regionName    display name of the region
     * @param glossHeadline Claude-generated one-line headline (nullable)
     * @param glossDetail   Claude-generated expanded detail (nullable)
     * @param verdict       region verdict: GO / MARGINAL / STANDDOWN (nullable)
     * @param locations     locations within this region
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RegionGroup(
            String regionName,
            String glossHeadline,
            String glossDetail,
            String verdict,
            List<LocationEntry> locations) {
    }

    /**
     * A single location within a region group.
     *
     * @param locationName          display name of the location
     * @param locationType          location type label (e.g. "Woodland", "Coastal")
     * @param badge                 optional badge text (e.g. "Best"); nullable
     * @param bluebellLocationMetrics bluebell-specific metrics (nullable)
     * @param tideLocationMetrics     tide-specific metrics (nullable)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LocationEntry(
            String locationName,
            String locationType,
            String badge,
            BluebellLocationMetrics bluebellLocationMetrics,
            TideLocationMetrics tideLocationMetrics) {

        /**
         * Returns this entry without its bluebell rating: the metrics (score, exposure, summary) and the
         * "Best" badge, which states the entry is the region's top rating, are removed. The name and the
         * location-type label stay, as does the tide detail.
         *
         * @return a copy for a reader whose sub-region picks do not cover this location
         */
        public LocationEntry withoutBluebellRating() {
            return new LocationEntry(locationName, locationType, null, null, tideLocationMetrics);
        }

        /**
         * Returns this entry without its badge (the "Best" mark states a ranking).
         *
         * @return a copy with {@code badge} null
         */
        public LocationEntry withoutBadge() {
            return new LocationEntry(locationName, locationType, null, bluebellLocationMetrics,
                    tideLocationMetrics);
        }
    }

    // ── Pill-level metrics ─────────────────────────────────────────────────

    /**
     * Pill-level bluebell metrics.
     *
     * @param bestScore            highest bluebell rating (1–5) across all regions; null when the reader's
     *                             sub-region picks do not cover the rated places
     * @param qualityLabel         human-readable quality: "Excellent" / "Good" / "Fair"; null with the score
     * @param scoringLocationCount number of locations included in the expanded detail
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BluebellMetrics(Integer bestScore, String qualityLabel, int scoringLocationCount) {

        /**
         * Returns these metrics without the best score and the label derived from it; the count stays.
         *
         * @return a copy for a shaped reader
         */
        public BluebellMetrics withoutScore() {
            return new BluebellMetrics(null, null, scoringLocationCount);
        }
    }

    /**
     * Pill-level tide metrics.
     *
     * @param tidalClassification  "King tide" or "Spring tide"
     * @param lunarPhase           current moon phase name (e.g. "Full Moon")
     * @param sunriseAlignedCount  coastal locations with tide aligned at sunrise; null on the Lite wire
     * @param sunsetAlignedCount   coastal locations with tide aligned at sunset; null on the Lite wire
     */
    public record TideMetrics(String tidalClassification, String lunarPhase,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer sunriseAlignedCount,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer sunsetAlignedCount) {

        /**
         * Returns these metrics as a LITE reader may see them: the two aligned counts, which state
         * how many locations have the water at the light, are null (and so absent from the wire).
         *
         * @return a copy with both counts null
         */
        public TideMetrics forLite() {
            return new TideMetrics(tidalClassification, lunarPhase, null, null);
        }
    }

    // ── Per-location typed metrics ─────────────────────────────────────────

    /**
     * Bluebell-specific metrics for a single location.
     *
     * @param score     bluebell rating (1–5)
     * @param exposure  exposure type: "WOODLAND" or "OPEN_FELL"
     * @param summary   bluebell condition summary text (nullable)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BluebellLocationMetrics(int score, String exposure, String summary) {
    }

    /**
     * Tide-specific metrics for a single location.
     *
     * @param tidePreference primary tide preference label (e.g. "HIGH")
     */
    public record TideLocationMetrics(String tidePreference) {
    }
}
