package com.gregochr.goldenhour.model;

/**
 * One {@code area_alias} row, flat.
 *
 * @param areaId the area's id
 * @param alias  the other name
 */
public record AreaAliasRow(Long areaId, String alias) {
}
