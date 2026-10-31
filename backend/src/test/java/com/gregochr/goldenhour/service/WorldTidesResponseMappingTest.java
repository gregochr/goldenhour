package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.WorldTidesResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Proves {@link WorldTidesResponse} maps the provenance fields WorldTides returns (atlas, station,
 * request and response point, datum) and tolerates their absence, through the same HTTP message
 * conversion the production client uses.
 */
class WorldTidesResponseMappingTest {

    private static WorldTidesResponse read(String json) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        return builder.build().get().uri("http://localhost/x").retrieve().body(WorldTidesResponse.class);
    }

    @Test
    @DisplayName("maps the provenance fields when present and ignores unknown ones")
    void mapsProvenanceFields() {
        WorldTidesResponse response = read("""
                {"status":200,"callCount":2,"atlas":"FES2022","station":null,
                 "requestLat":54.4,"requestLon":-0.7,"responseLat":54.41,"responseLon":-0.69,
                 "responseDatum":"LAT","unknownField":1,
                 "extremes":[{"dt":1790000000,"height":1.5,"type":"High"}]}""");

        assertThat(response.getAtlas()).isEqualTo("FES2022");
        assertThat(response.getStation()).isNull();
        assertThat(response.getRequestLat()).isEqualTo(54.4);
        assertThat(response.getRequestLon()).isEqualTo(-0.7);
        assertThat(response.getResponseLat()).isEqualTo(54.41);
        assertThat(response.getResponseLon()).isEqualTo(-0.69);
        assertThat(response.getResponseDatum()).isEqualTo("LAT");
        assertThat(response.getExtremes()).hasSize(1);
    }

    @Test
    @DisplayName("leaves the provenance fields null when the response omits them")
    void missingFieldsMapToNull() {
        WorldTidesResponse response = read("{\"status\":200,\"extremes\":[]}");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getAtlas()).isNull();
        assertThat(response.getStation()).isNull();
        assertThat(response.getRequestLat()).isNull();
        assertThat(response.getRequestLon()).isNull();
        assertThat(response.getResponseLat()).isNull();
        assertThat(response.getResponseLon()).isNull();
        assertThat(response.getResponseDatum()).isNull();
    }
}
