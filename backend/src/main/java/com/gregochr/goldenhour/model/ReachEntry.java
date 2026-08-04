package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One location's reach for one caller — how far away it is, and how long it takes to get there.
 *
 * <p><b>This is the per-user half of a two-contract join, and it must never ride
 * {@code /api/briefing}.</b> That path is ETag-revalidated, which requires
 * {@code Cache-Control: private, no-cache} and therefore persists the body to a browser HTTP cache
 * JavaScript cannot evict on logout. {@code HttpCachingConfig} keeps {@code /api/user/settings*}
 * out of the revalidatable set for exactly that reason. Shared window content comes from the
 * briefing; reach comes from here; the client joins them on {@code locationId}.
 *
 * <p><b>Both figures are independently nullable, and neither absence means "out of reach".</b> A
 * lens is not a gate when it has no data: a location with no drive time is <em>unknown</em>, so it
 * passes every tier and simply renders without its drive line. Emptying the page instead would make
 * the control look broken to the one user who most needs it to be honest — the first-run user, who
 * has no home postcode at all. That is the normal starting state, not a failure.
 *
 * @param locationId    the location this describes; the join key, and never null
 * @param driveMinutes  driving time from the caller's home, or null when none is stored for this
 *                      location — which is every location until drive times are first calculated
 * @param distanceMiles straight-line distance from the caller's home, rounded to whole miles to
 *                      match the spot card that renders it, or null when no home postcode is saved
 */
public record ReachEntry(
        Long locationId,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer driveMinutes,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer distanceMiles) {
}
