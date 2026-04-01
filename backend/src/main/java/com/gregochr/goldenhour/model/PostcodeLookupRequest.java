package com.gregochr.goldenhour.model;

/**
 * Request body for postcode lookup.
 *
 * @param postcode the UK postcode to geocode
 */
public record PostcodeLookupRequest(String postcode) {
}
