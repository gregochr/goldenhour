package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.client.EnvironmentAgencyGaugeClient.GaugeReading;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link EnvironmentAgencyGaugeClient} against a {@link MockRestServiceServer}; no
 * real Environment Agency call is made.
 */
class EnvironmentAgencyGaugeClientTest {

    private static final String MEASURE = "E70939-level-tidal_level-Mean-15_min-mAOD";

    private MockRestServiceServer server;
    private EnvironmentAgencyGaugeClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new EnvironmentAgencyGaugeClient(builder.build());
    }

    @Test
    @DisplayName("Requests the measure's readings with _sorted, the date range and an explicit limit")
    void fetchReadings_sendsExpectedQuery() {
        server.expect(method(HttpMethod.GET))
                .andExpect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://environment.data.gov.uk/flood-monitoring/id/measures/" + MEASURE + "/readings?")))
                .andExpect(queryParam("startdate", "2026-10-24"))
                .andExpect(queryParam("enddate", "2026-10-31"))
                .andExpect(queryParam("_limit", "2000"))
                .andExpect(requestTo(org.hamcrest.Matchers.containsString("_sorted")))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        List<GaugeReading> readings =
                client.fetchReadings(MEASURE, LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 31));

        assertThat(readings).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("Parses items and returns them oldest first, dropping rows without a time or value")
    void fetchReadings_parsesAndSortsAscending() {
        String body = "{\"items\":["
                + "{\"dateTime\":\"2026-10-30T04:30:00Z\",\"value\":3.1},"
                + "{\"dateTime\":\"2026-10-30T04:15:00Z\",\"value\":2.9},"
                + "{\"dateTime\":\"2026-10-30T04:45:00Z\"},"
                + "{\"value\":1.0}]}";
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<GaugeReading> readings =
                client.fetchReadings(MEASURE, LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 31));

        assertThat(readings).containsExactly(
                new GaugeReading(Instant.parse("2026-10-30T04:15:00Z"), 2.9),
                new GaugeReading(Instant.parse("2026-10-30T04:30:00Z"), 3.1));
    }

    @Test
    @DisplayName("A server error propagates for the caller to log")
    void fetchReadings_serverError_throws() {
        server.expect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() ->
                client.fetchReadings(MEASURE, LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 31)))
                .isInstanceOf(RuntimeException.class);
    }
}
