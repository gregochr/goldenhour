package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 * @param daylightHighWater true when the lead group is unaligned and its whole union window, with the
 *                         anchor's high water, lies strictly between the lights (sunrise and sunset);
 *                         omitted from the wire when false
 * @param daylightStart    the anchor structure's own sunrise (UTC, no zone); set only when
 *                         {@code daylightHighWater} is true
 * @param daylightEnd      the anchor structure's own sunset (UTC, no zone); set only when
 *                         {@code daylightHighWater} is true
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
        int otherWindows,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean daylightHighWater,
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime daylightStart,
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime daylightEnd) {

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

    /**
     * Returns this day as a LITE reader may see it. Where the high water falls against the light is
     * a Pro fact (tide-light-pro-plan.md section 3, B1): {@code alignedEvent}, {@code gapMinutes} and
     * {@code gapSide} are null and every structure's {@code aligned} is false. The daylight
     * high-water facts ({@code daylightHighWater}, {@code daylightStart}, {@code daylightEnd}) and
     * the high-water clock stay: "the high water falls in daylight" is a when-fact, free for every
     * role.
     *
     * @return the shaped copy
     */
    public BigSeasDay forLite() {
        return new BigSeasDay(date, windowStart, windowEnd, hwTime, null, null, null, waveHeightM,
                waveDirectionDeg, wavePeriodS,
                structures.stream().map(BigSeasStructure::forLite).toList(), otherWindows,
                daylightHighWater, daylightStart, daylightEnd);
    }
}
