package com.gregochr.goldenhour.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP client for the lightpollutionmap.info QueryRaster API.
 *
 * <p>Used by {@code BortleEnrichmentService} to look up the artificial sky brightness
 * at a coordinate and convert it to a Bortle class (1–9).
 *
 * <p>The API allows 500 requests/day. With ~200 locations and a 500 ms delay between
 * calls, a full enrichment run takes ~2 minutes and stays within the daily limit.
 *
 * <p>API endpoint format:
 * <pre>
 *   GET https://www.lightpollutionmap.info/QueryRaster/
 *       ?ql=wa_2015
 *       &amp;qt=point
 *       &amp;qd={longitude},{latitude}   (longitude FIRST)
 *       &amp;key={apiKey}
 * </pre>
 */
@Component
public class LightPollutionClient {

    private static final Logger LOG = LoggerFactory.getLogger(LightPollutionClient.class);

    /** World Atlas 2015 data layer identifier. */
    private static final String QUERY_LAYER = "wa_2015";

    /** Upper bounds (mcd/m²) for each Bortle class, index 0 = Bortle 1, index 7 = Bortle 8. */
    private static final double[] BORTLE_UPPER_MCD = {0.01, 0.02, 0.06, 0.17, 0.50, 1.70, 5.00, 15.0};

    private final RestClient restClient;

    /**
     * Constructs the client with the shared {@link RestClient}.
     *
     * @param restClient shared HTTP client
     */
    public LightPollutionClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Queries the sky brightness at the given coordinate and returns the Bortle class.
     *
     * <p>Returns {@code null} if the API call fails or returns an unexpected response,
     * so the caller can skip the location and try again later.
     *
     * @param latitude  observer latitude in decimal degrees
     * @param longitude observer longitude in decimal degrees
     * @param apiKey    lightpollutionmap.info API key
     * @return Bortle class 1–9, or {@code null} on failure
     */
    public Integer queryBortleClass(double latitude, double longitude, String apiKey) {
        try {
            // Note: API expects longitude,latitude order in the qd parameter
            BrightnessResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("www.lightpollutionmap.info")
                            .path("/QueryRaster/")
                            .queryParam("ql", QUERY_LAYER)
                            .queryParam("qt", "point")
                            .queryParam("qd", longitude + "," + latitude)
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .body(BrightnessResponse.class);

            if (response == null || response.result() == null) {
                LOG.warn("Null response from light pollution API for ({}, {})", latitude, longitude);
                return null;
            }
            double mcd = response.result();
            return toBortleClass(mcd);
        } catch (RestClientException e) {
            LOG.warn("Light pollution API call failed for ({}, {}): {}", latitude, longitude,
                    e.getMessage());
            return null;
        }
    }

    /**
     * Converts an artificial sky brightness value in mcd/m² to a Bortle class (1–9).
     *
     * <p>Uses the World Atlas 2015 lookup table. Values above the Bortle 8 threshold
     * are classified as Bortle 9 (most light-polluted).
     *
     * @param mcdPerM2 artificial sky brightness in millicandela per square metre
     * @return Bortle class 1 (darkest) through 9 (most light-polluted)
     */
    int toBortleClass(double mcdPerM2) {
        for (int i = 0; i < BORTLE_UPPER_MCD.length; i++) {
            if (mcdPerM2 < BORTLE_UPPER_MCD[i]) {
                return i + 1; // Bortle 1–8
            }
        }
        return 9; // > 15 mcd/m²
    }

    /**
     * JSON response from the lightpollutionmap.info QueryRaster endpoint.
     *
     * @param result artificial sky brightness in mcd/m²
     */
    record BrightnessResponse(@JsonProperty("result") Double result) {
    }
}
