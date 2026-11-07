package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One match from postcodes.io's {@code /places?q=} search (a named settlement, hamlet or other place), as it comes on
 * the wire. Its own record, not a postcode result: a place has no postcode, and only these six of its many fields are
 * used.
 *
 * @param name          the place name ({@code name_1}), e.g. "Glenridding"
 * @param localType     what kind of place it is ({@code local_type}), e.g. "Village"
 * @param latitude      WGS84 latitude
 * @param longitude     WGS84 longitude
 * @param countyUnitary the county or unitary authority ({@code county_unitary}), or null
 * @param region        the English region ({@code region}), or null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlaceResult(
        @JsonProperty("name_1") String name,
        @JsonProperty("local_type") String localType,
        double latitude,
        double longitude,
        @JsonProperty("county_unitary") String countyUnitary,
        String region) {
}
