package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.AreaKind;

import java.util.List;

/**
 * Request body for creating or renaming a named area. Membership is not part of it: the membership endpoint is
 * the only writer of a named area's locations, and a sub-region's members are set on each location.
 *
 * <p>{@code kind} and {@code regionId} matter only when creating: a sub-region needs a region and a named area
 * has none. On an update they must be absent or equal to what the area already has, because an area's kind
 * and region do not change.
 *
 * @param name     the area's name
 * @param aliases  other names for it (null reads as none)
 * @param kind     the kind to create (null reads as {@link AreaKind#NAMED})
 * @param regionId the region a sub-region partitions (required for a sub-region, refused for a named area)
 */
public record AreaRequest(String name, List<String> aliases, AreaKind kind, Long regionId) {

    /**
     * A request that names no kind and no region: a named area on create, "no change" on update.
     *
     * @param name    the area's name
     * @param aliases other names for it (null reads as none)
     */
    public AreaRequest(String name, List<String> aliases) {
        this(name, aliases, null, null);
    }
}
