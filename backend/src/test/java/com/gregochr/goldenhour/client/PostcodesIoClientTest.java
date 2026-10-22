package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.model.PostcodeLookupResult;
import com.gregochr.goldenhour.util.RestClientMocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        RestClientMocks.stubGet(restClient, PostcodesIoClient.PostcodesIoResponse.class, response);

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
        RestClientMocks.stubGet(restClient, PostcodesIoClient.PostcodesIoResponse.class, response);

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
        RestClientMocks.stubGet(restClient, PostcodesIoClient.PostcodesIoResponse.class, response);

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
        RestClientMocks.stubGet(restClient, PostcodesIoClient.PostcodesIoResponse.class, response);

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
        RestClientMocks.stubGet(restClient, PostcodesIoClient.PostcodesIoResponse.class, response);

        PostcodeLookupResult result = client.lookup("AB1 2CD");

        assertThat(result.placeName()).isEqualTo("AB1 2CD");
    }

    @Test
    @DisplayName("Null response throws PostcodeLookupException")
    void lookup_nullResponse_throws() {
        RestClientMocks.stubGet(restClient, PostcodesIoClient.PostcodesIoResponse.class, null);

        assertThatThrownBy(() -> client.lookup("ZZ9 9ZZ"))
                .isInstanceOf(PostcodeLookupException.class)
                .hasMessageContaining("Invalid postcode");
    }

    @Test
    @DisplayName("Null result in response throws PostcodeLookupException")
    void lookup_nullResult_throws() {
        PostcodesIoClient.PostcodesIoResponse response = new PostcodesIoClient.PostcodesIoResponse(404, null);
        RestClientMocks.stubGet(restClient, PostcodesIoClient.PostcodesIoResponse.class, response);

        assertThatThrownBy(() -> client.lookup("ZZ9 9ZZ"))
                .isInstanceOf(PostcodeLookupException.class)
                .hasMessageContaining("Invalid postcode");
    }

    @Test
    @DisplayName("A transport failure is PostcodeLookupUnavailableException with the fixed message, "
            + "never the upstream's text")
    void lookup_transportFailure_isUnavailable() {
        RestClientMocks.stubGetThrows(restClient, PostcodesIoClient.PostcodesIoResponse.class,
                new RuntimeException("Connection refused"));

        assertThatThrownBy(() -> client.lookup("DH1 3LE"))
                .isInstanceOf(PostcodeLookupUnavailableException.class)
                .hasMessage("Postcode lookup is unavailable right now");
    }

    @Test
    @DisplayName("An upstream 404 is 'not found' (PostcodeLookupException), a 500 is 'unavailable'")
    void lookup_statusClassification() {
        RestClientMocks.stubGetThrows(restClient, PostcodesIoClient.PostcodesIoResponse.class,
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY,
                        new byte[0], null));
        assertThatThrownBy(() -> client.lookup("ZZ9 9ZZ"))
                .isInstanceOf(PostcodeLookupException.class)
                .hasMessage("Invalid postcode");

        RestClientMocks.stubGetThrows(restClient, PostcodesIoClient.PostcodesIoResponse.class,
                HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom",
                        HttpHeaders.EMPTY, new byte[0], null));
        assertThatThrownBy(() -> client.lookup("DH1 3LE"))
                .isInstanceOf(PostcodeLookupUnavailableException.class);
    }

    // ---- reverseLookup ----

    private static PostcodesIoClient.PostcodesIoResponse.Result durham() {
        return new PostcodesIoClient.PostcodesIoResponse.Result(
                "DH1 3LE", 54.7761, -1.5733, "County Durham", "Elvet and Gilesgate", "Durham");
    }

    @Test
    @DisplayName("reverseLookup maps the first result through the same place-name rules as lookup")
    void reverseLookup_hit_returnsResult() {
        RestClientMocks.stubGet(restClient, PostcodesIoClient.PostcodesIoReverseResponse.class,
                new PostcodesIoClient.PostcodesIoReverseResponse(200, List.of(durham())));

        PostcodeLookupResult result = client.reverseLookup(54.776, -1.575);

        assertThat(result).isEqualTo(
                new PostcodeLookupResult("DH1 3LE", 54.7761, -1.5733, "Durham, County Durham"));
    }

    @Test
    @DisplayName("reverseLookup with result: [] throws PostcodeLookupException with the fixed message")
    void reverseLookup_emptyResult_throwsNotFound() {
        RestClientMocks.stubGet(restClient, PostcodesIoClient.PostcodesIoReverseResponse.class,
                new PostcodesIoClient.PostcodesIoReverseResponse(200, List.of()));

        assertThatThrownBy(() -> client.reverseLookup(0.0, 0.0))
                .isInstanceOf(PostcodeLookupException.class)
                .hasMessage("No UK postcode found for that location");
    }

    @Test
    @DisplayName("reverseLookup with result: null throws PostcodeLookupException")
    void reverseLookup_nullResult_throwsNotFound() {
        RestClientMocks.stubGet(restClient, PostcodesIoClient.PostcodesIoReverseResponse.class,
                new PostcodesIoClient.PostcodesIoReverseResponse(200, null));

        assertThatThrownBy(() -> client.reverseLookup(0.0, 0.0))
                .isInstanceOf(PostcodeLookupException.class);
    }

    @Test
    @DisplayName("reverseLookup with a null body throws PostcodeLookupException")
    void reverseLookup_nullBody_throwsNotFound() {
        RestClientMocks.stubGet(restClient, PostcodesIoClient.PostcodesIoReverseResponse.class, null);

        assertThatThrownBy(() -> client.reverseLookup(0.0, 0.0))
                .isInstanceOf(PostcodeLookupException.class);
    }

    @Test
    @DisplayName("reverseLookup with a null first element throws PostcodeLookupException")
    void reverseLookup_nullElement_throwsNotFound() {
        RestClientMocks.stubGet(restClient, PostcodesIoClient.PostcodesIoReverseResponse.class,
                new PostcodesIoClient.PostcodesIoReverseResponse(200, Collections.singletonList(null)));

        assertThatThrownBy(() -> client.reverseLookup(0.0, 0.0))
                .isInstanceOf(PostcodeLookupException.class);
    }

    @Test
    @DisplayName("reverseLookup against a 500 is PostcodeLookupUnavailableException with the fixed "
            + "message — never the upstream's body")
    void reverseLookup_serverError_isUnavailable() {
        RestClientMocks.stubGetThrows(restClient, PostcodesIoClient.PostcodesIoReverseResponse.class,
                HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
                        "secret upstream detail", HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> client.reverseLookup(54.776, -1.575))
                .isInstanceOf(PostcodeLookupUnavailableException.class)
                .hasMessage("Postcode lookup is unavailable right now")
                .hasMessageNotContaining("secret");
    }

    @Test
    @DisplayName("reverseLookup against a transport failure is PostcodeLookupUnavailableException")
    void reverseLookup_transportFailure_isUnavailable() {
        RestClientMocks.stubGetThrows(restClient, PostcodesIoClient.PostcodesIoReverseResponse.class,
                new ResourceAccessException("I/O error"));

        assertThatThrownBy(() -> client.reverseLookup(54.776, -1.575))
                .isInstanceOf(PostcodeLookupUnavailableException.class);
    }
}
