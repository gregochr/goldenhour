package com.gregochr.goldenhour.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Health probe for the Anthropic Claude API.
 *
 * <p>Sends a lightweight GET to the models listing endpoint. Any HTTP response (including
 * 401/403) confirms the API is reachable; only connection-level errors produce DOWN.
 *
 * <p>Registered as a bean named {@code "claudeApi"} by {@link HealthProbeConfig}.
 */
public class ClaudeApiHealthIndicator implements HealthIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(ClaudeApiHealthIndicator.class);

    private static final String MODELS_URL = "https://api.anthropic.com/v1/models";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final RestClient restClient;
    private final String apiKey;

    /**
     * Constructs the indicator with the Anthropic API key and a dedicated {@link RestClient}
     * configured with a 5-second read timeout and default error handler suppressed.
     *
     * @param anthropicProperties Anthropic configuration
     */
    public ClaudeApiHealthIndicator(AnthropicProperties anthropicProperties) {
        this.apiKey = anthropicProperties.getApiKey();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultStatusHandler(status -> true, (req, resp) -> { /* any HTTP response = reachable */ })
                .build();
    }

    /**
     * Package-private constructor for testing with a mock {@link RestClient}.
     *
     * @param restClient the RestClient to use for probes
     * @param apiKey     the Anthropic API key
     */
    ClaudeApiHealthIndicator(RestClient restClient, String apiKey) {
        this.restClient = restClient;
        this.apiKey = apiKey;
    }

    @Override
    public Health health() {
        long start = System.currentTimeMillis();
        try {
            restClient.get()
                    .uri(MODELS_URL)
                    .headers(h -> {
                        h.set("x-api-key", apiKey);
                        h.set("anthropic-version", ANTHROPIC_VERSION);
                    })
                    .retrieve()
                    .toBodilessEntity();
            long latency = System.currentTimeMillis() - start;
            return Health.up()
                    .withDetail("latencyMs", latency)
                    .build();
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - start;
            LOG.debug("Claude API health probe failed in {}ms", latency, ex);
            return Health.down()
                    .withDetail("latencyMs", latency)
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
