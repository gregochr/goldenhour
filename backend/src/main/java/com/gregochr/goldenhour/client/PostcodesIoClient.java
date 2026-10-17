package com.gregochr.goldenhour.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gregochr.goldenhour.model.PostcodeLookupResult;
import com.gregochr.goldenhour.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.List;

/**
 * Client for the postcodes.io UK postcode geocoding API.
 *
 * <p>Completely free, no API key, public domain data. Used to resolve a user's
 * home postcode to coordinates for drive-time calculations, and to find the nearest
 * postcode to a point (the "Use my location" lookup).
 *
 * <p>Two failure types, deliberately distinct: {@link PostcodeLookupException} means the lookup
 * worked and there is no such postcode; {@link PostcodeLookupUnavailableException} means it did not
 * work (transport failure, 5xx, rate limit). Neither carries the upstream's own message.
 */
@Component
public class PostcodesIoClient {

    private static final Logger LOG = LoggerFactory.getLogger(PostcodesIoClient.class);
    private static final String BASE_URL = "https://api.postcodes.io";
    /** postcodes.io's maximum reverse-geocoding radius; its 100 m default misses most rural homes. */
    private static final int REVERSE_RADIUS_METRES = 2000;
    private static final String INVALID_POSTCODE = "Invalid postcode";
    private static final String NOT_FOUND_NEAR_POINT = "No UK postcode found for that location";

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
     * @throws PostcodeLookupException            if postcodes.io does not know the postcode
     * @throws PostcodeLookupUnavailableException if the service could not be reached or failed
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
            LOG.warn("Postcode lookup failed for '{}': {}", LogSanitizer.sanitize(normalised),
                    LogSanitizer.sanitize(e.getMessage()));
            throw failureFor(e, INVALID_POSTCODE);
        }

        if (response == null || response.result() == null) {
            throw new PostcodeLookupException(INVALID_POSTCODE);
        }
        return toResult(response.result());
    }

    /**
     * Finds the nearest UK postcode to a point.
     *
     * <p>Asks for one result within {@value #REVERSE_RADIUS_METRES} m — postcodes.io's maximum; its
     * default of 100 m misses most rural homes. A point with no postcode in that radius (the sea,
     * abroad) answers 200 with a null or empty result, which is "not found", not a failure.
     *
     * @param latitude  WGS84 latitude
     * @param longitude WGS84 longitude
     * @return the nearest postcode with its coordinates and place name
     * @throws PostcodeLookupException            if no postcode is found near the point
     * @throws PostcodeLookupUnavailableException if the service could not be reached or failed
     */
    public PostcodeLookupResult reverseLookup(double latitude, double longitude) {
        PostcodesIoReverseResponse response;
        try {
            response = restClient.get()
                    .uri(BASE_URL + "/postcodes?lon={lon}&lat={lat}&limit=1&radius=" + REVERSE_RADIUS_METRES,
                            plain(longitude), plain(latitude))
                    .retrieve()
                    .body(PostcodesIoReverseResponse.class);
        } catch (Exception e) {
            // No coordinates in the log: they are the caller's location. The cause is enough to diagnose.
            LOG.warn("Reverse postcode lookup failed: {}", LogSanitizer.sanitize(e.getMessage()));
            throw failureFor(e, NOT_FOUND_NEAR_POINT);
        }

        if (response == null || response.result() == null || response.result().isEmpty()
                || response.result().get(0) == null) {
            throw new PostcodeLookupException(NOT_FOUND_NEAR_POINT);
        }
        return toResult(response.result().get(0));
    }

    /**
     * Classifies an upstream failure. Only a 400 or 404 from postcodes.io says the lookup worked and
     * the answer is "no such postcode"; a transport error, a 5xx, a 429 or anything unreadable says
     * nothing about the postcode, so it is "unavailable". The caller logs the upstream message; it
     * is never carried into the exception.
     */
    private static RuntimeException failureFor(Exception cause, String notFoundMessage) {
        if (cause instanceof RestClientResponseException rcre
                && (rcre.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)
                || rcre.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST))) {
            return new PostcodeLookupException(notFoundMessage);
        }
        return new PostcodeLookupUnavailableException();
    }

    /** A coordinate as plain decimal text — {@code Double.toString} would give {@code 1.0E-5}. */
    private static String plain(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private PostcodeLookupResult toResult(PostcodesIoResponse.Result r) {
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
     * Internal record mapping the postcodes.io reverse-geocoding response.
     *
     * @param status HTTP status code
     * @param result the nearest postcodes (one is requested), or null/empty when none is near
     */
    record PostcodesIoReverseResponse(int status, List<PostcodesIoResponse.Result> result) {
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
         * @param adminDistrict   local authority district (e.g. "County Durham"); the wire field is
         *                        {@code admin_district}, and without the annotation it never bound,
         *                        so every place name silently fell back to the bare postcode
         * @param adminWard       electoral ward ({@code admin_ward} on the wire)
         * @param parish          civil parish, or null
         */
        record Result(
                String postcode,
                double latitude,
                double longitude,
                @JsonProperty("admin_district") String adminDistrict,
                @JsonProperty("admin_ward") String adminWard,
                String parish) {
        }
    }
}
