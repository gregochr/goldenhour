package com.gregochr.goldenhour.repository;

/**
 * One stored drive time, as the two ids alone: which origin (a user or a region) has a row for which location.
 * The unroutable-places read only asks "is there a row?", so the duration is not selected.
 *
 * @param originId   the user id (a home) or region id (a base)
 * @param locationId the location the row routes to
 */
public record DrivePair(Long originId, Long locationId) { }
