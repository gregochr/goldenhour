package com.gregochr.goldenhour.model;

/**
 * Request body for setting a region's base town via {@code PUT /api/regions/{id}/base}.
 *
 * <p><b>Its own endpoint rather than three more fields on {@code UpdateRegionRequest}</b>, which
 * is a rename. A JSON body carrying only {@code name} would deserialise the base fields to null,
 * so every rename through the existing form would silently clear the base and discard the region's
 * whole drive-time matrix with it. {@code PUT /{id}/enabled} is separate for the same reason.
 *
 * <p>All three null clears the base — the region stays searchable and stops being an origin. A
 * partial base is rejected: a name with no coordinates cannot be routed from, and coordinates with
 * no name would put an unlabelled origin on the chip.
 *
 * @param baseName the town a visitor would base themselves in, or null to clear
 * @param baseLat  the base town's latitude, or null to clear
 * @param baseLon  the base town's longitude, or null to clear
 */
public record SetRegionBaseRequest(String baseName, Double baseLat, Double baseLon) {
}
