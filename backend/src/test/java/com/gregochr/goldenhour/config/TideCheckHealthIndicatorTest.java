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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TideCheckHealthIndicator}.
 */
@ExtendWith(MockitoExtension.class)
class TideCheckHealthIndicatorTest {

    @Test
    @DisplayName("Reports UP with latencyMs when probe succeeds")
    void upWhenProbeSucceeds() {
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(restClient.get().uri(anyString()).retrieve().toBodilessEntity())
                .thenReturn(ResponseEntity.ok().build());

        TideCheckHealthIndicator indicator = new TideCheckHealthIndicator(restClient, "test-key");
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("latencyMs");
    }

    @Test
    @DisplayName("Reports DOWN when probe fails")
    void downWhenProbeFails() {
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(restClient.get().uri(anyString()).retrieve().toBodilessEntity())
                .thenThrow(new ResourceAccessException("Timeout"));

        TideCheckHealthIndicator indicator = new TideCheckHealthIndicator(restClient, "test-key");
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

    @Test
    @DisplayName("Reports UNKNOWN when no API key configured")
    void unknownWhenNoApiKey() {
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);

        TideCheckHealthIndicator indicator = new TideCheckHealthIndicator(restClient, "");
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails().get("reason")).isEqualTo("No API key configured");
    }

    @Test
    @DisplayName("Reports UNKNOWN when API key is null")
    void unknownWhenNullApiKey() {
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);

        TideCheckHealthIndicator indicator = new TideCheckHealthIndicator(restClient, null);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
    }
}
