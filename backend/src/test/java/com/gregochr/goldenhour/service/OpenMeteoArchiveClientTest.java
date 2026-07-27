package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.util.RestClientMocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link OpenMeteoArchiveClient}.
 *
 * <p>The contract that matters is <em>positional alignment</em>: cloud verification lays out two
 * points per evaluation (solar horizon, then observer) and maps responses back by index, so a
 * misaligned or short result would silently swap the gap reading with the canvas reading rather
 * than fail. These tests pin length and order, including across chunk boundaries and failures.
 */
class OpenMeteoArchiveClientTest {

    private static final LocalDate DATE = LocalDate.of(2026, 3, 7);
    private static final String HOURLY = "cloud_cover_low,cloud_cover_mid,cloud_cover_high";

    private RestClient restClient;
    private OpenMeteoArchiveClient client;

    @BeforeEach
    void setUp() {
        restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        client = new OpenMeteoArchiveClient(new ObjectMapper(), restClient);
    }

    @Test
    @DisplayName("fetchArchiveBatch parses an array response one entry per coordinate, in order")
    void fetchArchiveBatch_parsesArrayInOrder() {
        RestClientMocks.stubGet(restClient, String.class,
                "[" + responseJson(10) + "," + responseJson(90) + "]");

        List<OpenMeteoForecastResponse> results =
                client.fetchArchiveBatch(coords(2), DATE, HOURLY);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getHourly().getCloudCoverLow()).containsExactly(10);
        assertThat(results.get(1).getHourly().getCloudCoverLow()).containsExactly(90);
    }

    @Test
    @DisplayName("fetchArchiveBatch parses a bare object when only one coordinate is requested")
    void fetchArchiveBatch_singleCoordinate_parsesBareObject() {
        // Open-Meteo returns an object rather than a one-element array for a single point.
        RestClientMocks.stubGet(restClient, String.class, responseJson(42));

        List<OpenMeteoForecastResponse> results =
                client.fetchArchiveBatch(coords(1), DATE, HOURLY);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getHourly().getCloudCoverLow()).containsExactly(42);
    }

    @Test
    @DisplayName("fetchArchiveBatch splits past the coordinate limit and keeps positions aligned")
    void fetchArchiveBatch_chunksAndKeepsAlignment() {
        int total = OpenMeteoClient.BATCH_COORD_LIMIT + 5;
        // Every chunk returns a full-size array, so alignment depends on the offset arithmetic.
        RestClientMocks.stubGetSequence(restClient, String.class,
                arrayJson(OpenMeteoClient.BATCH_COORD_LIMIT, 10),
                arrayJson(5, 90));

        List<OpenMeteoForecastResponse> results =
                client.fetchArchiveBatch(coords(total), DATE, HOURLY);

        assertThat(results).hasSize(total);
        assertThat(results.getFirst().getHourly().getCloudCoverLow()).containsExactly(10);
        // First entry after the chunk boundary must come from the second request.
        assertThat(results.get(OpenMeteoClient.BATCH_COORD_LIMIT).getHourly().getCloudCoverLow())
                .containsExactly(90);
        assertThat(results.getLast().getHourly().getCloudCoverLow()).containsExactly(90);
    }

    @Test
    @DisplayName("a failed chunk nulls only its own positions, leaving earlier chunks intact")
    void fetchArchiveBatch_partialChunkFailure_isolatesTheFailure() {
        int total = OpenMeteoClient.BATCH_COORD_LIMIT + 3;
        // Second (final) chunk comes back unparseable. Failing on the LAST chunk exercises the
        // per-chunk catch without triggering the backoff sleep, which only delays a NEXT chunk.
        RestClientMocks.stubGetSequence(restClient, String.class,
                arrayJson(OpenMeteoClient.BATCH_COORD_LIMIT, 25),
                "gateway timeout, not json");

        List<OpenMeteoForecastResponse> results =
                client.fetchArchiveBatch(coords(total), DATE, HOURLY);

        // Length is preserved so index-based mapping downstream stays correct.
        assertThat(results).hasSize(total);
        assertThat(results.subList(OpenMeteoClient.BATCH_COORD_LIMIT, total)).containsOnlyNulls();
    }

    @Test
    @DisplayName("fetchArchiveBatch returns nulls, not a short list, when a single chunk fails")
    void fetchArchiveBatch_failure_returnsNullsOfMatchingLength() {
        RestClientMocks.stubGetThrows(restClient, String.class, new RuntimeException("503"));

        List<OpenMeteoForecastResponse> results =
                client.fetchArchiveBatch(coords(3), DATE, HOURLY);

        // Length must match the request: callers map back by index, so a short list would
        // silently shift every subsequent evaluation's observations onto the wrong row.
        assertThat(results).hasSize(3).containsOnlyNulls();
    }

    @Test
    @DisplayName("a rate-limit envelope returned as HTTP 200 is treated as failure, not empty data")
    void fetchArchiveBatch_errorEnvelope_isNotMistakenForEmptyData() {
        // Open-Meteo answers a rate limit with HTTP 200 and this body. Nothing about it is
        // exceptional to the HTTP client, and it deserialises happily into a response whose
        // hourly block is null — which downstream reads as "the archive has no data here".
        // Undetected, a throttled backfill blanks its whole remaining backlog with no error,
        // and the anti-join then treats every one of those rows as verified forever.
        RestClientMocks.stubGet(restClient, String.class,
                "{\"error\":true,\"reason\":\"Hourly API request limit exceeded.\"}");

        List<OpenMeteoForecastResponse> results =
                client.fetchArchiveBatch(coords(2), DATE, HOURLY);

        // Nulls, not a parsed-but-empty response — so the caller records no observations
        // and the runner can stop rather than grinding on.
        assertThat(results).hasSize(2).containsOnlyNulls();
    }

    @Test
    @DisplayName("an error envelope inside a batch array is also rejected")
    void fetchArchiveBatch_errorEnvelopeInsideArray_isRejected() {
        RestClientMocks.stubGet(restClient, String.class,
                "[" + responseJson(10) + ",{\"error\":true,\"reason\":\"limit\"}]");

        assertThat(client.fetchArchiveBatch(coords(2), DATE, HOURLY))
                .hasSize(2).containsOnlyNulls();
    }

    @Test
    @DisplayName("fetchArchiveBatch returns nulls when the response body is unparseable")
    void fetchArchiveBatch_badJson_returnsNulls() {
        RestClientMocks.stubGet(restClient, String.class, "not json");

        assertThat(client.fetchArchiveBatch(coords(2), DATE, HOURLY))
                .hasSize(2).containsOnlyNulls();
    }

    @Test
    @DisplayName("fetchArchiveBatch handles no coordinates without calling the API")
    void fetchArchiveBatch_emptyCoords_returnsEmpty() {
        assertThat(client.fetchArchiveBatch(List.of(), DATE, HOURLY)).isEmpty();
        assertThat(client.fetchArchiveBatch(null, DATE, HOURLY)).isEmpty();
    }

    private List<double[]> coords(int count) {
        List<double[]> coords = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            coords.add(new double[]{54.0 + i * 0.01, -1.5 - i * 0.01});
        }
        return coords;
    }

    private String responseJson(int lowCloud) {
        return "{\"latitude\":54.0,\"longitude\":-1.5,\"hourly\":{"
                + "\"time\":[\"2026-03-07T17:00\"],"
                + "\"cloud_cover_low\":[" + lowCloud + "],"
                + "\"cloud_cover_mid\":[50],"
                + "\"cloud_cover_high\":[30]}}";
    }

    private String arrayJson(int count, int lowCloud) {
        List<String> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(responseJson(lowCloud));
        }
        return "[" + String.join(",", entries) + "]";
    }
}
