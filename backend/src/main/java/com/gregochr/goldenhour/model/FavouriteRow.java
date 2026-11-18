package com.gregochr.goldenhour.model;

import java.time.LocalDateTime;

/**
 * One favourite as the repository reads it: a scalar projection, never a managed entity.
 *
 * @param locationId the starred location, or null for an area row
 * @param areaId     the starred area, or null for a place row
 * @param createdAt  when it was starred (UTC wall clock)
 */
public record FavouriteRow(Long locationId, Long areaId, LocalDateTime createdAt) {
}
