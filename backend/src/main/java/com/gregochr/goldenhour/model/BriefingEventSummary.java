package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;

import java.util.List;

/**
 * Per-event-type summary (sunrise or sunset) within a day.
 *
 * @param targetType the solar event type (SUNRISE or SUNSET)
 * @param regions    region-level rollups with their child slots
 * @param unregioned location slots not assigned to any region
 */
public record BriefingEventSummary(
        TargetType targetType,
        List<BriefingRegion> regions,
        List<BriefingSlot> unregioned) {

    public BriefingEventSummary {
        regions = List.copyOf(regions);
        unregioned = List.copyOf(unregioned);
    }
}
