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
 * @param localRadiusMiles      the caller's Close to home radius in miles, or null when never
 *                              chosen (the service applies the default)
 * @param driveTimesCalculatedAt   when drive times were last calculated, or null
 * @param mapColourScale           which ramp paints the map — {@code "temp"} or {@code "verdict"},
 *                                 or null when never chosen. Raw pass-through, deliberately not
 *                                 defaulted here: a later stage needs to tell "never chose" apart
 *                                 from "explicitly chose verdict" to change the default safely.
 */
public record UserSettingsResponse(
        String username,
        String email,
        String role,
        String homePostcode,
        Double homeLatitude,
        Double homeLongitude,
        String homePlaceName,
        Integer localRadiusMiles,
        Instant driveTimesCalculatedAt,
        String mapColourScale) {
}
