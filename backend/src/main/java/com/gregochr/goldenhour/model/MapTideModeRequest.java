package com.gregochr.goldenhour.model;

/**
 * Request body for saving the caller's Map tab tide mode (map-mobile-sheet-plan.md, M4).
 *
 * <p>Deliberately its own endpoint rather than fields on {@link SaveHomeRequest}, matching
 * {@link MapColourPreferencesRequest}'s reasoning: a tide mode is not home-derived, and a body
 * carrying only this field would deserialise the home fields to null and wipe a saved postcode.
 *
 * @param mapTideMode which tide cues the phone Map tab shows — must be {@code "auto"},
 *                    {@code "always"} or {@code "off"}; any other value is rejected
 */
public record MapTideModeRequest(String mapTideMode) {
}
