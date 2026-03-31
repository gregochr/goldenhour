package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.config.AuroraProperties;
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
 * Unit tests for {@link LightPollutionClient#querySkyBrightness(double, double, String)}.
 *
 * <p>HTTP-related paths — success, null response, and network failure.
 * The SQM/Bortle conversion logic is tested separately in {@link LightPollutionClientTest}.
 */
@ExtendWith(MockitoExtension.class)
class LightPollutionClientHttpTest {

    @Mock
    private RestClient restClient;

    private LightPollutionClient client;

    @BeforeEach
    void setUp() {
        AuroraProperties props = new AuroraProperties();
        client = new LightPollutionClient(restClient, props);
    }

    @Test
    @DisplayName("querySkyBrightness returns result when API returns a brightness value")
    void querySkyBrightness_success_returnsResult() {
        setupGetChain(new LightPollutionClient.BrightnessResponse(0.05));

        LightPollutionClient.SkyBrightnessResult result =
                client.querySkyBrightness(54.77, -1.57, "test-key");

        assertThat(result).isNotNull();
        assertThat(result.bortle()).isBetween(1, 8);
        assertThat(result.sqm()).isGreaterThan(18.0);
    }

    @Test
    @DisplayName("querySkyBrightness returns null when API response body is null")
    void querySkyBrightness_nullResponseBody_returnsNull() {
        setupGetChain(null);

        LightPollutionClient.SkyBrightnessResult result =
                client.querySkyBrightness(54.77, -1.57, "test-key");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("querySkyBrightness returns null when response result field is null")
    void querySkyBrightness_nullResultField_returnsNull() {
        setupGetChain(new LightPollutionClient.BrightnessResponse(null));

        LightPollutionClient.SkyBrightnessResult result =
                client.querySkyBrightness(54.77, -1.57, "test-key");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("querySkyBrightness returns null on RestClientException (network error)")
    void querySkyBrightness_networkError_returnsNull() {
        @SuppressWarnings("unchecked")
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) uriSpec);
        when(uriSpec.uri(any(java.net.URI.class)))
                .thenThrow(new RestClientException("Connection refused"));

        LightPollutionClient.SkyBrightnessResult result =
                client.querySkyBrightness(54.77, -1.57, "test-key");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("querySkyBrightness maps bright urban sky to Bortle 8")
    void querySkyBrightness_brightUrbanSky_returnsBortle8() {
        setupGetChain(new LightPollutionClient.BrightnessResponse(20.0));

        LightPollutionClient.SkyBrightnessResult result =
                client.querySkyBrightness(51.5, -0.1, "key");

        assertThat(result).isNotNull();
        assertThat(result.bortle()).isEqualTo(8);
    }

    @Test
    @DisplayName("querySkyBrightness maps very dark remote sky to Bortle 1")
    void querySkyBrightness_darkRemoteSky_returnsBortle1() {
        setupGetChain(new LightPollutionClient.BrightnessResponse(0.001));

        LightPollutionClient.SkyBrightnessResult result =
                client.querySkyBrightness(57.0, -5.0, "key");

        assertThat(result).isNotNull();
        assertThat(result.bortle()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setupGetChain(Object responseBody) {
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) uriSpec);
        when(uriSpec.uri(any(java.net.URI.class))).thenReturn((RestClient.RequestHeadersSpec) headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(Class.class))).thenReturn(responseBody);
    }
}
