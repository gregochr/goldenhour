package com.gregochr.goldenhour.model;

/**
 * Request body for saving a user's home location.
 *
 * @param postcode  the formatted UK postcode (e.g. "DH1 3LE")
 * @param latitude  WGS84 latitude resolved from the postcode
 * @param longitude WGS84 longitude resolved from the postcode
 * @param localRadiusMiles the Close to home radius in miles, or null to leave it unchanged —
 *                  the two settings live together because the radius is measured from this home
 */
public record SaveHomeRequest(String postcode, double latitude, double longitude,
        Integer localRadiusMiles) {
}
