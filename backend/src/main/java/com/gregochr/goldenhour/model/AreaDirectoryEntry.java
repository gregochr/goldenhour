package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.AreaKind;

import java.util.List;

/**
 * One area in the reader-facing directory behind Find ({@code GET /api/areas/directory}): a named area or a
 * sub-region, with the other names readers use for it and the ENABLED locations that belong to it. Role-free
 * roster data: it never carries a rating or a verdict.
 *
 * @param id          the area's id
 * @param name        the display name
 * @param kind        {@link AreaKind#NAMED} or {@link AreaKind#SUB_REGION}
 * @param aliases     other names for the area, shortest first, then alphabetical
 * @param regionId    the region a sub-region partitions, or null for a named area
 * @param regionName  that region's name, or null for a named area
 * @param locationIds the enabled member locations' ids, ascending
 */
public record AreaDirectoryEntry(Long id, String name, AreaKind kind, List<String> aliases, Long regionId,
        String regionName, List<Long> locationIds) {
}
