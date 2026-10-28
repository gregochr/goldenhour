package com.gregochr.goldenhour.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;

/**
 * Health probe for the WorldTides API.
 *
 * <p>Sends a minimal extremes request and reports UP with latency if successful, DOWN otherwise.
 * Returns UNKNOWN if no API key is configured (avoids wasting paid calls).
 *
 * <p>Registered as a bean named {@code "tideCheck"} by {@link HealthProbeConfig}.
 */
public class TideCheckHealthIndicator implements HealthIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(TideCheckHealthIndicator.class);

    private static final String BASE_URL = "https://www.worldtides.info/api/v3";

    private final RestClient restClient;
    private final String apiKey;

    /**
     * Constructs the indicator with the WorldTides API key and a dedicated {@link RestClient}
     * configured with a 5-second read timeout.
     *
     * @param worldTidesProperties WorldTides configuration
     */
    public TideCheckHealthIndicator(WorldTidesProperties worldTidesProperties) {
        this.apiKey = worldTidesProperties.getApiKey();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /**
     * Package-private constructor for testing with a mock {@link RestClient}.
     *
     * @param restClient the RestClient to use for probes
     * @param apiKey     the WorldTides API key
     */
    TideCheckHealthIndicator(RestClient restClient, String apiKey) {
        this.restClient = restClient;
        this.apiKey = apiKey;
    }

    @Override
    public Health health() {
        if (apiKey == null || apiKey.isBlank()) {
            return Health.unknown()
                    .withDetail("reason", "No API key configured")
                    .build();
        }

        URI uri = UriComponentsBuilder.fromUriString(BASE_URL)
                .queryParam("extremes")
                .queryParam("lat", 0)
                .queryParam("lon", 0)
                .queryParam("length", 1)
                .queryParam("key", apiKey)
                .build()
                .encode()
                .toUri();
        long start = System.currentTimeMillis();
        try {
            restClient.get().uri(uri).retrieve().toBodilessEntity();
            long latency = System.currentTimeMillis() - start;
            return Health.up()
                    .withDetail("latencyMs", latency)
                    .build();
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - start;
            LOG.debug("WorldTides health probe failed in {}ms", latency, ex);
            // Never the message: Spring's I/O errors quote the full request URL, key included.
            Health.Builder down = Health.down()
                    .withDetail("latencyMs", latency)
                    .withDetail("error", ex.getClass().getSimpleName());
            if (ex instanceof RestClientResponseException responseEx) {
                down.withDetail("status", responseEx.getStatusCode().value());
            }
            return down.build();
        }
    }
}
