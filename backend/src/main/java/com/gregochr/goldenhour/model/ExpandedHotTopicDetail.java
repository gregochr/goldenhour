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
    }

    // ── Pill-level metrics ─────────────────────────────────────────────────

    /**
     * Pill-level bluebell metrics.
     *
     * @param bestScore            highest bluebell score across all regions
     * @param qualityLabel         human-readable quality: "Excellent" / "Good" / "Fair"
     * @param scoringLocationCount number of locations with score &ge; 5
     */
    public record BluebellMetrics(int bestScore, String qualityLabel, int scoringLocationCount) {
    }

    /**
     * Pill-level tide metrics.
     *
     * @param tidalClassification  "King tide" or "Spring tide"
     * @param lunarPhase           current moon phase name (e.g. "Full Moon")
     * @param sunriseAlignedCount  coastal locations with tide aligned at sunrise
     * @param sunsetAlignedCount   coastal locations with tide aligned at sunset
     */
    public record TideMetrics(String tidalClassification, String lunarPhase,
            int sunriseAlignedCount, int sunsetAlignedCount) {
    }

    // ── Per-location typed metrics ─────────────────────────────────────────

    /**
     * Bluebell-specific metrics for a single location.
     *
     * @param score     bluebell score (0–10)
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
