package com.gregochr.goldenhour.model;

/**
 * Request body for the reverse postcode lookup: a point to find the nearest UK postcode to.
 *
 * <p>Boxed {@code Double}s so a missing field arrives as {@code null} and is answered as a 400 by
 * {@code UserSettingsService.reverseLookupPostcode}, rather than silently becoming {@code 0.0}.
 *
 * @param latitude  WGS84 latitude, −90 to 90
 * @param longitude WGS84 longitude, −180 to 180
 */
public record ReverseLookupRequest(Double latitude, Double longitude) {
}
