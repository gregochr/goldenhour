package com.gregochr.goldenhour.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Health probe for the Open-Meteo weather API.
 *
 * <p>Sends a lightweight forecast request (single grid cell, 1-day horizon) and
 * reports UP with latency if the response is successful, DOWN otherwise.
 *
 * <p>Registered as a bean named {@code "openMeteo"} by {@link HealthProbeConfig}.
 */
public class OpenMeteoHealthIndicator implements HealthIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(OpenMeteoHealthIndicator.class);

    /** Minimal probe URL — single coordinate, single variable, 1-day forecast. */
    private static final String PROBE_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=0&longitude=0"
                    + "&hourly=temperature_2m&forecast_days=1";

    private final RestClient restClient;

    /**
     * Constructs the indicator with a dedicated {@link RestClient} configured with a 5-second
     * read timeout.
     */
    public OpenMeteoHealthIndicator() {
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
     */
    OpenMeteoHealthIndicator(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Health health() {
        long start = System.currentTimeMillis();
        try {
            restClient.get().uri(PROBE_URL).retrieve().toBodilessEntity();
            long latency = System.currentTimeMillis() - start;
            return Health.up()
                    .withDetail("latencyMs", latency)
                    .build();
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - start;
            LOG.debug("Open-Meteo health probe failed in {}ms", latency, ex);
            return Health.down()
                    .withDetail("latencyMs", latency)
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
