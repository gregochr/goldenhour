package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.AreaKind;

/**
 * One area row as the directory read maps it: the area and its region's id and name, flat, so no lazy
 * association is touched.
 *
 * @param id         the area's id
 * @param name       the display name
 * @param kind       the kind (null reads as named)
 * @param regionId   the region's id, or null
 * @param regionName the region's name, or null
 */
public record AreaDirectoryRow(Long id, String name, AreaKind kind, Long regionId, String regionName) {
}
