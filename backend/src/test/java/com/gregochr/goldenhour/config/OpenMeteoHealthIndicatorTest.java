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
 * Unit tests for {@link OpenMeteoHealthIndicator}.
 */
@ExtendWith(MockitoExtension.class)
class OpenMeteoHealthIndicatorTest {

    @Test
    @DisplayName("Reports UP with latencyMs when probe succeeds")
    void upWhenProbeSucceeds() {
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(restClient.get().uri(anyString()).retrieve().toBodilessEntity())
                .thenReturn(ResponseEntity.ok().build());

        OpenMeteoHealthIndicator indicator = new OpenMeteoHealthIndicator(restClient);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("latencyMs");
        assertThat((Long) health.getDetails().get("latencyMs")).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Reports DOWN with error detail when probe fails")
    void downWhenProbeFails() {
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(restClient.get().uri(anyString()).retrieve().toBodilessEntity())
                .thenThrow(new ResourceAccessException("Connection refused"));

        OpenMeteoHealthIndicator indicator = new OpenMeteoHealthIndicator(restClient);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("latencyMs");
        assertThat(health.getDetails()).containsKey("error");
        assertThat(health.getDetails().get("error").toString()).contains("Connection refused");
    }
}
