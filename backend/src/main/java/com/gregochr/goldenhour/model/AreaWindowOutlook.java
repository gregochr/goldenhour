package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;

/**
 * What one reader may know of one area for one window: how many members carry a rating, how many are hidden
 * from this reader, and the best rating they are allowed to see.
 *
 * <p>Built from the same served scores as {@code /api/briefing/evaluate/scores} after {@code LitePickShaper}
 * has shaped them, so a gated member is counted in {@code gatedMembers} and never rated. It is a count and a
 * top figure for display with its window, never the area's verdict.
 *
 * @param date          the window's date
 * @param targetType    SUNRISE or SUNSET
 * @param ratedMembers  members with a rating this reader may see
 * @param totalMembers  enabled members of the area
 * @param gatedMembers  members whose rating is hidden from this reader
 * @param topRating     the best rating among the rated members, or null when none is rated
 * @param topLocationId the member that holds it (ties by name, then id), or null
 */
public record AreaWindowOutlook(
        LocalDate date,
        TargetType targetType,
        int ratedMembers,
        int totalMembers,
        int gatedMembers,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer topRating,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long topLocationId) {
}
