package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * The body of {@code PUT /api/user/settings/sub-regions} and {@code PUT /api/users/{id}/sub-regions}.
 *
 * @param areaIds the sub-region area ids to hold, replacing the current set
 */
public record SubRegionPicksRequest(List<Long> areaIds) {
}
