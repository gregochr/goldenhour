package com.gregochr.goldenhour.model;

import java.time.LocalDateTime;

/**
 * One favourite as the repository reads it: a scalar projection, never a managed entity. Exactly one of the three
 * targets is set.
 *
 * @param locationId the starred location, or null for an area or a date row
 * @param areaId     the starred area, or null for a place or a date row
 * @param almanacKey the watched date (type, a colon, the start date), or null for a place or an area row
 * @param createdAt  when it was starred (UTC wall clock)
 */
public record FavouriteRow(Long locationId, Long areaId, String almanacKey, LocalDateTime createdAt) {
}
