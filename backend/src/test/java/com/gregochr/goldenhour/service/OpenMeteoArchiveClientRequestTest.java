package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.util.RestClientMocks;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Request-building and chunk-failure tests for {@link OpenMeteoArchiveClient}, run against
 * {@link MockRestServiceServer} so the real query parameters and the sleep/backoff paths execute.
 */
class OpenMeteoArchiveClientRequestTest {

    private static final LocalDate DATE = LocalDate.of(2026, 3, 7);
    private static final String HOURLY = "cloud_cover_low,cloud_cover_mid,cloud_cover_high";

    private MockRestServiceServer server;
    private OpenMeteoArchiveClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://archive-api.open-meteo.com");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenMeteoArchiveClient(new ObjectMapper(), builder.build());
    }

    @AfterEach
    void clearInterrupt() {
        // Some tests interrupt the thread to skip the real inter-chunk sleep.
        Thread.interrupted();
    }

    private static List<double[]> coords(int count) {
        List<double[]> coords = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            coords.add(new double[]{54.0 + i, -1.5 - i});
        }
        return coords;
    }

    private static String responseJson(int lowCloud) {
        return "{\"latitude\":54.0,\"longitude\":-1.5,\"hourly\":{\"time\":[\"2026-03-07T17:00\"],"
                + "\"cloud_cover_low\":[" + lowCloud + "],\"cloud_cover_mid\":[50],\"cloud_cover_high\":[30]}}";
    }

    private static String arrayJson(int count, int lowCloud) {
        List<String> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(responseJson(lowCloud));
        }
        return "[" + String.join(",", entries) + "]";
    }

    @Test
    @DisplayName("the archive request carries every coordinate, the day twice, the variables and UTC")
    void fetchArchiveBatch_buildsTheArchiveRequest() {
        server.expect(once(), requestTo(startsWith("https://archive-api.open-meteo.com/v1/archive")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("latitude", "54.0,55.0"))
                .andExpect(queryParam("longitude", "-1.5,-2.5"))
                .andExpect(queryParam("start_date", "2026-03-07"))
                .andExpect(queryParam("end_date", "2026-03-07"))
                .andExpect(queryParam("hourly", HOURLY))
                .andExpect(queryParam("timezone", "UTC"))
                .andRespond(withSuccess("[" + responseJson(11) + "," + responseJson(22) + "]",
                        MediaType.APPLICATION_JSON));

        List<OpenMeteoForecastResponse> results = client.fetchArchiveBatch(coords(2), DATE, HOURLY);

        server.verify();
        assertThat(results).hasSize(2);
        assertThat(results.get(0).getHourly().getCloudCoverLow()).containsExactly(11);
        assertThat(results.get(1).getHourly().getCloudCoverLow()).containsExactly(22);
    }

    @Test
    @DisplayName("after a 429 on one chunk the next chunk is still attempted, and the 429 chunk stays null")
    void fetchArchiveBatch_rateLimitedChunk_nextChunkStillAttempted() {
        int total = OpenMeteoClient.BATCH_COORD_LIMIT + 3;
        server.expect(once(), requestTo(startsWith("https://archive-api.open-meteo.com/v1/archive")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(once(), requestTo(startsWith("https://archive-api.open-meteo.com/v1/archive")))
                .andRespond(withSuccess(arrayJson(3, 77), MediaType.APPLICATION_JSON));
        // An interrupted thread makes the 60 s rate-limit backoff return immediately, flag restored.
        Thread.currentThread().interrupt();

        List<OpenMeteoForecastResponse> results = client.fetchArchiveBatch(coords(total), DATE, HOURLY);

        server.verify();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(results).hasSize(total);
        assertThat(results.subList(0, OpenMeteoClient.BATCH_COORD_LIMIT)).containsOnlyNulls();
        assertThat(results.get(OpenMeteoClient.BATCH_COORD_LIMIT).getHourly().getCloudCoverLow())
                .containsExactly(77);
    }

    @Test
    @DisplayName("a chunk failing with a message-less exception is reported by class name and leaves nulls")
    void fetchArchiveBatch_messageLessFailure_leavesNulls() {
        RestClient mockClient = org.mockito.Mockito.mock(RestClient.class);
        RestClientMocks.stubGetThrows(mockClient, String.class, new IllegalStateException());
        OpenMeteoArchiveClient failing = new OpenMeteoArchiveClient(new ObjectMapper(), mockClient);
        int total = OpenMeteoClient.BATCH_COORD_LIMIT + 2;
        Thread.currentThread().interrupt();

        List<OpenMeteoForecastResponse> results = failing.fetchArchiveBatch(coords(total), DATE, HOURLY);

        assertThat(results).hasSize(total);
        assertThat(results).containsOnlyNulls();
    }

    @Test
    @DisplayName("a chunk response longer than its chunk cannot spill past the end of the result list")
    void fetchArchiveBatch_oversizedFinalChunk_isClipped() {
        int total = OpenMeteoClient.BATCH_COORD_LIMIT + 2;
        server.expect(once(), requestTo(startsWith("https://archive-api.open-meteo.com/v1/archive")))
                .andRespond(withSuccess(arrayJson(OpenMeteoClient.BATCH_COORD_LIMIT, 10),
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith("https://archive-api.open-meteo.com/v1/archive")))
                .andRespond(withSuccess(arrayJson(6, 90), MediaType.APPLICATION_JSON));
        Thread.currentThread().interrupt();

        List<OpenMeteoForecastResponse> results = client.fetchArchiveBatch(coords(total), DATE, HOURLY);

        server.verify();
        assertThat(results).hasSize(total);
        assertThat(results.get(total - 1).getHourly().getCloudCoverLow()).containsExactly(90);
    }
}
