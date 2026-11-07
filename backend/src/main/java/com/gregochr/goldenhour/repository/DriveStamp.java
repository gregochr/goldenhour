package com.gregochr.goldenhour.repository;

import java.time.Instant;

/**
 * One drive origin's id and its last full-measurement stamp: a home ({@code app_user.drive_times_calculated_at})
 * or a region base ({@code regions.drives_measured_at}). A projection, so the admin unroutable-places read does
 * not hydrate entities.
 *
 * @param originId   the user id (a home) or region id (a base)
 * @param measuredAt the origin's stamp; never null in the rows the queries return
 */
public record DriveStamp(Long originId, Instant measuredAt) { }
