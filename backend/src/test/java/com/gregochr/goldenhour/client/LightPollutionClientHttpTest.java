package com.gregochr.goldenhour.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.util.RestClientMocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;

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
        client = new LightPollutionClient(restClient, new ObjectMapper(), props);
    }

    @Test
    @DisplayName("querySkyBrightness returns result when API returns a brightness value")
    void querySkyBrightness_success_returnsResult() {
        RestClientMocks.stubGet(restClient, String.class, "0.05");

        LightPollutionClient.SkyBrightnessResult result =
                client.querySkyBrightness(54.77, -1.57, "test-key");

        assertThat(result).isNotNull();
        assertThat(result.bortle()).isBetween(1, 8);
        assertThat(result.sqm()).isGreaterThan(18.0);
    }

    @Test
    @DisplayName("querySkyBrightness returns null when API response body is null")
    void querySkyBrightness_nullResponseBody_returnsNull() {
        RestClientMocks.stubGet(restClient, String.class, null);

        LightPollutionClient.SkyBrightnessResult result =
                client.querySkyBrightness(54.77, -1.57, "test-key");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("querySkyBrightness returns null when API response body is blank")
    void querySkyBrightness_blankResponseBody_returnsNull() {
        RestClientMocks.stubGet(restClient, String.class, "  ");

        LightPollutionClient.SkyBrightnessResult result =
                client.querySkyBrightness(54.77, -1.57, "test-key");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("querySkyBrightness returns null when JSON object has null result field")
    void querySkyBrightness_nullResultField_returnsNull() {
        RestClientMocks.stubGet(restClient, String.class, "{\"result\": null}");

        LightPollutionClient.SkyBrightnessResult result =
                client.querySkyBrightness(54.77, -1.57, "test-key");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("querySkyBrightness parses JSON object format as well as bare number")
    void querySkyBrightness_jsonObjectFormat_returnsResult() {
        RestClientMocks.stubGet(restClient, String.class, "{\"result\": 0.05}");

        LightPollutionClient.SkyBrightnessResult result =
                client.querySkyBrightness(54.77, -1.57, "test-key");

        assertThat(result).isNotNull();
        assertThat(result.bortle()).isBetween(1, 8);
    }

    @Test
    @DisplayName("querySkyBrightness returns null on RestClientException (network error)")
    void querySkyBrightness_networkError_returnsNull() {
        RestClientMocks.stubGetThrows(restClient, String.class, new RestClientException("Connection refused"));

        LightPollutionClient.SkyBrightnessResult result =
                client.querySkyBrightness(54.77, -1.57, "test-key");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("querySkyBrightness returns null on malformed JSON")
    void querySkyBrightness_malformedJson_returnsNull() {
        RestClientMocks.stubGet(restClient, String.class, "not valid json");

        LightPollutionClient.SkyBrightnessResult result =
                client.querySkyBrightness(54.77, -1.57, "test-key");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("querySkyBrightness maps bright urban sky to Bortle 8")
    void querySkyBrightness_brightUrbanSky_returnsBortle8() {
        RestClientMocks.stubGet(restClient, String.class, "20.0");

        LightPollutionClient.SkyBrightnessResult result =
                client.querySkyBrightness(51.5, -0.1, "key");

        assertThat(result).isNotNull();
        assertThat(result.bortle()).isEqualTo(8);
    }

    @Test
    @DisplayName("querySkyBrightness maps very dark remote sky to Bortle 1")
    void querySkyBrightness_darkRemoteSky_returnsBortle1() {
        RestClientMocks.stubGet(restClient, String.class, "0.001");

        LightPollutionClient.SkyBrightnessResult result =
                client.querySkyBrightness(57.0, -5.0, "key");

        assertThat(result).isNotNull();
        assertThat(result.bortle()).isEqualTo(1);
    }
}
