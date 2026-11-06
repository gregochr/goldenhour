package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The Plan headline recomputed for each drive tier: what every window's verdict, mean, confidence and pick become
 * when only the places inside the reader's drive count (docs/engineering/reach-scoped-card-plan.md D4, R2).
 *
 * <p><b>Personal, so it never rides an ETag-shared payload.</b> It depends on the caller's drive times (or the
 * chosen base's), which {@code /api/briefing} must never carry. It is served from {@code
 * GET /api/user/settings/reach-verdicts}, which {@code HttpCachingConfig} never caches.
 *
 * <p>All three tiers come in one body so that a tier tap on the client is a lookup and never a request.
 *
 * @param measured        whether any drive is measured for this origin; false means no tier can act, and every tier
 *                        list is empty. The client's gate is the same value, computed once for the origin
 * @param briefingVersion the {@code briefingVersion} of the served briefing this was computed from, or null when
 *                        there was no briefing; a client discards an answer whose version differs from the briefing
 *                        it holds
 * @param tiers           the windows of each tier, keyed by the tier's minutes ({@code "45"}, {@code "90"},
 *                        {@code "150"}), in briefing order
 */
public record ReachVerdictResponse(
        boolean measured,
        @JsonInclude(JsonInclude.Include.NON_NULL) String briefingVersion,
        Map<String, List<Window>> tiers) {

    /**
     * Canonical constructor: takes an immutable, order-preserving copy of {@code tiers}.
     */
    public ReachVerdictResponse {
        tiers = tiers == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(tiers));
    }

    /**
     * One window as the tier sees it.
     *
     * <p>The word is the window's own {@code displayVerdict}, a lookup the client keeps a lookup; nothing here asks
     * the client to compare a number to a threshold.
     *
     * @param date           the window's date
     * @param targetType     the solar event
     * @param displayVerdict the verdict the window wears at this tier: the top in-reach region's own, AWAITING when
     *                       nothing is in reach. Away, the origin region's own
     * @param meanRating     the mean the card may PRINT beside the word: the region's mean when its sample is
     *                       verdict-eligible or its word is UNRATED, else null (a triage word has no mean behind
     *                       it); null when nothing is in reach
     * @param topMeanRating  the top region's mean regardless of eligibility, for the Order control's Best ranking;
     *                       null when it carries none
     * @param confidence     the top region's confidence, or null
     * @param blanked        true when the word is a STAND_DOWN over a region the honesty filter blanked
     * @param thinCount      the number of rated places behind a rated word whose region is verdict-eligible only by
     *                       force-evaluation (the sample gate failed), or null; the card's {@code · N rated}
     * @param pick           the forecast's pick if it falls on this window at this tier, or null. Away, only a
     *                       pick that names the origin region
     * @param pickRegion     the picked region's name, or null (the pick's own, repeated for the client's join)
     * @param regions        every in-reach region of the window, best first, for surfaces that need each region's
     *                       own verdict (the Map pill, R3); away, the origin region alone
     */
    public record Window(
            LocalDate date,
            TargetType targetType,
            DisplayVerdict displayVerdict,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double meanRating,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double topMeanRating,
            @JsonInclude(JsonInclude.Include.NON_NULL) Confidence confidence,
            boolean blanked,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer thinCount,
            @JsonInclude(JsonInclude.Include.NON_NULL) BriefingWindow.Pick pick,
            @JsonInclude(JsonInclude.Include.NON_NULL) String pickRegion,
            List<RegionVerdict> regions) {

        /**
         * Canonical constructor: takes an immutable copy of {@code regions}; null reads as empty.
         */
        public Window {
            regions = regions == null ? List.of() : List.copyOf(regions);
        }
    }

    /**
     * One in-reach region's own verdict in a window.
     *
     * @param regionName     the region's name, as the briefing names it
     * @param displayVerdict the region's verdict over its in-reach places
     * @param meanRating     the region's mean over them, or null when it carries none
     */
    public record RegionVerdict(
            String regionName,
            DisplayVerdict displayVerdict,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double meanRating) {
    }
}
