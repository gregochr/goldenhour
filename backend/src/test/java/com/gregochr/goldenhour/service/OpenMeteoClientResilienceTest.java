package com.gregochr.goldenhour.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.client.OpenMeteoAirQualityApi;
import com.gregochr.goldenhour.entity.ServiceName;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.PrefetchCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Chunking, retry and partial-failure behaviour of {@link OpenMeteoClient}'s batch methods:
 * {@code fetchForecastBriefingBatch}, {@code fetchAirQualityBatchResilient} (both run through the
 * same scenarios) and {@code fetchForecastBatch}.
 *
 * <p>Every request is matched on its endpoint path and exact latitude list, so a chunk that is
 * split on the wrong boundary, sent out of order or mapped back to the wrong slot fails here. Each
 * stubbed response echoes the coordinates it was asked for in a marker field, so the merged list
 * can be checked slot by slot against the input. All waits are recorded through the client's
 * {@code sleeper} seam; nothing sleeps and nothing leaves the JVM.
 */
class OpenMeteoClientResilienceTest {

    private static final int CHUNK = OpenMeteoClient.BATCH_COORD_LIMIT;
    private static final long INTER_CHUNK_MS = 111L;
    private static final long RATE_LIMIT_MS = 222L;
    private static final long RETRY_BASE_MS = 7L;
    private static final long TIMEOUT_RETRY_BASE_MS = 1_000L;
    private static final long T0 = Instant.parse("2026-11-02T13:47:00Z").toEpochMilli();
    private static final String FORECAST_PATH = "/v1/forecast";
    private static final String AIR_PATH = "/v1/air-quality";
    private static final String CONCURRENT_BODY = "{\"error\":true,\"reason\":\"Too many concurrent requests\"}";

    // ── The two resilient batch methods run through identical scenarios ──────────────────────

    /** One resilient batch method, described well enough to run the same scenarios against it. */
    private record Target(String name, String path, String kind, ServiceName service,
            String exhaustedPrefix, boolean forecast) {

        @Override
        public String toString() {
            return name;
        }

        OpenMeteoClient client(RestClient mockClient, List<Long> sleeps) {
            OpenMeteoClient client = new OpenMeteoClient(null, null, new ObjectMapper(),
                    forecast ? mockClient : null, forecast ? null : mockClient);
            client.interChunkDelayMs = INTER_CHUNK_MS;
            client.rateLimitBackoffMs = RATE_LIMIT_MS;
            client.chunkRetryBackoffMs = RETRY_BASE_MS;
            client.timeoutRetryBackoffMs = TIMEOUT_RETRY_BASE_MS;
            client.sleeper = sleeps::add;
            client.elapsedClockMs = () -> T0;
            return client;
        }

        List<?> call(OpenMeteoClient client, List<double[]> coords, Consumer<PrefetchCall> sink) {
            return forecast ? client.fetchForecastBriefingBatch(coords, sink)
                    : client.fetchAirQualityBatchResilient(coords, sink);
        }

        List<?> callPlain(OpenMeteoClient client, List<double[]> coords) {
            return forecast ? client.fetchForecastBriefingBatch(coords)
                    : client.fetchAirQualityBatchResilient(coords);
        }

        /** A response body that echoes each coordinate's latitude in the field {@link #marker} reads. */
        String body(List<double[]> chunk) {
            return chunk.stream().map(c -> forecast
                            ? "{\"latitude\":" + c[0] + ",\"longitude\":" + c[1] + "}"
                            : "{\"hourly\":{\"pm2_5\":[" + c[0] + "]}}")
                    .collect(Collectors.joining(",", "[", "]"));
        }

        double marker(Object response) {
            return forecast ? ((OpenMeteoForecastResponse) response).getLatitude()
                    : ((OpenMeteoAirQualityResponse) response).getHourly().getPm25().get(0);
        }
    }

    private static final Target BRIEFING = new Target("fetchForecastBriefingBatch", FORECAST_PATH,
            "forecast", ServiceName.OPEN_METEO_FORECAST, "Chunk", true);
    private static final Target AIR = new Target("fetchAirQualityBatchResilient", AIR_PATH,
            "air-quality", ServiceName.OPEN_METEO_AIR_QUALITY, "Air quality chunk", false);

    static Stream<Target> targets() {
        return Stream.of(BRIEFING, AIR);
    }

    static Stream<Arguments> targetsAndSizes() {
        List<Arguments> args = new ArrayList<>();
        for (Target target : List.of(BRIEFING, AIR)) {
            args.add(Arguments.of(target, 1, List.of(1)));
            args.add(Arguments.of(target, 19, List.of(19)));
            args.add(Arguments.of(target, 20, List.of(20)));
            args.add(Arguments.of(target, 21, List.of(20, 1)));
            args.add(Arguments.of(target, 40, List.of(20, 20)));
            args.add(Arguments.of(target, 41, List.of(20, 20, 1)));
            args.add(Arguments.of(target, 59, List.of(20, 20, 19)));
        }
        return args.stream();
    }

    @ParameterizedTest(name = "{0} with {1} coords splits into chunks {2}")
    @MethodSource("targetsAndSizes")
    @DisplayName("the batch is split on 20-coordinate boundaries, sent in order and merged slot for slot")
    void chunkBoundaries_splitSendInOrderAndMergeIntoTheRightSlots(Target target, int size,
            List<Integer> expectedChunkSizes) {
        RestClient http = deepMock();
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = target.client(http, sleeps);
        List<double[]> coords = coords(size);
        List<List<double[]>> chunks = chunks(coords);
        for (List<double[]> chunk : chunks) {
            stubChunk(http, target.path(), chunk, target.body(chunk));
        }
        List<PrefetchCall> calls = new ArrayList<>();

        List<?> results = target.call(client, coords, calls::add);

        assertThat(chunks.stream().map(List::size).toList()).isEqualTo(expectedChunkSizes);
        verifyRequests(http, target.path(), chunks);
        assertThat(results).hasSize(size);
        for (int slot = 0; slot < size; slot++) {
            assertThat(target.marker(results.get(slot))).as("slot %d", slot).isEqualTo(coords.get(slot)[0]);
        }
        assertThat(sleeps).as("one inter-chunk pause between chunks, none before the first or after the last")
                .containsExactlyElementsOf(Collections.nCopies(expectedChunkSizes.size() - 1, INTER_CHUNK_MS));
        int total = expectedChunkSizes.size();
        assertThat(calls).extracting(PrefetchCall::label).containsExactlyElementsOf(IntStream.rangeClosed(1, total)
                .mapToObj(i -> "batch-prefetch " + target.kind() + " chunk " + i + "/" + total).toList());
        assertThat(calls).extracting(PrefetchCall::coordsRequested).containsExactlyElementsOf(expectedChunkSizes);
        assertThat(calls).extracting(PrefetchCall::coordsReturned).containsExactlyElementsOf(expectedChunkSizes);
        assertThat(calls).extracting(PrefetchCall::service).containsOnly(target.service());
        assertThat(calls).extracting(PrefetchCall::succeeded).containsOnly(true);
        assertThat(calls).extracting(PrefetchCall::statusCode).containsOnly(200);
        assertThat(calls).extracting(PrefetchCall::attempts).containsOnly(1);
        assertThat(calls).extracting(PrefetchCall::error).containsOnlyNulls();
        assertThat(calls).extracting(PrefetchCall::durationMs).allMatch(ms -> ms >= 0 && ms < 30_000);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    @DisplayName("an empty input returns an empty list, sleeps nothing and reports one empty row")
    void emptyInput_returnsEmptyList(Target target) {
        RestClient http = deepMock();
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = target.client(http, sleeps);
        stubChunk(http, target.path(), List.of(), "[]");
        List<PrefetchCall> calls = new ArrayList<>();

        List<?> results = target.call(client, List.of(), calls::add);

        assertThat(results).isEmpty();
        assertThat(sleeps).isEmpty();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).coordsRequested()).isZero();
        assertThat(calls.get(0).coordsReturned()).isZero();
        assertThat(calls.get(0).succeeded()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    @DisplayName("the one-argument overload returns the same merged result as the sink overload")
    void plainOverload_mergesLikeTheSinkOverload(Target target) {
        RestClient http = deepMock();
        OpenMeteoClient client = target.client(http, new ArrayList<>());
        List<double[]> coords = coords(25);
        List<List<double[]>> chunks = chunks(coords);
        for (List<double[]> chunk : chunks) {
            stubChunk(http, target.path(), chunk, target.body(chunk));
        }

        List<?> results = target.callPlain(client, coords);

        assertThat(results).hasSize(25);
        for (int slot = 0; slot < 25; slot++) {
            assertThat(target.marker(results.get(slot))).isEqualTo(coords.get(slot)[0]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    @DisplayName("a chunk response with a null element counts only the populated entries as returned")
    void nullElementInResponse_isCountedOutOfCoordsReturned(Target target) {
        RestClient http = deepMock();
        OpenMeteoClient client = target.client(http, new ArrayList<>());
        List<double[]> coords = coords(3);
        String populated = target.body(coords.subList(0, 1));
        String body = populated.substring(0, populated.length() - 1) + ",null]";
        stubChunk(http, target.path(), coords, body);
        List<PrefetchCall> calls = new ArrayList<>();

        List<?> results = target.call(client, coords, calls::add);

        assertThat(results).hasSize(2);
        assertThat(results.get(1)).isNull();
        assertThat(calls.get(0).coordsRequested()).isEqualTo(3);
        assertThat(calls.get(0).coordsReturned()).isEqualTo(1);
    }

    // ── Retry on transient failure ───────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    @DisplayName("a 502 on the middle chunk is retried once after the base backoff, then the batch is whole")
    void transientFailure_retriedOnceThenSucceeds(Target target) {
        RestClient http = deepMock();
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = target.client(http, sleeps);
        List<double[]> coords = coords(45);
        List<List<double[]>> chunks = chunks(coords);
        stubChunk(http, target.path(), chunks.get(0), target.body(chunks.get(0)));
        stubChunk(http, target.path(), chunks.get(1), badGateway(), target.body(chunks.get(1)));
        stubChunk(http, target.path(), chunks.get(2), target.body(chunks.get(2)));
        List<PrefetchCall> calls = new ArrayList<>();

        List<?> results = target.call(client, coords, calls::add);

        assertThat(results).hasSize(45).doesNotContainNull();
        for (int slot = 0; slot < 45; slot++) {
            assertThat(target.marker(results.get(slot))).as("slot %d", slot).isEqualTo(coords.get(slot)[0]);
        }
        verifyRequests(http, target.path(), List.of(chunks.get(0), chunks.get(1), chunks.get(1), chunks.get(2)));
        assertThat(sleeps).containsExactly(INTER_CHUNK_MS, RETRY_BASE_MS, INTER_CHUNK_MS);
        assertThat(calls).extracting(PrefetchCall::attempts).containsExactly(1, 2, 1);
        assertThat(calls).extracting(PrefetchCall::succeeded).containsOnly(true);
        assertThat(calls).extracting(PrefetchCall::coordsReturned).containsExactly(20, 20, 5);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    @DisplayName("two failures then success doubles the backoff and reports three attempts")
    void transientFailureTwice_backoffDoubles(Target target) {
        RestClient http = deepMock();
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = target.client(http, sleeps);
        List<double[]> coords = coords(25);
        List<List<double[]>> chunks = chunks(coords);
        stubChunk(http, target.path(), chunks.get(0), connectionReset(), connectionReset(),
                target.body(chunks.get(0)));
        stubChunk(http, target.path(), chunks.get(1), target.body(chunks.get(1)));
        List<PrefetchCall> calls = new ArrayList<>();

        List<?> results = target.call(client, coords, calls::add);

        assertThat(results).hasSize(25).doesNotContainNull();
        assertThat(sleeps).containsExactly(RETRY_BASE_MS, 2 * RETRY_BASE_MS, INTER_CHUNK_MS);
        assertThat(calls).extracting(PrefetchCall::attempts).containsExactly(3, 1);
        assertThat(calls).extracting(PrefetchCall::succeeded).containsOnly(true);
    }

    // ── Retry exhaustion and partial results ─────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    @DisplayName("a chunk that fails all three attempts leaves nulls in its slots and the others intact")
    void retriesExhausted_nullsForThatChunkOnly(Target target) {
        RestClient http = deepMock();
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = target.client(http, sleeps);
        List<double[]> coords = coords(45);
        List<List<double[]>> chunks = chunks(coords);
        stubChunk(http, target.path(), chunks.get(0), target.body(chunks.get(0)));
        stubChunk(http, target.path(), chunks.get(1), badGateway(), badGateway(), badGateway());
        stubChunk(http, target.path(), chunks.get(2), target.body(chunks.get(2)));
        List<PrefetchCall> calls = new ArrayList<>();

        List<?> results = target.call(client, coords, calls::add);

        assertThat(results).hasSize(45);
        for (int slot = 0; slot < 45; slot++) {
            if (slot >= 20 && slot < 40) {
                assertThat(results.get(slot)).as("failed chunk slot %d", slot).isNull();
            } else {
                assertThat(target.marker(results.get(slot))).as("slot %d", slot).isEqualTo(coords.get(slot)[0]);
            }
        }
        verifyRequests(http, target.path(), List.of(chunks.get(0), chunks.get(1), chunks.get(1), chunks.get(1),
                chunks.get(2)));
        assertThat(sleeps).as("no backoff after the last attempt, and the loop carries on to the next chunk")
                .containsExactly(INTER_CHUNK_MS, RETRY_BASE_MS, 2 * RETRY_BASE_MS, INTER_CHUNK_MS);
        assertThat(calls).extracting(PrefetchCall::succeeded).containsExactly(true, false, true);
        assertThat(calls).extracting(PrefetchCall::coordsReturned).containsExactly(20, 0, 5);
        PrefetchCall failed = calls.get(1);
        assertThat(failed.attempts()).isEqualTo(3);
        assertThat(failed.durationMs()).isBetween(0L, 30_000L);
        assertThat(failed.statusCode()).isEqualTo(502);
        assertThat(failed.error()).isEqualTo(target.exhaustedPrefix() + " 2/3 failed after 3 attempts");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    @DisplayName("the first and last chunks failing leaves only the middle chunk populated")
    void firstAndLastChunksFail_middleSurvives(Target target) {
        RestClient http = deepMock();
        OpenMeteoClient client = target.client(http, new ArrayList<>());
        List<double[]> coords = coords(45);
        List<List<double[]>> chunks = chunks(coords);
        stubChunk(http, target.path(), chunks.get(0), badRequest());
        stubChunk(http, target.path(), chunks.get(1), target.body(chunks.get(1)));
        stubChunk(http, target.path(), chunks.get(2), badRequest());

        List<?> results = target.callPlain(client, coords);

        assertThat(results).hasSize(45);
        assertThat(results.subList(0, 20)).containsOnlyNulls();
        assertThat(results.subList(40, 45)).containsOnlyNulls();
        for (int slot = 20; slot < 40; slot++) {
            assertThat(target.marker(results.get(slot))).isEqualTo(coords.get(slot)[0]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    @DisplayName("a non-transient 400 is not retried and does not trigger the rate-limit backoff")
    void nonTransientFailure_notRetriedAndNoRateLimitBackoff(Target target) {
        RestClient http = deepMock();
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = target.client(http, sleeps);
        List<double[]> coords = coords(45);
        List<List<double[]>> chunks = chunks(coords);
        stubChunk(http, target.path(), chunks.get(0), badRequest());
        stubChunk(http, target.path(), chunks.get(1), target.body(chunks.get(1)));
        stubChunk(http, target.path(), chunks.get(2), target.body(chunks.get(2)));
        List<PrefetchCall> calls = new ArrayList<>();

        List<?> results = target.call(client, coords, calls::add);

        verifyRequests(http, target.path(), chunks);
        assertThat(results.subList(0, 20)).containsOnlyNulls();
        assertThat(results.subList(20, 45)).doesNotContainNull();
        assertThat(sleeps).containsExactly(INTER_CHUNK_MS, INTER_CHUNK_MS);
        assertThat(calls.get(0).succeeded()).isFalse();
        assertThat(calls.get(0).statusCode()).isEqualTo(400);
        assertThat(calls.get(0).attempts()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    @DisplayName("a minutely 429 is not retried; only the next pause is the long backoff, then pauses revert")
    void rateLimited_onlyTheNextPauseIsTheLongBackoff(Target target) {
        RestClient http = deepMock();
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = target.client(http, sleeps);
        List<double[]> coords = coords(61);
        List<List<double[]>> chunks = chunks(coords);
        stubChunk(http, target.path(), chunks.get(0), target.body(chunks.get(0)));
        stubChunk(http, target.path(), chunks.get(1), minutelyLimit());
        stubChunk(http, target.path(), chunks.get(2), target.body(chunks.get(2)));
        stubChunk(http, target.path(), chunks.get(3), target.body(chunks.get(3)));

        List<?> results = target.callPlain(client, coords);

        verifyRequests(http, target.path(), chunks);
        assertThat(sleeps).containsExactly(INTER_CHUNK_MS, RATE_LIMIT_MS, INTER_CHUNK_MS);
        assertThat(results.subList(20, 40)).containsOnlyNulls();
        assertThat(results.subList(0, 20)).doesNotContainNull();
        assertThat(results.subList(40, 61)).doesNotContainNull();
    }

    // ── A batch that fits in one chunk has no isolation: it throws ───────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    @DisplayName("a single chunk that exhausts its retries throws, naming the attempts and keeping the cause")
    void singleChunk_exhausted_throwsWithCause(Target target) {
        RestClient http = deepMock();
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = target.client(http, sleeps);
        List<double[]> coords = coords(CHUNK);
        HttpServerErrorException last = badGateway();
        stubChunk(http, target.path(), coords, badGateway(), badGateway(), last);
        List<PrefetchCall> calls = new ArrayList<>();

        assertThatThrownBy(() -> target.call(client, coords, calls::add))
                .isInstanceOf(RuntimeException.class)
                .hasMessage(target.exhaustedPrefix() + " 1/1 failed after 3 attempts")
                .hasCause(last);

        verifyRequests(http, target.path(), List.of(coords, coords, coords));
        assertThat(sleeps).containsExactly(RETRY_BASE_MS, 2 * RETRY_BASE_MS);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).attempts()).isEqualTo(3);
        assertThat(calls.get(0).coordsRequested()).isEqualTo(CHUNK);
        assertThat(calls.get(0).coordsReturned()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    @DisplayName("a single chunk that fails once then succeeds returns its results")
    void singleChunk_retrySucceeds_returnsResults(Target target) {
        RestClient http = deepMock();
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = target.client(http, sleeps);
        List<double[]> coords = coords(CHUNK);
        stubChunk(http, target.path(), coords, connectionReset(), target.body(coords));

        List<?> results = target.callPlain(client, coords);

        assertThat(results).hasSize(CHUNK);
        assertThat(target.marker(results.get(CHUNK - 1))).isEqualTo(coords.get(CHUNK - 1)[0]);
        assertThat(sleeps).containsExactly(RETRY_BASE_MS);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    @DisplayName("a single chunk hitting a 429 or a 400 throws that exception at once, with no retry")
    void singleChunk_nonRetryable_throwsImmediately(Target target) {
        RestClient http = deepMock();
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = target.client(http, sleeps);
        List<double[]> coords = coords(3);
        HttpClientErrorException rateLimited = minutelyLimit();
        stubChunk(http, target.path(), coords, rateLimited);

        assertThatThrownBy(() -> target.callPlain(client, coords)).isSameAs(rateLimited);
        verifyRequests(http, target.path(), List.of(coords));
        assertThat(sleeps).isEmpty();

        RestClient http2 = deepMock();
        OpenMeteoClient client2 = target.client(http2, sleeps);
        HttpClientErrorException badRequest = badRequest();
        stubChunk(http2, target.path(), coords, badRequest);

        assertThatThrownBy(() -> target.callPlain(client2, coords)).isSameAs(badRequest);
        verifyRequests(http2, target.path(), List.of(coords));
        assertThat(sleeps).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    @DisplayName("a sink that throws never changes what the batch returns")
    void throwingSink_isIgnored(Target target) {
        RestClient http = deepMock();
        OpenMeteoClient client = target.client(http, new ArrayList<>());
        List<double[]> coords = coords(25);
        for (List<double[]> chunk : chunks(coords)) {
            stubChunk(http, target.path(), chunk, target.body(chunk));
        }

        List<?> results = target.call(client, coords, call -> {
            throw new IllegalStateException("sink is broken");
        });

        assertThat(results).hasSize(25).doesNotContainNull();
    }

    // ── Cool-off interaction with the retry loop ─────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    @DisplayName("a cool-off that starts while a chunk is being retried stops that retry and the whole loop")
    void coolOffStartedDuringRetry_stopsRetryingAndStopsTheLoop(Target target) {
        RestClient http = deepMock();
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = target.client(http, sleeps);
        List<double[]> coords = coords(45);
        List<List<double[]>> chunks = chunks(coords);
        // A second batch (another thread, in production) is refused with "too many concurrent requests"
        // while chunk 1 is mid-attempt, and chunk 1 itself then fails with a transient 502.
        List<double[]> other = List.of(new double[]{60.0, -2.0});
        stubChunk(http, target.path(), other, tooManyRequests(CONCURRENT_BODY));
        stubChunk(http, target.path(), chunks.get(0), (org.mockito.stubbing.Answer<String>) invocation -> {
            assertThatThrownBy(() -> target.callPlain(client, other)).isInstanceOf(HttpClientErrorException.class);
            throw badGateway();
        });

        List<?> results = target.callPlain(client, coords);

        assertThat(results).hasSize(45).containsOnlyNulls();
        verifyRequests(http, target.path(), List.of(chunks.get(0), other));
        assertThat(sleeps).as("no retry backoff and no inter-chunk pause").isEmpty();
    }

    // ── fetchForecastBatch (no retry, chunk isolation only) ──────────────────────────────────

    static Stream<Arguments> forecastBatchSizes() {
        return Stream.of(
                Arguments.of(1, List.of(1)), Arguments.of(20, List.of(20)), Arguments.of(21, List.of(20, 1)),
                Arguments.of(40, List.of(20, 20)), Arguments.of(41, List.of(20, 20, 1)));
    }

    @ParameterizedTest(name = "{0} coords -> chunks {1}")
    @MethodSource("forecastBatchSizes")
    @DisplayName("fetchForecastBatch splits on 20-coordinate boundaries and merges slot for slot")
    void forecastBatch_chunksAndMerges(int size, List<Integer> expectedChunkSizes) {
        RestClient http = deepMock();
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = BRIEFING.client(http, sleeps);
        List<double[]> coords = coords(size);
        List<List<double[]>> chunks = chunks(coords);
        for (List<double[]> chunk : chunks) {
            stubChunk(http, FORECAST_PATH, chunk, BRIEFING.body(chunk));
        }

        List<OpenMeteoForecastResponse> results = client.fetchForecastBatch(coords);

        assertThat(chunks.stream().map(List::size).toList()).isEqualTo(expectedChunkSizes);
        verifyRequests(http, FORECAST_PATH, chunks);
        assertThat(results).hasSize(size);
        for (int slot = 0; slot < size; slot++) {
            assertThat(results.get(slot).getLatitude()).as("slot %d", slot).isEqualTo(coords.get(slot)[0]);
        }
        assertThat(sleeps).containsExactlyElementsOf(Collections.nCopies(expectedChunkSizes.size() - 1,
                INTER_CHUNK_MS));
    }

    @Test
    @DisplayName("fetchForecastBatch with an empty input returns an empty list")
    void forecastBatch_emptyInput() {
        RestClient http = deepMock();
        OpenMeteoClient client = BRIEFING.client(http, new ArrayList<>());
        stubChunk(http, FORECAST_PATH, List.of(), "[]");

        assertThat(client.fetchForecastBatch(List.of())).isEmpty();
    }

    @Test
    @DisplayName("fetchForecastBatch: a failed middle chunk is not retried and leaves nulls; the rest are kept")
    void forecastBatch_failedChunkNotRetried_othersKept() {
        RestClient http = deepMock();
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = BRIEFING.client(http, sleeps);
        List<double[]> coords = coords(45);
        List<List<double[]>> chunks = chunks(coords);
        stubChunk(http, FORECAST_PATH, chunks.get(0), BRIEFING.body(chunks.get(0)));
        stubChunk(http, FORECAST_PATH, chunks.get(1), badGateway());
        stubChunk(http, FORECAST_PATH, chunks.get(2), BRIEFING.body(chunks.get(2)));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBatch(coords);

        verifyRequests(http, FORECAST_PATH, chunks);
        assertThat(results).hasSize(45);
        assertThat(results.subList(20, 40)).containsOnlyNulls();
        assertThat(results.get(19).getLatitude()).isEqualTo(coords.get(19)[0]);
        assertThat(results.get(40).getLatitude()).isEqualTo(coords.get(40)[0]);
        assertThat(results.get(44).getLatitude()).isEqualTo(coords.get(44)[0]);
        assertThat(sleeps).containsExactly(INTER_CHUNK_MS, INTER_CHUNK_MS);
    }

    @Test
    @DisplayName("fetchForecastBatch: a minutely 429 makes only the next pause the long backoff")
    void forecastBatch_rateLimit_onlyNextPauseIsLong() {
        RestClient http = deepMock();
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = BRIEFING.client(http, sleeps);
        List<double[]> coords = coords(61);
        List<List<double[]>> chunks = chunks(coords);
        stubChunk(http, FORECAST_PATH, chunks.get(0), BRIEFING.body(chunks.get(0)));
        stubChunk(http, FORECAST_PATH, chunks.get(1), minutelyLimit());
        stubChunk(http, FORECAST_PATH, chunks.get(2), BRIEFING.body(chunks.get(2)));
        stubChunk(http, FORECAST_PATH, chunks.get(3), BRIEFING.body(chunks.get(3)));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBatch(coords);

        assertThat(sleeps).containsExactly(INTER_CHUNK_MS, RATE_LIMIT_MS, INTER_CHUNK_MS);
        assertThat(results.subList(20, 40)).containsOnlyNulls();
        assertThat(results.subList(0, 20)).doesNotContainNull();
        assertThat(results.subList(40, 61)).doesNotContainNull();
    }

    @Test
    @DisplayName("fetchForecastBatch: a batch that fits one chunk is not isolated, its failure propagates")
    void forecastBatch_singleChunkFailure_propagates() {
        RestClient http = deepMock();
        OpenMeteoClient client = BRIEFING.client(http, new ArrayList<>());
        List<double[]> coords = coords(CHUNK);
        HttpServerErrorException failure = badGateway();
        stubChunk(http, FORECAST_PATH, coords, failure);

        assertThatThrownBy(() -> client.fetchForecastBatch(coords)).isSameAs(failure);
        verifyRequests(http, FORECAST_PATH, List.of(coords));
    }

    // ── Cause-chain depth limits ─────────────────────────────────────────────────────────────

    /** {@code leaf} wrapped in plain RuntimeExceptions so that it sits {@code depth} levels down. */
    private static RuntimeException wrapped(Throwable leaf, int depth) {
        RuntimeException top = new RuntimeException("wrapper", leaf);
        for (int i = 1; i < depth; i++) {
            top = new RuntimeException("wrapper", top);
        }
        return top;
    }

    @ParameterizedTest(name = "response exception {0} causes down -> status {1}")
    @CsvSource({"1,400", "9,400", "10,"})
    @DisplayName("the HTTP status is read from a cause up to nine levels down, and not from ten down")
    void statusCode_foundInCauseChainOnlyWithinTheDepthLimit(int depth, Integer expectedStatus) {
        RestClient http = deepMock();
        OpenMeteoClient client = BRIEFING.client(http, new ArrayList<>());
        List<double[]> coords = coords(2);
        stubChunk(http, FORECAST_PATH, coords, wrapped(badRequest(), depth));
        List<PrefetchCall> calls = new ArrayList<>();

        assertThatThrownBy(() -> client.fetchForecastBriefingBatch(coords, calls::add))
                .isInstanceOf(RuntimeException.class);

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).statusCode()).isEqualTo(expectedStatus);
    }

    @ParameterizedTest(name = "socket timeout {0} causes down -> first backoff {1} ms")
    @CsvSource({"1,1000", "9,1000", "10,7"})
    @DisplayName("a socket timeout is recognised up to nine causes down, and not ten down")
    void timeout_recognisedInCauseChainOnlyWithinTheDepthLimit(int depth, long expectedFirstBackoff) {
        RestClient http = deepMock();
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = BRIEFING.client(http, sleeps);
        List<double[]> coords = coords(2);
        IOException cause = new SocketTimeoutException("deep");
        for (int i = 1; i < depth; i++) {
            cause = new IOException("wrapper", cause);
        }
        ResourceAccessException failure = new ResourceAccessException("I/O error", cause);
        stubChunk(http, FORECAST_PATH, coords, failure, failure, failure);

        assertThatThrownBy(() -> client.fetchForecastBriefingBatch(coords)).isInstanceOf(RuntimeException.class);

        assertThat(sleeps).containsExactly(expectedFirstBackoff, 2 * expectedFirstBackoff);
    }

    // ── fetchAirQualityBatch (plain: no retry, no isolation) ─────────────────────────────────

    @ParameterizedTest(name = "{0} coords -> chunks {1}")
    @MethodSource("forecastBatchSizes")
    @DisplayName("fetchAirQualityBatch splits on 20-coordinate boundaries, pauses between chunks, keeps order")
    void airQualityBatch_chunksAndMerges(int size, List<Integer> expectedChunkSizes) {
        RestClient http = deepMock();
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = AIR.client(http, sleeps);
        List<double[]> coords = coords(size);
        List<List<double[]>> chunks = chunks(coords);
        for (List<double[]> chunk : chunks) {
            stubChunk(http, AIR_PATH, chunk, AIR.body(chunk));
        }

        List<OpenMeteoAirQualityResponse> results = client.fetchAirQualityBatch(coords);

        assertThat(chunks.stream().map(List::size).toList()).isEqualTo(expectedChunkSizes);
        verifyRequests(http, AIR_PATH, chunks);
        assertThat(results).hasSize(size);
        for (int slot = 0; slot < size; slot++) {
            assertThat(AIR.marker(results.get(slot))).as("slot %d", slot).isEqualTo(coords.get(slot)[0]);
        }
        assertThat(sleeps).containsExactlyElementsOf(Collections.nCopies(expectedChunkSizes.size() - 1,
                INTER_CHUNK_MS));
    }

    @Test
    @DisplayName("fetchAirQualityBatch: a failing chunk propagates rather than leaving nulls")
    void airQualityBatch_failingChunk_propagates() {
        RestClient http = deepMock();
        OpenMeteoClient client = AIR.client(http, new ArrayList<>());
        List<double[]> coords = coords(25);
        List<List<double[]>> chunks = chunks(coords);
        stubChunk(http, AIR_PATH, chunks.get(0), AIR.body(chunks.get(0)));
        HttpServerErrorException failure = badGateway();
        stubChunk(http, AIR_PATH, chunks.get(1), failure);

        assertThatThrownBy(() -> client.fetchAirQualityBatch(coords)).isSameAs(failure);
    }

    // ── fetchForecastBatch and the cool-off ──────────────────────────────────────────────────

    @Test
    @DisplayName("fetchForecastBatch: a concurrent-requests refusal stops the loop before any further pause")
    void forecastBatch_coolOff_stopsLoopWithoutFurtherPauses() {
        RestClient http = deepMock();
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = BRIEFING.client(http, sleeps);
        List<double[]> coords = coords(61);
        List<List<double[]>> chunks = chunks(coords);
        stubChunk(http, FORECAST_PATH, chunks.get(0), BRIEFING.body(chunks.get(0)));
        stubChunk(http, FORECAST_PATH, chunks.get(1), tooManyRequests(CONCURRENT_BODY));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBatch(coords);

        verifyRequests(http, FORECAST_PATH, List.of(chunks.get(0), chunks.get(1)));
        assertThat(sleeps).containsExactly(INTER_CHUNK_MS);
        assertThat(results).hasSize(61);
        assertThat(results.subList(0, 20)).doesNotContainNull();
        assertThat(results.subList(20, 61)).containsOnlyNulls();
    }

    // ── Small collaborators ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the batch request factory sets a 10 s connect timeout and a 30 s read timeout")
    void batchRequestFactory_hasConnectAndReadTimeouts() {
        SimpleClientHttpRequestFactory factory = OpenMeteoClient.batchRequestFactory();

        assertThat(factory).isNotNull();
        assertThat(timeoutMillis(factory, "connectTimeout")).isEqualTo(10_000L);
        assertThat(timeoutMillis(factory, "readTimeout")).isEqualTo(30_000L);
    }

    private static long timeoutMillis(Object factory, String field) {
        Object value = ReflectionTestUtils.getField(factory, field);
        return value instanceof Duration d ? d.toMillis() : ((Number) value).longValue();
    }

    @Test
    @DisplayName("the default sleeper really waits for the requested time")
    void defaultSleeper_waits() {
        OpenMeteoClient client = new OpenMeteoClient(null, null, new ObjectMapper(), null, null);
        long start = System.nanoTime();

        client.sleeper.accept(60);

        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(55L);
    }

    @Test
    @DisplayName("the default sleeper keeps the thread's interrupt flag when it is interrupted mid-sleep")
    void defaultSleeper_restoresInterruptFlag() {
        OpenMeteoClient client = new OpenMeteoClient(null, null, new ObjectMapper(), null, null);
        Thread.currentThread().interrupt();
        try {
            client.sleeper.accept(10_000);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("a concurrent-requests refusal on fetchAirQuality starts a cool-off the next call hits")
    void singleAirQualityCall_refusalStartsCoolOff() {
        OpenMeteoAirQualityApi airApi = mock(OpenMeteoAirQualityApi.class);
        OpenMeteoClient client = new OpenMeteoClient(null, airApi, new ObjectMapper(), null, null);
        client.elapsedClockMs = () -> T0;
        when(airApi.getAirQuality(54.77, -1.57, OpenMeteoClient.AIR_QUALITY_PARAMS, "UTC"))
                .thenThrow(tooManyRequests(CONCURRENT_BODY));

        assertThatThrownBy(() -> client.fetchAirQuality(54.77, -1.57))
                .isInstanceOf(HttpClientErrorException.class);
        assertThatThrownBy(() -> client.fetchAirQuality(54.77, -1.57))
                .isInstanceOf(OpenMeteoCoolOffException.class);

        verify(airApi, times(1)).getAirQuality(54.77, -1.57, OpenMeteoClient.AIR_QUALITY_PARAMS, "UTC");
    }

    @Test
    @DisplayName("rate-limiter logging is harmless without a registry and logs a WARN on rejection with one")
    void rateLimiterLogging_warnsOnRejection() {
        OpenMeteoClient client = new OpenMeteoClient(null, null, new ObjectMapper(), null, null);
        client.registerRateLimiterEventLogging();

        RateLimiterRegistry registry = RateLimiterRegistry.of(RateLimiterConfig.custom()
                .limitForPeriod(1).limitRefreshPeriod(Duration.ofHours(1)).timeoutDuration(Duration.ZERO).build());
        ReflectionTestUtils.setField(client, "rateLimiterRegistry", registry);
        Logger logger = (Logger) LoggerFactory.getLogger(OpenMeteoClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            client.registerRateLimiterEventLogging();
            RateLimiter limiter = registry.rateLimiter("open-meteo");
            assertThat(limiter.acquirePermission()).isTrue();
            assertThat(limiter.acquirePermission()).isFalse();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("Open-Meteo rate limiter REJECTED a call");
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────

    private static RestClient deepMock() {
        return mock(RestClient.class, RETURNS_DEEP_STUBS);
    }

    private static List<double[]> coords(int count) {
        return IntStream.range(0, count).mapToObj(i -> new double[]{50.0 + i * 0.01, -1.5 - i * 0.01}).toList();
    }

    private static List<List<double[]>> chunks(List<double[]> coords) {
        List<List<double[]>> chunks = new ArrayList<>();
        for (int i = 0; i < coords.size(); i += CHUNK) {
            chunks.add(coords.subList(i, Math.min(i + CHUNK, coords.size())));
        }
        return chunks;
    }

    private static HttpServerErrorException badGateway() {
        return new HttpServerErrorException(HttpStatus.BAD_GATEWAY, "Bad Gateway");
    }

    private static ResourceAccessException connectionReset() {
        return new ResourceAccessException("I/O error: Connection reset",
                new java.io.IOException("Connection reset"));
    }

    private static HttpClientErrorException badRequest() {
        return HttpClientErrorException.create("400 Bad Request", HttpStatus.BAD_REQUEST, "Bad Request",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }

    private static HttpClientErrorException minutelyLimit() {
        return tooManyRequests("{\"error\":true,\"reason\":\"Minutely API request limit exceeded\"}");
    }

    private static HttpClientErrorException tooManyRequests(String body) {
        return HttpClientErrorException.create("429 Too Many Requests: \"" + body + "\"",
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static String latitudeList(List<double[]> chunk) {
        return chunk.stream().map(c -> String.valueOf(c[0])).collect(Collectors.joining(","));
    }

    private static URI buildRequestUri(Function<UriBuilder, URI> uriFunction) {
        return uriFunction.apply(new DefaultUriBuilderFactory("https://api.open-meteo.com").builder());
    }

    private static String longitudeList(List<double[]> chunk) {
        return chunk.stream().map(c -> String.valueOf(c[1])).collect(Collectors.joining(","));
    }

    private static String queryParam(URI uri, String name) {
        String value = UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String latitudeParam(URI uri) {
        return queryParam(uri, "latitude");
    }

    /** Matches the request for exactly this endpoint path and this chunk's latitude list. */
    private static Function<UriBuilder, URI> uriFor(String path, List<double[]> chunk) {
        String latitudes = latitudeList(chunk);
        return argThat(uriFunction -> {
            if (uriFunction == null) {
                return false;
            }
            URI uri = buildRequestUri(uriFunction);
            return path.equals(uri.getPath()) && latitudes.equals(latitudeParam(uri));
        });
    }

    /**
     * Stubs successive responses for requests to {@code path} carrying exactly {@code chunk}'s latitudes:
     * a Throwable is thrown, an Answer is run, anything else is a JSON body.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void stubChunk(RestClient client, String path, List<double[]> chunk, Object... outcomes) {
        var stubbing = when(client.get().uri(uriFor(path, chunk)).retrieve().body(String.class));
        for (Object outcome : outcomes) {
            if (outcome instanceof Throwable t) {
                stubbing = stubbing.thenThrow(t);
            } else if (outcome instanceof org.mockito.stubbing.Answer answer) {
                stubbing = stubbing.thenAnswer(answer);
            } else {
                stubbing = stubbing.thenReturn((String) outcome);
            }
        }
    }

    /** Verifies the client sent exactly {@code expected} requests, in order, to {@code path}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void verifyRequests(RestClient client, String path, List<List<double[]>> expected) {
        ArgumentCaptor<Function<UriBuilder, URI>> captor = ArgumentCaptor.forClass((Class) Function.class);
        verify(client.get(), atLeast(0)).uri(captor.capture());
        // Stubbing invokes uri(...) with a matcher placeholder (null); real requests are the rest.
        List<Function<UriBuilder, URI>> sent = captor.getAllValues().stream().filter(f -> f != null).toList();
        assertThat(sent).as("requests sent").hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            URI uri = buildRequestUri(sent.get(i));
            assertThat(uri.getPath()).as("request %d path", i + 1).isEqualTo(path);
            assertThat(latitudeParam(uri)).as("request %d latitudes", i + 1)
                    .isEqualTo(latitudeList(expected.get(i)));
            assertThat(queryParam(uri, "longitude")).as("request %d longitudes", i + 1)
                    .isEqualTo(longitudeList(expected.get(i)));
        }
    }
}
