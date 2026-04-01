package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.model.PostcodeLookupResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PostcodesIoClient}.
 */
@ExtendWith(MockitoExtension.class)
class PostcodesIoClientTest {

    @Mock
    private RestClient restClient;

    private PostcodesIoClient client;

    @BeforeEach
    void setUp() {
        client = new PostcodesIoClient(restClient);
    }

    @Test
    @DisplayName("Valid postcode returns coordinates and place name")
    void lookup_validPostcode_returnsResult() {
        PostcodesIoClient.PostcodesIoResponse response = new PostcodesIoClient.PostcodesIoResponse(
                200,
                new PostcodesIoClient.PostcodesIoResponse.Result(
                        "DH1 3LE", 54.7761, -1.5733, "County Durham", "Elvet and Gilesgate", "Durham"));
        mockRestClientGet(response);

        PostcodeLookupResult result = client.lookup("DH1 3LE");

        assertThat(result.postcode()).isEqualTo("DH1 3LE");
        assertThat(result.latitude()).isEqualTo(54.7761);
        assertThat(result.longitude()).isEqualTo(-1.5733);
        assertThat(result.placeName()).isEqualTo("Durham, County Durham");
    }

    @Test
    @DisplayName("Normalises spaces and case in postcode")
    void lookup_unnormalisedPostcode_normalisesBeforeCall() {
        PostcodesIoClient.PostcodesIoResponse response = new PostcodesIoClient.PostcodesIoResponse(
                200,
                new PostcodesIoClient.PostcodesIoResponse.Result(
                        "DH1 3LE", 54.7761, -1.5733, "County Durham", null, null));
        mockRestClientGet(response);

        PostcodeLookupResult result = client.lookup("dh1  3le");

        assertThat(result.postcode()).isEqualTo("DH1 3LE");
    }

    @Test
    @DisplayName("Place name uses admin ward when parish is null")
    void lookup_noParish_usesAdminWard() {
        PostcodesIoClient.PostcodesIoResponse response = new PostcodesIoClient.PostcodesIoResponse(
                200,
                new PostcodesIoClient.PostcodesIoResponse.Result(
                        "NE66 1QN", 55.6087, -1.7114, "Northumberland", "Alnwick", null));
        mockRestClientGet(response);

        PostcodeLookupResult result = client.lookup("NE66 1QN");

        assertThat(result.placeName()).isEqualTo("Alnwick, Northumberland");
    }

    @Test
    @DisplayName("Place name falls back to admin district when ward matches")
    void lookup_wardMatchesDistrict_usesDistrictOnly() {
        PostcodesIoClient.PostcodesIoResponse response = new PostcodesIoClient.PostcodesIoResponse(
                200,
                new PostcodesIoClient.PostcodesIoResponse.Result(
                        "SW1A 1AA", 51.5014, -0.1419, "Westminster", "Westminster", null));
        mockRestClientGet(response);

        PostcodeLookupResult result = client.lookup("SW1A 1AA");

        assertThat(result.placeName()).isEqualTo("Westminster");
    }

    @Test
    @DisplayName("Place name falls back to postcode when all fields are null")
    void lookup_allFieldsNull_fallsBackToPostcode() {
        PostcodesIoClient.PostcodesIoResponse response = new PostcodesIoClient.PostcodesIoResponse(
                200,
                new PostcodesIoClient.PostcodesIoResponse.Result(
                        "AB1 2CD", 57.0, -2.0, null, null, null));
        mockRestClientGet(response);

        PostcodeLookupResult result = client.lookup("AB1 2CD");

        assertThat(result.placeName()).isEqualTo("AB1 2CD");
    }

    @Test
    @DisplayName("Null response throws PostcodeLookupException")
    void lookup_nullResponse_throws() {
        mockRestClientGet(null);

        assertThatThrownBy(() -> client.lookup("ZZ9 9ZZ"))
                .isInstanceOf(PostcodeLookupException.class)
                .hasMessageContaining("Invalid postcode");
    }

    @Test
    @DisplayName("Null result in response throws PostcodeLookupException")
    void lookup_nullResult_throws() {
        PostcodesIoClient.PostcodesIoResponse response = new PostcodesIoClient.PostcodesIoResponse(404, null);
        mockRestClientGet(response);

        assertThatThrownBy(() -> client.lookup("ZZ9 9ZZ"))
                .isInstanceOf(PostcodeLookupException.class)
                .hasMessageContaining("Invalid postcode");
    }

    @Test
    @DisplayName("REST exception wraps in PostcodeLookupException")
    void lookup_restException_wraps() {
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        doReturn(uriSpec).when(restClient).get();
        when(uriSpec.uri(anyString(), any(Object[].class))).thenThrow(
                new RuntimeException("Connection refused"));

        assertThatThrownBy(() -> client.lookup("DH1 3LE"))
                .isInstanceOf(PostcodeLookupException.class)
                .hasMessageContaining("Postcode lookup failed");
    }

    @SuppressWarnings("unchecked")
    private void mockRestClientGet(PostcodesIoClient.PostcodesIoResponse response) {
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        doReturn(uriSpec).when(restClient).get();
        doReturn(headersSpec).when(uriSpec).uri(anyString(), any(Object[].class));
        doReturn(responseSpec).when(headersSpec).retrieve();
        when(responseSpec.body(PostcodesIoClient.PostcodesIoResponse.class)).thenReturn(response);
    }
}
