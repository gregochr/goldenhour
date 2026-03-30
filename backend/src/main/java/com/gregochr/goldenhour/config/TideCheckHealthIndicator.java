package com.gregochr.goldenhour.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

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

        String url = BASE_URL + "?extremes&lat=0&lon=0&length=1&key=" + apiKey;
        long start = System.currentTimeMillis();
        try {
            restClient.get().uri(url).retrieve().toBodilessEntity();
            long latency = System.currentTimeMillis() - start;
            return Health.up()
                    .withDetail("latencyMs", latency)
                    .build();
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - start;
            LOG.debug("WorldTides health probe failed in {}ms", latency, ex);
            return Health.down()
                    .withDetail("latencyMs", latency)
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
