package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.util.RestClientMocks;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link OpenMeteoHealthIndicator}.
 */
@ExtendWith(MockitoExtension.class)
class OpenMeteoHealthIndicatorTest {

    @Test
    @DisplayName("Reports UP with latencyMs when probe succeeds")
    void upWhenProbeSucceeds() {
        RestClient restClient = mock(RestClient.class);
        RestClientMocks.stubGetBodilessEntity(restClient, ResponseEntity.ok().build());

        OpenMeteoHealthIndicator indicator = new OpenMeteoHealthIndicator(restClient);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("latencyMs");
        assertThat((Long) health.getDetails().get("latencyMs")).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Reports DOWN with error detail when probe fails")
    void downWhenProbeFails() {
        RestClient restClient = mock(RestClient.class);
        RestClientMocks.stubGetBodilessEntityThrows(restClient, new ResourceAccessException("Connection refused"));

        OpenMeteoHealthIndicator indicator = new OpenMeteoHealthIndicator(restClient);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("latencyMs");
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

        Health health = new OpenMeteoHealthIndicator(restClient).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("status")).isEqualTo(503);
        assertThat(health.getDetails().get("error")).isEqualTo("RestClientResponseException");
        assertThat(health.getDetails().toString()).doesNotContain("SECRETKEY");
    }
}
