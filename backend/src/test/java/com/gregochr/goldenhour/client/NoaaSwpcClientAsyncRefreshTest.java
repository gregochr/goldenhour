package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.model.KpReading;
import com.gregochr.goldenhour.util.RestClientMocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;

/**
 * The status endpoint's cache-first NOAA reads on {@link NoaaSwpcClient}: single-flight
 * background refresh, cold-cache wait, fail-open retry, and the synchronous fetches left alone.
 */
@ExtendWith(MockitoExtension.class)
class NoaaSwpcClientAsyncRefreshTest {

    private static final String KP_JSON_A = """
            [["time_tag","Kp","Kp_fraction","Kp_int"],
             ["2025-08-01 00:00:00","3.33","3.333","3"]]
            """;
    private static final String KP_JSON_B = """
            [["time_tag","Kp","Kp_fraction","Kp_int"],
             ["2025-08-01 03:00:00","5.67","5.667","6"]]
            """;

    @Mock
    private RestClient restClient;

    private MutableClock clock;
    private NoaaSwpcClient client;
    private RestClient.ResponseSpec responseSpec;
    private final AtomicInteger httpCalls = new AtomicInteger();
    private final AtomicReference<Supplier<String>> behaviour = new AtomicReference<>(() -> KP_JSON_A);

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-07-17T12:00:00Z"));
        client = new NoaaSwpcClient(restClient, new AuroraProperties(), new ObjectMapper(), clock);
        // Plumb the fluent chain once, then re-stub its terminal body() per test.
        RestClientMocks.stubGet(restClient, String.class, KP_JSON_A);
        responseSpec = restClient.get().uri("x").retrieve();
        // Touch the placeholder stub once so strict stubbing counts it as used, then route every
        // call through one answer whose behaviour tests swap (re-stubbing a mock a background
        // thread is calling would race).
        responseSpec.body(String.class);
        doAnswer(inv -> {
            httpCalls.incrementAndGet();
            return behaviour.get().get();
        }).when(responseSpec).body(String.class);
    }

    private void answerWith(Supplier<String> body) {
        behaviour.set(body);
    }

    @Test
    @DisplayName("a cold cache waits for the one in-flight fetch and returns the fresh value")
    void coldCacheWaitsAndReturnsFresh() {
        answerWith(() -> KP_JSON_A);

        List<KpReading> readings = client.getOrRefreshAsyncKp();

        assertThat(readings).hasSize(1);
        assertThat(readings.get(0).kp()).isEqualTo(3.33);
        assertThat(httpCalls).hasValue(1);
    }

    @Test
    @DisplayName("a cold cache whose fetch fails returns empty, not an exception")
    void coldCacheFailureReturnsEmpty() {
        answerWith(() -> {
            throw new RestClientException("NOAA down");
        });

        assertThat(client.getOrRefreshAsyncKp()).isEmpty();
    }

    @Test
    @DisplayName("concurrent stale callers start exactly one fetch and are served the stale value")
    void concurrentStaleCallersStartOneFetch() throws Exception {
        answerWith(() -> KP_JSON_A);
        List<KpReading> stale = client.getOrRefreshAsyncKp();
        assertThat(httpCalls).hasValue(1);
        clock.advanceMinutes(16);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        answerWith(() -> {
            entered.countDown();
            await().atMost(5, TimeUnit.SECONDS).until(() -> release.getCount() == 0);
            return KP_JSON_B;
        });

        List<List<KpReading>> served = new java.util.concurrent.CopyOnWriteArrayList<>();
        served.add(client.getOrRefreshAsyncKp());
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 20; i++) {
            Thread.startVirtualThread(() -> served.add(client.getOrRefreshAsyncKp()));
        }
        await().atMost(5, TimeUnit.SECONDS).until(() -> served.size() == 21);

        // 20 callers arrived while the refresh was in flight: none started another fetch, and none
        // waited for it (the cache was not empty).
        assertThat(httpCalls).as("1 priming call + exactly 1 refresh").hasValue(2);
        assertThat(served).allSatisfy(s -> assertThat(s).isSameAs(stale));

        release.countDown();
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(client.getOrRefreshAsyncKp().get(0).kp())
                        .isEqualTo(5.67));
        assertThat(httpCalls).hasValue(2);
    }

    @Test
    @DisplayName("a failed refresh keeps the stale value and the next call retries")
    void failedRefreshKeepsStaleAndRetries() {
        answerWith(() -> KP_JSON_A);
        List<KpReading> stale = client.getOrRefreshAsyncKp();
        clock.advanceMinutes(16);
        answerWith(() -> {
            throw new RestClientException("NOAA down");
        });

        assertThat(client.getOrRefreshAsyncKp()).isSameAs(stale);
        // Wait for the failed flight to finish (its slot is freed), then expect a fresh attempt.
        int before = httpCalls.get();
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(client.getOrRefreshAsyncKp()).isSameAs(stale);
            assertThat(httpCalls.get()).isGreaterThan(before);
        });

        // Recovery: the same retry path installs the fresh value.
        answerWith(() -> KP_JSON_B);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(
                () -> assertThat(client.getOrRefreshAsyncKp().get(0).kp()).isEqualTo(5.67));
    }

    @Test
    @DisplayName("fetchKp stays synchronous: a stale cache is refreshed on the calling thread")
    void fetchKpStillSynchronous() {
        answerWith(() -> KP_JSON_A);
        client.fetchKp();
        clock.advanceMinutes(16);
        AtomicReference<Thread> fetchedOn = new AtomicReference<>();
        answerWith(() -> {
            fetchedOn.set(Thread.currentThread());
            return KP_JSON_B;
        });

        List<KpReading> fresh = client.fetchKp();

        assertThat(fetchedOn.get()).isSameAs(Thread.currentThread());
        assertThat(fresh.get(0).kp()).isEqualTo(5.67);
    }

    @Test
    @DisplayName("fetchAll still fetches on the calling thread")
    void fetchAllStillSynchronous() {
        AtomicReference<Thread> fetchedOn = new AtomicReference<>();
        answerWith(() -> {
            fetchedOn.compareAndSet(null, Thread.currentThread());
            return KP_JSON_A;
        });

        client.fetchAll();

        assertThat(fetchedOn.get()).isSameAs(Thread.currentThread());
    }

    /** A Clock whose instant the test advances by hand. */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advanceMinutes(long minutes) {
            now = now.plus(Duration.ofMinutes(minutes));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
