package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Per-event-type summary (sunrise or sunset) within a day.
 *
 * @param targetType     the solar event type (SUNRISE or SUNSET)
 * @param regions        region-level rollups with their child slots
 * @param unregioned     location slots not assigned to any region
 * @param solarEventTime when this event occurs, independent of the slots — see below. Null only
 *                       for an event with no slots at all, and in payloads cached before this
 *                       component existed
 * @param window         the window-first Plan tab's projection of this event, or null. Null on
 *                       every internal path and in the persisted cache — it is derived at serve
 *                       time, as the last step before the response leaves {@code BriefingService},
 *                       and omitted from JSON when absent so the stored payload is unchanged
 */
public record BriefingEventSummary(
        TargetType targetType,
        List<BriefingRegion> regions,
        List<BriefingSlot> unregioned,
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime solarEventTime,
        @JsonInclude(JsonInclude.Include.NON_NULL) BriefingWindow window) {

    public BriefingEventSummary {
        regions = List.copyOf(regions);
        unregioned = List.copyOf(unregioned);
    }

    /**
     * Builds a summary with neither an event time nor a window.
     *
     * <p>Retained for the many construction sites that only ever cared about the hierarchy. A
     * summary built this way carries no {@code solarEventTime}, so the client falls back to its
     * own date-derived ordering — correct, but coarser. Production build paths should use the
     * canonical constructor and supply the time.
     *
     * @param targetType the solar event type (SUNRISE or SUNSET)
     * @param regions    region-level rollups with their child slots
     * @param unregioned location slots not assigned to any region
     */
    public BriefingEventSummary(TargetType targetType, List<BriefingRegion> regions,
            List<BriefingSlot> unregioned) {
        this(targetType, regions, unregioned, null, null);
    }

    /**
     * The earliest solar event time across the given slots, or null when there are none.
     *
     * <p>Earliest rather than any-one-slot because it must be deterministic: the alternative the
     * client used to rely on — "first slot encountered" — varies with region grouping order, so
     * two runs over the same data could disagree. The spread across a region set is a few minutes
     * against a 30-minute afterglow window, so the choice between earliest and latest changes
     * nothing a reader could see.
     *
     * @param slots the slots for one date and event type
     * @return the earliest event time, or null if {@code slots} is empty
     */
    public static LocalDateTime earliestEventTime(List<BriefingSlot> slots) {
        return slots.stream()
                .map(BriefingSlot::solarEventTime)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /**
     * Returns a copy carrying the given regions, leaving every other component untouched.
     *
     * <p>Exists for the same reason as {@link #withWindow}: three separate passes rebuild a
     * summary purely to swap its region list, and each did so positionally through the three-arg
     * constructor. That silently defaulted {@code solarEventTime} away — which is precisely the
     * defect this component was added to fix, so rebuilding by constructor would have reinstated
     * it one call later.
     *
     * @param newRegions the replacement region rollups
     * @return a copy carrying the new regions
     */
    public BriefingEventSummary withRegions(List<BriefingRegion> newRegions) {
        return new BriefingEventSummary(targetType, newRegions, unregioned, solarEventTime, window);
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
        return new BriefingEventSummary(targetType, regions, unregioned, solarEventTime, newWindow);
    }
}
