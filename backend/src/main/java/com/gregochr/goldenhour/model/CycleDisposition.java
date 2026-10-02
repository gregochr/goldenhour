package com.gregochr.goldenhour.model;

/**
 * The (location, disposition) pair of one {@code forecast_run_disposition} row — the projection
 * {@code CycleLocationOutcomeResolver} reads to learn what collection decided about a place.
 *
 * @param locationId  the place's id
 * @param disposition the stored {@code DispositionCategory} name
 */
public record CycleDisposition(Long locationId, String disposition) {
}
