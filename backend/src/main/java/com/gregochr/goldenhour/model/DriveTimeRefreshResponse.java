package com.gregochr.goldenhour.model;

import java.time.Instant;

/**
 * Response DTO for a drive time refresh.
 *
 * @param locationsUpdated the number of locations with drive times calculated
 * @param calculatedAt     the timestamp of the calculation
 */
public record DriveTimeRefreshResponse(int locationsUpdated, Instant calculatedAt) {
}
