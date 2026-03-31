package com.gregochr.goldenhour.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AuroraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;

/**
 * HTTP client for the lightpollutionmap.info QueryRaster API.
 *
 * <p>Used by {@code BortleEnrichmentService} to look up the artificial sky brightness
 * at a coordinate and convert it to a Bortle class (1–9) via an intermediate SQM value.
 *
 * <p>The API allows 1000 requests/day (quota resets at GMT+1). With ~200 locations
 * and a 500 ms delay between calls, a full enrichment run takes ~2 minutes.
 *
 * <p>API endpoint format:
 * <pre>
 *   GET https://www.lightpollutionmap.info/api/queryraster
 *       ?ql=sb_2025
 *       &amp;qt=point
 *       &amp;qd={longitude},{latitude}   (longitude FIRST)
 *       &amp;key={apiKey}
 * </pre>
 */
@Component
public class LightPollutionClient {

    private static final Logger LOG = LoggerFactory.getLogger(LightPollutionClient.class);
    private static final String DEFAULT_API_URL = "https://www.lightpollutionmap.info/api/queryraster";
    private static final String DEFAULT_QUERY_LAYER = "sb_2025";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String queryLayer;

    /**
     * Constructs the client with the shared {@link RestClient} and aurora configuration.
     *
     * @param restClient      shared HTTP client
     * @param objectMapper    Jackson mapper for deserializing text/plain JSON responses
     * @param auroraProperties aurora configuration including light pollution API settings
     */
    public LightPollutionClient(RestClient restClient, ObjectMapper objectMapper,
                               AuroraProperties auroraProperties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        AuroraProperties.LightPollutionConfig lpConfig = auroraProperties.getLightPollution();
        if (lpConfig != null) {
            this.apiUrl = lpConfig.getApiUrl();
            this.queryLayer = lpConfig.getQueryLayer();
        } else {
            this.apiUrl = DEFAULT_API_URL;
            this.queryLayer = DEFAULT_QUERY_LAYER;
        }
    }

    /**
     * Queries the sky brightness at the given coordinate and returns the SQM value
     * and Bortle class.
     *
     * <p>Returns {@code null} if the API call fails or returns an unexpected response,
     * so the caller can skip the location and try again later.
     *
     * @param latitude  observer latitude in decimal degrees
     * @param longitude observer longitude in decimal degrees
     * @param apiKey    lightpollutionmap.info API key
     * @return sky brightness result with SQM and Bortle class, or {@code null} on failure
     */
    public SkyBrightnessResult querySkyBrightness(double latitude, double longitude, String apiKey) {
        try {
            // Note: API expects longitude,latitude order in the qd parameter
            URI uri = URI.create(apiUrl
                    + "?ql=" + queryLayer
                    + "&qt=point"
                    + "&qd=" + longitude + "," + latitude
                    + "&key=" + apiKey);

            // API returns JSON body but with text/plain content type,
            // so retrieve as String and deserialize manually.
            String raw = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);

            if (raw == null || raw.isBlank()) {
                LOG.warn("Empty response from light pollution API for ({}, {})", latitude, longitude);
                return null;
            }

            // API returns either a bare number (e.g. "0.0145") or a JSON object {"result": 0.0145}
            double mcd;
            String trimmed = raw.trim();
            if (trimmed.startsWith("{")) {
                BrightnessResponse response = objectMapper.readValue(trimmed, BrightnessResponse.class);
                if (response.result() == null) {
                    LOG.warn("Null result from light pollution API for ({}, {})", latitude, longitude);
                    return null;
                }
                mcd = response.result();
            } else {
                mcd = Double.parseDouble(trimmed);
            }
            double sqm = mcdToSqm(mcd);
            int bortle = sqmToBortle(sqm);
            return new SkyBrightnessResult(sqm, bortle);
        } catch (RestClientException e) {
            LOG.warn("Light pollution API call failed for ({}, {}): {}", latitude, longitude,
                    e.getMessage());
            return null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | NumberFormatException e) {
            LOG.warn("Failed to parse light pollution response for ({}, {}): {}", latitude, longitude,
                    e.getMessage());
            return null;
        }
    }

    /**
     * Converts artificial sky brightness in mcd/m² to SQM (magnitudes per square arcsecond).
     *
     * <p>Uses the formula provided by Jurij Stare (lightpollutionmap.info developer):
     * {@code SQM = log10((brightness + 0.171168465) / 108000000) / -0.4}
     *
     * <p>The 0.171168465 offset represents the natural sky background, ensuring that
     * a location with zero artificial light still yields a valid SQM (~22.0).
     *
     * @param mcdPerM2 artificial sky brightness in millicandela per square metre
     * @return SQM value (higher = darker sky)
     */
    double mcdToSqm(double mcdPerM2) {
        return Math.log10((mcdPerM2 + 0.171168465) / 108000000.0) / -0.4;
    }

    /**
     * Converts an SQM value to a Bortle class (1–8) using the Handprint reference table.
     *
     * @param sqm sky quality meter value (magnitudes per square arcsecond)
     * @return Bortle class 1 (darkest) through 8 (city sky, covers Bortle 8–9)
     * @see <a href="https://www.handprint.com/ASTRO/bortle.html">Handprint Bortle reference</a>
     */
    int sqmToBortle(double sqm) {
        if (sqm >= 21.99) {
            return 1;
        }
        if (sqm >= 21.89) {
            return 2;
        }
        if (sqm >= 21.69) {
            return 3;
        }
        if (sqm >= 20.49) {
            return 4;
        }
        if (sqm >= 19.50) {
            return 5;
        }
        if (sqm >= 18.94) {
            return 6;
        }
        if (sqm >= 18.38) {
            return 7;
        }
        return 8; // City sky (Bortle 8-9)
    }

    /**
     * Result of a sky brightness query containing both the continuous SQM value
     * and the discrete Bortle class.
     *
     * @param sqm    sky quality meter value (magnitudes per square arcsecond; higher = darker)
     * @param bortle Bortle dark-sky class (1 = darkest, 8 = city sky)
     */
    public record SkyBrightnessResult(double sqm, int bortle) {
    }

    /**
     * JSON response from the lightpollutionmap.info QueryRaster endpoint.
     *
     * @param result artificial sky brightness in mcd/m²
     */
    record BrightnessResponse(@JsonProperty("result") Double result) {
    }
}
