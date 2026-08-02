package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gregochr.goldenhour.entity.TargetType;

import java.util.List;

/**
 * Per-event-type summary (sunrise or sunset) within a day.
 *
 * @param targetType the solar event type (SUNRISE or SUNSET)
 * @param regions    region-level rollups with their child slots
 * @param unregioned location slots not assigned to any region
 * @param window     the window-first Plan tab's projection of this event, or null. Null on every
 *                   internal path and in the persisted cache — it is derived at serve time, as the
 *                   last step before the response leaves {@code BriefingService}, and omitted from
 *                   JSON when absent so the stored payload is unchanged
 */
public record BriefingEventSummary(
        TargetType targetType,
        List<BriefingRegion> regions,
        List<BriefingSlot> unregioned,
        @JsonInclude(JsonInclude.Include.NON_NULL) BriefingWindow window) {

    public BriefingEventSummary {
        regions = List.copyOf(regions);
        unregioned = List.copyOf(unregioned);
    }

    /**
     * Builds a summary with no window — every path except the serve-time projection.
     *
     * @param targetType the solar event type (SUNRISE or SUNSET)
     * @param regions    region-level rollups with their child slots
     * @param unregioned location slots not assigned to any region
     */
    public BriefingEventSummary(TargetType targetType, List<BriefingRegion> regions,
            List<BriefingSlot> unregioned) {
        this(targetType, regions, unregioned, null);
    }

    /**
     * Returns a copy carrying the given window, leaving everything else untouched.
     *
     * <p>A wither rather than a positional rebuild at the projection site, deliberately: rebuilding
     * this record by constructor is how a component gets silently defaulted away when a new one is
     * added later. {@code BriefingRegion.withGloss} exists for the same reason, after exactly that
     * happened to {@code confidence}.
     *
     * @param newWindow the projected window
     * @return a copy with the window attached
     */
    public BriefingEventSummary withWindow(BriefingWindow newWindow) {
        return new BriefingEventSummary(targetType, regions, unregioned, newWindow);
    }
}
