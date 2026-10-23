package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One day's big-seas summary, built from a single lead group of overlapping high-water windows
 * (docs/engineering/big-seas-plan.md §3 B2). Every time is {@code LocalDateTime} UTC with no zone,
 * like {@code BriefingSlot.solarEventTime}.
 *
 * @param date             the UK civil date
 * @param windowStart      earliest start across the lead group's windows
 * @param windowEnd        latest end across the lead group's windows
 * @param hwTime           the anchor member's high water
 * @param alignedEvent     {@code SUNRISE} or {@code SUNSET} when the anchor is aligned, else null
 * @param gapMinutes       minutes between the anchor's high water and the event (nearest minute,
 *                         never negative), or null unless aligned
 * @param gapSide          whether the high water falls before or after the event, or null unless aligned
 * @param waveHeightM      the group's maximum wave height, metres
 * @param waveDirectionDeg direction of origin of the maximum-wave member
 * @param wavePeriodS      period of the maximum-wave member, seconds
 * @param structures       the lead group's structures
 * @param otherWindows     further qualifying groups that day (served, not yet drawn)
 */
public record BigSeasDay(
        LocalDate date,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        LocalDateTime hwTime,
        @JsonInclude(JsonInclude.Include.NON_NULL) AlignedEvent alignedEvent,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long gapMinutes,
        @JsonInclude(JsonInclude.Include.NON_NULL) GapSide gapSide,
        double waveHeightM,
        int waveDirectionDeg,
        double wavePeriodS,
        List<BigSeasStructure> structures,
        int otherWindows) {

    /** The solar event a big-seas window can be aligned with. */
    public enum AlignedEvent {
        /** The window contains the sunrise. */
        SUNRISE,
        /** The window contains the sunset. */
        SUNSET
    }

    /** Whether the high water falls before or after the aligned event. */
    public enum GapSide {
        /** High water is before the event. */
        BEFORE,
        /** High water is at or after the event. */
        AFTER
    }

    /** Defensive copy of the structure list. */
    public BigSeasDay {
        structures = List.copyOf(structures);
    }
}
