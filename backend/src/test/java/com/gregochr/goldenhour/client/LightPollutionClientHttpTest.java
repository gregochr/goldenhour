package com.gregochr.goldenhour.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LightPollutionClient#queryBortleClass(double, double, String)}.
 *
 * <p>HTTP-related paths — success, null response, and network failure.
 * The Bortle conversion logic is tested separately in {@link LightPollutionClientTest}.
 */
@ExtendWith(MockitoExtension.class)
class LightPollutionClientHttpTest {

    @Mock
    private RestClient restClient;

    private LightPollutionClient client;

    @BeforeEach
    void setUp() {
        client = new LightPollutionClient(restClient);
    }

    @Test
    @DisplayName("queryBortleClass returns Bortle class when API returns a brightness value")
    void queryBortleClass_success_returnsBortleClass() {
        setupGetChain(new LightPollutionClient.BrightnessResponse(0.05)); // Bortle 3

        Integer result = client.queryBortleClass(54.77, -1.57, "test-key");

        assertThat(result).isEqualTo(3);
    }

    @Test
    @DisplayName("queryBortleClass returns null when API response body is null")
    void queryBortleClass_nullResponseBody_returnsNull() {
        setupGetChain(null);

        Integer result = client.queryBortleClass(54.77, -1.57, "test-key");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("queryBortleClass returns null when response result field is null")
    void queryBortleClass_nullResultField_returnsNull() {
        setupGetChain(new LightPollutionClient.BrightnessResponse(null));

        Integer result = client.queryBortleClass(54.77, -1.57, "test-key");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("queryBortleClass returns null on RestClientException (network error)")
    void queryBortleClass_networkError_returnsNull() {
        @SuppressWarnings("unchecked")
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) uriSpec);
        when(uriSpec.uri(any(java.util.function.Function.class)))
                .thenThrow(new RestClientException("Connection refused"));

        Integer result = client.queryBortleClass(54.77, -1.57, "test-key");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("queryBortleClass maps bright urban sky to Bortle 9")
    void queryBortleClass_brightUrbanSky_returnsBortle9() {
        setupGetChain(new LightPollutionClient.BrightnessResponse(20.0)); // > 15 mcd → Bortle 9

        Integer result = client.queryBortleClass(51.5, -0.1, "key");

        assertThat(result).isEqualTo(9);
    }

    @Test
    @DisplayName("queryBortleClass maps very dark remote sky to Bortle 1")
    void queryBortleClass_darkRemoteSky_returnsBortle1() {
        setupGetChain(new LightPollutionClient.BrightnessResponse(0.005)); // < 0.01 mcd → Bortle 1

        Integer result = client.queryBortleClass(57.0, -5.0, "key");

        assertThat(result).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setupGetChain(Object responseBody) {
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) uriSpec);
        when(uriSpec.uri(any(java.util.function.Function.class))).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(Class.class))).thenReturn(responseBody);
    }
}
