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

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ClaudeApiHealthIndicator}.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeApiHealthIndicatorTest {

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("Reports UP with latencyMs when API is reachable")
    void upWhenApiReachable() {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        doReturn(uriSpec).when(restClient).get();
        doReturn(headersSpec).when(uriSpec).uri(anyString());
        doReturn(headersSpec).when(headersSpec).headers(any(Consumer.class));
        doReturn(responseSpec).when(headersSpec).retrieve();
        doReturn(ResponseEntity.ok().build()).when(responseSpec).toBodilessEntity();

        ClaudeApiHealthIndicator indicator = new ClaudeApiHealthIndicator(restClient, "test-key");
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("latencyMs");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("Reports DOWN when connection fails")
    void downWhenConnectionFails() {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        doReturn(uriSpec).when(restClient).get();
        doReturn(headersSpec).when(uriSpec).uri(anyString());
        doReturn(headersSpec).when(headersSpec).headers(any(Consumer.class));
        doReturn(responseSpec).when(headersSpec).retrieve();
        doThrow(new ResourceAccessException("Connection refused")).when(responseSpec).toBodilessEntity();

        ClaudeApiHealthIndicator indicator = new ClaudeApiHealthIndicator(restClient, "test-key");
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
        assertThat(health.getDetails().get("error").toString()).contains("Connection refused");
    }
}
