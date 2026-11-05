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

    @Test
    @DisplayName("The probe sends the API key and version headers to the models endpoint")
    void probeSendsAuthHeaders() {
        RestClient.Builder builder = RestClient.builder();
        org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .requestTo("https://api.anthropic.com/v1/models"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .method(org.springframework.http.HttpMethod.GET))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .header("x-api-key", "sk-test"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .header("anthropic-version", "2023-06-01"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess());

        Health health = new ClaudeApiHealthIndicator(builder.build(), "sk-test").health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        server.verify();
    }

    @Test
    @DisplayName("The production constructor builds an indicator from the configured key without probing")
    void productionConstructor_doesNotProbe() {
        AnthropicProperties properties = new AnthropicProperties();
        properties.setApiKey("sk-test");

        ClaudeApiHealthIndicator indicator = new ClaudeApiHealthIndicator(properties);

        assertThat(indicator).isInstanceOf(org.springframework.boot.health.contributor.HealthIndicator.class);
    }
}
