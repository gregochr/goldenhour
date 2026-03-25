package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Region-level rollup in the daily briefing.
 *
 * @param regionName     display name of the geographic region
 * @param verdict        rolled-up verdict across all slots in this region
 * @param summary        one-line human-readable summary of conditions
 * @param tideHighlights notable tide events (e.g. "King tide at Bamburgh")
 * @param slots          individual location assessments within this region
 */
public record BriefingRegion(
        String regionName,
        Verdict verdict,
        String summary,
        List<String> tideHighlights,
        List<BriefingSlot> slots) {
}
