package com.gregochr.goldenhour.model;

/**
 * Request body for saving the caller's map colour preferences.
 *
 * <p>Deliberately its own endpoint rather than fields on {@link SaveHomeRequest}: a colour
 * preference is not home-derived, and a body carrying only these two fields would deserialise
 * the home fields to null and wipe a saved postcode.
 *
 * @param mapColourScale     which ramp paints the map — must be {@code "temp"} or
 *                           {@code "verdict"}; any other value is rejected
 */
public record MapColourPreferencesRequest(String mapColourScale) {
}
