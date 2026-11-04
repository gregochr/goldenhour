package com.gregochr.goldenhour.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

/**
 * Request body for saving a user's home location.
 *
 * <p>The bounds are checked when the controller binds the body ({@code @Valid}), so malformed input answers
 * HTTP 400 and never reaches the database: the postcode is held in a {@code VARCHAR(10)} column
 * ({@link #POSTCODE_MAX_LENGTH}), and a longer value used to surface as a database error.
 *
 * @param postcode  the formatted UK postcode (e.g. "DH1 3LE"), at most {@value #POSTCODE_MAX_LENGTH} characters
 * @param latitude  WGS84 latitude resolved from the postcode
 * @param longitude WGS84 longitude resolved from the postcode
 * @param localRadiusMiles the Close to home radius in miles, or null to leave it unchanged —
 *                  the two settings live together because the radius is measured from this home
 */
public record SaveHomeRequest(
        @Size(max = SaveHomeRequest.POSTCODE_MAX_LENGTH) String postcode,
        @DecimalMin("-90.0") @DecimalMax("90.0") double latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") double longitude,
        Integer localRadiusMiles) {

    /** The width of the {@code app_user.home_postcode} column (V67). */
    public static final int POSTCODE_MAX_LENGTH = 10;
}
