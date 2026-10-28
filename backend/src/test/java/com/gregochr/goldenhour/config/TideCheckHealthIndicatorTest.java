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
 * Unit tests for {@link TideCheckHealthIndicator}.
 */
@ExtendWith(MockitoExtension.class)
class TideCheckHealthIndicatorTest {

    @Test
    @DisplayName("Reports UP with latencyMs when probe succeeds")
    void upWhenProbeSucceeds() {
        RestClient restClient = mock(RestClient.class);
        RestClientMocks.stubGetBodilessEntity(restClient, ResponseEntity.ok().build());

        TideCheckHealthIndicator indicator = new TideCheckHealthIndicator(restClient, "test-key");
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("latencyMs");
    }

    @Test
    @DisplayName("Reports DOWN when probe fails")
    void downWhenProbeFails() {
        RestClient restClient = mock(RestClient.class);
        RestClientMocks.stubGetBodilessEntityThrows(restClient, new ResourceAccessException("Timeout"));

        TideCheckHealthIndicator indicator = new TideCheckHealthIndicator(restClient, "test-key");
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

    @Test
    @DisplayName("Reports UNKNOWN when no API key configured")
    void unknownWhenNoApiKey() {
        RestClient restClient = mock(RestClient.class);

        TideCheckHealthIndicator indicator = new TideCheckHealthIndicator(restClient, "");
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails().get("reason")).isEqualTo("No API key configured");
    }

    @Test
    @DisplayName("Reports UNKNOWN when API key is null")
    void unknownWhenNullApiKey() {
        RestClient restClient = mock(RestClient.class);

        TideCheckHealthIndicator indicator = new TideCheckHealthIndicator(restClient, null);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    @DisplayName("Health details never carry the exception message, so the request URL's API key cannot leak")
    void downDoesNotLeakKeyFromIoErrorMessage() {
        RestClient restClient = mock(RestClient.class);
        RestClientMocks.stubGetBodilessEntityThrows(restClient, new ResourceAccessException(
                "I/O error on GET request for \"https://www.worldtides.info/api/v3"
                        + "?extremes&lat=0&lon=0&length=1&key=SECRETKEY\": timeout"));

        Health health = new TideCheckHealthIndicator(restClient, "SECRETKEY").health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("error")).isEqualTo("ResourceAccessException");
        assertThat(health.getDetails().toString()).doesNotContain("SECRETKEY");
        assertThat(health.getDetails()).doesNotContainKey("status");
    }

    @Test
    @DisplayName("Reports the HTTP status, never the message, when WorldTides returns an error response")
    void downWithStatusWhenErrorResponse() {
        RestClient restClient = mock(RestClient.class);
        RestClientMocks.stubGetBodilessEntityThrows(restClient,
                new RestClientResponseException("bad key=SECRETKEY", 401, "Unauthorized", null, null, null));

        Health health = new TideCheckHealthIndicator(restClient, "SECRETKEY").health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("status")).isEqualTo(401);
        assertThat(health.getDetails().get("error")).isEqualTo("RestClientResponseException");
        assertThat(health.getDetails().toString()).doesNotContain("SECRETKEY");
    }
}
