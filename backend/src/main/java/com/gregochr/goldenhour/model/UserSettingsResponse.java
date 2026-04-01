package com.gregochr.goldenhour.model;

import java.time.Instant;

/**
 * Response DTO for the user settings endpoint.
 *
 * @param username                 the user's login name
 * @param email                    the user's email address
 * @param role                     the user's role (e.g. "ADMIN", "PRO_USER")
 * @param homePostcode             the user's home postcode, or null
 * @param homeLatitude             the user's home latitude, or null
 * @param homeLongitude            the user's home longitude, or null
 * @param homePlaceName            the resolved place name, or null
 * @param driveTimesCalculatedAt   when drive times were last calculated, or null
 */
public record UserSettingsResponse(
        String username,
        String email,
        String role,
        String homePostcode,
        Double homeLatitude,
        Double homeLongitude,
        String homePlaceName,
        Instant driveTimesCalculatedAt) {
}
