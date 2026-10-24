package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Request body replacing a named area's membership.
 *
 * @param locationIds the member locations' ids (null reads as none)
 */
public record AreaLocationsRequest(List<Long> locationIds) {
}
