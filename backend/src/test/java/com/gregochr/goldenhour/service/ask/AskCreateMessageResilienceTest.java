package com.gregochr.goldenhour.service.ask;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.services.blocking.MessageService;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The real Resilience4j aspects around {@link AnthropicApiClient#createAskMessage}, with the SDK mocked
 * underneath, in the application context built from the test profile: how many attempts a failure
 * gets, which failures are retried, that Ask's breaker is its own, and that its bulkhead sheds a fifth
 * conversation without counting it as an outage.
 */
@SpringBootTest
class AskCreateMessageResilienceTest {

    /** The SDK under {@code AnthropicApiClient}: the shared client, and the retry-free one derived from it. */
    @TestConfiguration
    static class Config {

        static final AnthropicClient SHARED_CLIENT = mock(AnthropicClient.class);
        static final MessageService SHARED_MESSAGES = mock(MessageService.class);
        static final AnthropicClient ASK_CLIENT = mock(AnthropicClient.class);
        static final MessageService ASK_MESSAGES = mock(MessageService.class);

        @Bean
        @Primary
        AnthropicClient mockedAnthropicClient() {
            when(SHARED_CLIENT.withOptions(any())).thenReturn(ASK_CLIENT);
            when(SHARED_CLIENT.messages()).thenReturn(SHARED_MESSAGES);
            when(ASK_CLIENT.messages()).thenReturn(ASK_MESSAGES);
            return SHARED_CLIENT;
        }
    }

    @Autowired
    private AnthropicApiClient api;

    @Autowired
    private CircuitBreakerRegistry breakers;

    @Autowired
    private RetryRegistry retries;

    @Autowired
    private BulkheadRegistry bulkheads;

    private static final Message REPLY = AskMessages.text("ok");
    private static final AnthropicApiClient.CallGate OPEN = () -> true;

    private static MessageCreateParams params() {
        return MessageCreateParams.builder().model("claude-haiku-4-5-20251001").maxTokens(50)
                .addUserMessage("hello").build();
    }

    private static AnthropicServiceException status(int code, String message) {
        AnthropicServiceException ex = mock(AnthropicServiceException.class);
        when(ex.statusCode()).thenReturn(code);
        when(ex.getMessage()).thenReturn(message);
        return ex;
    }

    private static void stubAsk(Throwable failure) {
        when(Config.ASK_MESSAGES.create(any(MessageCreateParams.class), any(RequestOptions.class)))
                .thenThrow(failure);
    }

    private static void verifyAskCalls(int times) {
        verify(Config.ASK_MESSAGES, times(times)).create(any(MessageCreateParams.class),
                any(RequestOptions.class));
    }

    @BeforeEach
    void resetState() {
        reset(Config.ASK_MESSAGES, Config.SHARED_MESSAGES);
        breakers.circuitBreaker("ask").reset();
        breakers.circuitBreaker("anthropic").reset();
    }

    @Test
    @DisplayName("the application's own registries carry the limits the committed YAML declares")
    void registriesCarryTheLimits() {
        assertThat(retries.retry("ask").getRetryConfig().getMaxAttempts()).isEqualTo(2);
        assertThat(bulkheads.bulkhead("ask").getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(4);
        assertThat(bulkheads.bulkhead("ask").getBulkheadConfig().getMaxWaitDuration())
                .isEqualTo(Duration.ofSeconds(2));
        var breaker = breakers.circuitBreaker("ask").getCircuitBreakerConfig();
        assertThat(breaker.getSlidingWindowSize()).isEqualTo(10);
        assertThat(breaker.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(breaker.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(breaker.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);
        assertThat(retries.retry("anthropic").getRetryConfig().getMaxAttempts())
                .as("the shared instance is untouched").isEqualTo(4);
    }

    @Test
    @DisplayName("a server error gets exactly two attempts, then fails")
    void serverErrorIsAttemptedTwice() {
        stubAsk(status(500, "boom"));

        assertThatThrownBy(() -> api.createAskMessage(params(), RequestOptions.none(), OPEN))
                .isInstanceOf(AnthropicServiceException.class);

        verifyAskCalls(2);
    }

    @Test
    @DisplayName("an overloaded 529 is retried once and a second attempt that works is returned")
    void secondAttemptCanSucceed() {
        AnthropicServiceException overloaded = status(529, "overloaded");
        when(Config.ASK_MESSAGES.create(any(MessageCreateParams.class), any(RequestOptions.class)))
                .thenThrow(overloaded).thenReturn(REPLY);

        assertThat(api.createAskMessage(params(), RequestOptions.none(), OPEN)).isSameAs(REPLY);

        verifyAskCalls(2);
    }

    @Test
    @DisplayName("the gate is asked UNDER the bulkhead permit, once per call: with one call in flight exactly "
            + "one of the four permits is taken when it runs")
    void gateRunsAfterTheBulkheadPermit() {
        List<Integer> available = new ArrayList<>();
        AnthropicApiClient.CallGate gate = () -> {
            available.add(bulkheads.bulkhead("ask").getMetrics().getAvailableConcurrentCalls());
            return true;
        };
        when(Config.ASK_MESSAGES.create(any(MessageCreateParams.class), any(RequestOptions.class)))
                .thenReturn(REPLY);

        api.createAskMessage(params(), RequestOptions.none(), gate);

        assertThat(available).containsExactly(3);
    }

    @Test
    @DisplayName("a refused first attempt makes no request at all, is not retried, and is neither a success nor "
            + "a failure to the breaker")
    void refusedBeforeTheFirstAttempt() {
        assertThatThrownBy(() -> api.createAskMessage(params(), RequestOptions.none(), () -> false))
                .isInstanceOf(AnthropicApiClient.CallRefusedException.class);

        verifyNoInteractions(Config.ASK_MESSAGES);
        assertThat(breakers.circuitBreaker("ask").getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    @DisplayName("a retry's second attempt is another paid call and is gated too: the first attempt fails with "
            + "a 500, another thread latches before the second, and the second is never made")
    void secondAttemptIsGated() throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        AnthropicServiceException serverError = status(500, "boom");
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            when(Config.ASK_MESSAGES.create(any(MessageCreateParams.class), any(RequestOptions.class)))
                    .thenAnswer(inv -> {
                        // Another conversation's write fails while this attempt is failing.
                        other.submit(() -> open.set(false)).get(10, TimeUnit.SECONDS);
                        throw serverError;
                    });

            assertThatThrownBy(() -> api.createAskMessage(params(), RequestOptions.none(), open::get))
                    .isInstanceOf(AnthropicApiClient.CallRefusedException.class);
        } finally {
            other.shutdownNow();
        }

        verifyAskCalls(1);
        assertThat(breakers.circuitBreaker("ask").getMetrics().getNumberOfFailedCalls())
                .as("the refusal is ignored; the 500 was absorbed by the retry's own attempt")
                .isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("an open gate changes nothing: a 500 is still attempted exactly twice, and the gate is asked "
            + "once per attempt")
    void openGateIsAskedPerAttempt() {
        AtomicInteger asked = new AtomicInteger();
        stubAsk(status(500, "boom"));

        assertThatThrownBy(() -> api.createAskMessage(params(), RequestOptions.none(), () -> {
            asked.incrementAndGet();
            return true;
        })).isInstanceOf(AnthropicServiceException.class);

        verifyAskCalls(2);
        assertThat(asked.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a call that works is made once")
    void successIsOneAttempt() {
        when(Config.ASK_MESSAGES.create(any(MessageCreateParams.class), any(RequestOptions.class)))
                .thenReturn(REPLY);

        assertThat(api.createAskMessage(params(), RequestOptions.none(), OPEN)).isSameAs(REPLY);

        verifyAskCalls(1);
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {400, 401, 403, 404, 429})
    @DisplayName("client errors and rate limits are not retried: one attempt")
    void clientErrorsAreNotRetried(int code) {
        stubAsk(status(code, "no"));

        assertThatThrownBy(() -> api.createAskMessage(params(), RequestOptions.none(), OPEN))
                .isInstanceOf(AnthropicServiceException.class);

        verifyAskCalls(1);
    }

    @Test
    @DisplayName("the content-filter 400 the forecast pipeline retries is NOT retried here: one attempt")
    void contentFilterIsNotRetried() {
        stubAsk(status(400, "Output blocked by content filtering policy"));

        assertThatThrownBy(() -> api.createAskMessage(params(), RequestOptions.none(), OPEN))
                .isInstanceOf(AnthropicServiceException.class);

        verifyAskCalls(1);
    }

    @Test
    @DisplayName("a timeout or other I/O failure is not retried: the turn has no time left for a second attempt")
    void ioFailureIsNotRetried() {
        stubAsk(new AnthropicIoException("timed out"));

        assertThatThrownBy(() -> api.createAskMessage(params(), RequestOptions.none(), OPEN))
                .isInstanceOf(AnthropicIoException.class);

        verifyAskCalls(1);
    }

    @Test
    @DisplayName("Ask's breaker is its own: five failed questions open it, the shared 'anthropic' breaker stays "
            + "closed, and the forecast pipeline's door still works")
    void askBreakerDoesNotOpenTheSharedOne() {
        stubAsk(status(500, "boom"));
        when(Config.SHARED_MESSAGES.create(any(MessageCreateParams.class))).thenReturn(REPLY);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> api.createAskMessage(params(), RequestOptions.none(), OPEN))
                    .isInstanceOf(AnthropicServiceException.class);
        }

        assertThat(breakers.circuitBreaker("ask").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breakers.circuitBreaker("anthropic").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breakers.circuitBreaker("anthropic").getMetrics().getNumberOfBufferedCalls()).isZero();
        verifyAskCalls(10);

        assertThatThrownBy(() -> api.createAskMessage(params(), RequestOptions.none(), OPEN))
                .isInstanceOf(CallNotPermittedException.class);
        verifyAskCalls(10);
        assertThat(api.createMessage(params())).isSameAs(REPLY);
    }

    @Test
    @DisplayName("a rejected API key never opens Ask's breaker, however many questions meet it")
    void rejectedKeyNeverOpensTheAskBreaker() {
        stubAsk(status(401, "bad key"));

        for (int i = 0; i < 30; i++) {
            assertThatThrownBy(() -> api.createAskMessage(params(), RequestOptions.none(), OPEN))
                    .isInstanceOf(AnthropicServiceException.class);
        }

        assertThat(breakers.circuitBreaker("ask").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breakers.circuitBreaker("ask").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("a fifth concurrent conversation waits two seconds and is then shed by the bulkhead, and that is "
            + "not counted against the breaker")
    void bulkheadShedsTheFifthWithoutCountingItAsAnOutage() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch fourInside = new CountDownLatch(4);
        when(Config.ASK_MESSAGES.create(any(MessageCreateParams.class), any(RequestOptions.class)))
                .thenAnswer(inv -> {
                    fourInside.countDown();
                    release.await(30, TimeUnit.SECONDS);
                    return REPLY;
                });
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<Message>> admitted = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                admitted.add(pool.submit(() -> api.createAskMessage(params(), RequestOptions.none(), OPEN)));
            }
            assertThat(fourInside.await(10, TimeUnit.SECONDS)).as("four conversations inside").isTrue();

            long started = System.nanoTime();
            Future<Message> fifth = pool.submit(() -> api.createAskMessage(params(), RequestOptions.none(), OPEN));
            Throwable shed = catchCause(fifth);
            long waitedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertThat(shed).isInstanceOf(BulkheadFullException.class);
            assertThat(waitedMs).as("waited for a slot").isBetween(1_800L, 6_000L);
            assertThat(breakers.circuitBreaker("ask").getMetrics().getNumberOfFailedCalls()).isZero();
            assertThat(breakers.circuitBreaker("ask").getState()).isEqualTo(CircuitBreaker.State.CLOSED);

            release.countDown();
            for (Future<Message> call : admitted) {
                assertThat(call.get(10, TimeUnit.SECONDS)).isSameAs(REPLY);
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        verifyNoInteractions(Config.SHARED_MESSAGES);
    }

    private static Throwable catchCause(Future<?> future) throws InterruptedException, TimeoutException {
        try {
            future.get(15, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }
}
