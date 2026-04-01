package com.gregochr.goldenhour.model;

/**
 * Request body for saving a user's home location.
 *
 * @param postcode  the formatted UK postcode (e.g. "DH1 3LE")
 * @param latitude  WGS84 latitude resolved from the postcode
 * @param longitude WGS84 longitude resolved from the postcode
 */
public record SaveHomeRequest(String postcode, double latitude, double longitude) {
}
