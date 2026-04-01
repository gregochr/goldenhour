package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.model.PostcodeLookupResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client for the postcodes.io UK postcode geocoding API.
 *
 * <p>Completely free, no API key, public domain data. Used to resolve a user's
 * home postcode to coordinates for drive-time calculations.
 */
@Component
public class PostcodesIoClient {

    private static final Logger LOG = LoggerFactory.getLogger(PostcodesIoClient.class);
    private static final String BASE_URL = "https://api.postcodes.io";

    private final RestClient restClient;

    /**
     * Constructs a {@code PostcodesIoClient}.
     *
     * @param restClient the shared RestClient bean
     */
    public PostcodesIoClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Looks up a UK postcode, returning the formatted postcode, coordinates, and place name.
     *
     * @param postcode the postcode to look up (spaces and case are normalised)
     * @return the lookup result with coordinates and place name
     * @throws PostcodeLookupException if the postcode is invalid or the service is unavailable
     */
    public PostcodeLookupResult lookup(String postcode) {
        String normalised = postcode.replaceAll("\\s+", "").toUpperCase();

        PostcodesIoResponse response;
        try {
            response = restClient.get()
                    .uri(BASE_URL + "/postcodes/{postcode}", normalised)
                    .retrieve()
                    .body(PostcodesIoResponse.class);
        } catch (Exception e) {
            LOG.warn("Postcode lookup failed for '{}': {}", normalised, e.getMessage());
            throw new PostcodeLookupException("Postcode lookup failed: " + e.getMessage());
        }

        if (response == null || response.result() == null) {
            throw new PostcodeLookupException("Invalid postcode: " + postcode);
        }

        PostcodesIoResponse.Result r = response.result();
        return new PostcodeLookupResult(
                r.postcode(),
                r.latitude(),
                r.longitude(),
                buildPlaceName(r)
        );
    }

    private String buildPlaceName(PostcodesIoResponse.Result r) {
        String locality = r.parish() != null ? r.parish() : r.adminWard();
        if (locality != null && r.adminDistrict() != null && !locality.equals(r.adminDistrict())) {
            return locality + ", " + r.adminDistrict();
        }
        return r.adminDistrict() != null ? r.adminDistrict() : r.postcode();
    }

    /**
     * Internal record mapping the postcodes.io response.
     *
     * @param status HTTP status code
     * @param result the postcode result, or null for invalid postcodes
     */
    record PostcodesIoResponse(int status, Result result) {

        /**
         * Fields from the postcodes.io result object.
         *
         * @param postcode        formatted postcode (e.g. "DH1 3LE")
         * @param latitude        WGS84 latitude
         * @param longitude       WGS84 longitude
         * @param adminDistrict   local authority district (e.g. "County Durham")
         * @param adminWard       electoral ward
         * @param parish          civil parish, or null
         */
        record Result(
                String postcode,
                double latitude,
                double longitude,
                String adminDistrict,
                String adminWard,
                String parish) {
        }
    }
}
