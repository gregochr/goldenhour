package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.AreaKind;

import java.util.List;

/**
 * A named area as the admin API serves it.
 *
 * @param id          the area's primary key
 * @param name        the area's name
 * @param aliases     other names for it, sorted
 * @param locationIds the member locations' ids, ascending; for a sub-region they are read from
 *                    {@code location_sub_region}, and are read-only (membership is set on the location)
 * @param kind        {@link AreaKind#NAMED} or {@link AreaKind#SUB_REGION}
 * @param regionId    the region a sub-region partitions; null for a named area
 * @param regionName  that region's name; null for a named area
 */
public record AreaDto(Long id, String name, List<String> aliases, List<Long> locationIds, AreaKind kind,
        Long regionId, String regionName) {

    /**
     * A named area: no region.
     *
     * @param id          the area's primary key
     * @param name        the area's name
     * @param aliases     other names for it, sorted
     * @param locationIds the member locations' ids, ascending
     */
    public AreaDto(Long id, String name, List<String> aliases, List<Long> locationIds) {
        this(id, name, aliases, locationIds, AreaKind.NAMED, null, null);
    }
}
