package com.gregochr.goldenhour.model;

/**
 * Result of a postcodes.io postcode lookup.
 *
 * @param postcode  formatted postcode (e.g. "DH1 3LE")
 * @param latitude  WGS84 latitude
 * @param longitude WGS84 longitude
 * @param placeName human-friendly place name (e.g. "Durham, County Durham")
 */
public record PostcodeLookupResult(
        String postcode,
        double latitude,
        double longitude,
        String placeName) {
}
