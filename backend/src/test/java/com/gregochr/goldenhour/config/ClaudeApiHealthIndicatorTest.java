package com.gregochr.goldenhour.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.gregochr.goldenhour.util.RestClientMocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ClaudeApiHealthIndicator}.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeApiHealthIndicatorTest {

    @Test
    @DisplayName("Reports UP with latencyMs when API is reachable")
    void upWhenApiReachable() {
        RestClient restClient = mock(RestClient.class);
        RestClientMocks.stubGetBodilessEntity(restClient, ResponseEntity.ok().build());

        ClaudeApiHealthIndicator indicator = new ClaudeApiHealthIndicator(restClient, "test-key");
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("latencyMs");
    }

    @Test
    @DisplayName("Reports DOWN when connection fails")
    void downWhenConnectionFails() {
        RestClient restClient = mock(RestClient.class);
        RestClientMocks.stubGetBodilessEntityThrows(restClient, new ResourceAccessException("Connection refused"));

        ClaudeApiHealthIndicator indicator = new ClaudeApiHealthIndicator(restClient, "test-key");
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
        assertThat(health.getDetails().get("error")).isEqualTo("ResourceAccessException");
        assertThat(health.getDetails().toString()).doesNotContain("Connection refused");
    }

    @Test
    @DisplayName("Reports the HTTP status, never the message, when the probe gets an error response")
    void downWithStatusWhenErrorResponse() {
        RestClient restClient = mock(RestClient.class);
        RestClientMocks.stubGetBodilessEntityThrows(restClient,
                new RestClientResponseException("boom?key=SECRETKEY", 503, "Service Unavailable", null, null, null));

        Health health = new ClaudeApiHealthIndicator(restClient, "test-key").health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("status")).isEqualTo(503);
        assertThat(health.getDetails().get("error")).isEqualTo("RestClientResponseException");
        assertThat(health.getDetails().toString()).doesNotContain("SECRETKEY");
    }
}
