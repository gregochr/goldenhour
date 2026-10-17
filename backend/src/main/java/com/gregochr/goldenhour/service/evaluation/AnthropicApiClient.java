package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Service;

/**
 * Resilient wrapper around the Anthropic Messages API.
 *
 * <p>Delegates to the {@link AnthropicClient} SDK with retry and circuit breaker
 * logic for transient failures. The circuit breaker fails fast when the Anthropic
 * API is persistently down, preventing cascading retries across many locations.
 *
 * <p>Retries on:
 * <ul>
 *   <li>500 (internal server error) — transient Anthropic-side failure</li>
 *   <li>529 (overloaded) — transient capacity issue</li>
 *   <li>400 with "content filtering" — intermittent output filter trigger</li>
 * </ul>
 *
 * <p>{@link #createAskMessage} is Ask PhotoCast's separate door: its own retry, circuit breaker and
 * bulkhead, so a reader's questions can never open the breaker the forecast pipeline depends on.
 */
@Service
public class AnthropicApiClient {

    private final AnthropicClient client;

    /**
     * The shared client with the SDK's own transport retries switched off ({@code maxRetries = 0}),
     * used only by {@link #createAskMessage}. The SDK retries 408, 409, 429, 5xx and I/O failures up
     * to twice by default, each retry with the request's full timeout, so left on it would multiply
     * under the {@code ask} retry and push one turn far past the conversation's deadline. With it off,
     * every HTTP attempt is one the {@code ask} retry instance can see and count. Null only when the
     * SDK client is a test mock that does not stub {@code withOptions}.
     */
    private final AnthropicClient askClient;

    /**
     * Constructs an {@code AnthropicApiClient}.
     *
     * @param client the configured Anthropic SDK client
     */
    public AnthropicApiClient(AnthropicClient client) {
        this.client = client;
        this.askClient = client.withOptions(options -> options.maxRetries(0));
    }

    /**
     * Creates a message using the Anthropic API with automatic retry on transient errors.
     *
     * @param params the message creation parameters (model, prompt, etc.)
     * @return Claude's response message
     */
    @Retry(name = "anthropic")
    @CircuitBreaker(name = "anthropic")
    public Message createMessage(MessageCreateParams params) {
        return client.messages().create(params);
    }

    /**
     * Creates one Ask PhotoCast model turn: one HTTP attempt per call, with {@code ask}-instance
     * retry (two attempts, 5xx only), circuit breaker and bulkhead (four concurrent, two seconds'
     * wait).
     *
     * <p>The caller owns the time budget: {@code options} carries the per-call timeout, and
     * {@code ClaudeAskEngine} also stops waiting at its own deadline, because a retry would
     * otherwise give the second attempt a fresh timeout of the same size.
     *
     * <p><b>The gate runs in the method body</b>, which Resilience4j enters once per ATTEMPT and only
     * after the bulkhead permit has been taken (the bulkhead is the innermost aspect; the breaker and
     * the retry sit outside it). So the check is made after any bulkhead wait and again before a
     * retry's second attempt, which is another paid call, and it is the last thing done before the HTTP
     * request is issued. A refusal is a {@link CallRefusedException}: not retried, and neither a
     * success nor a failure to the breaker. A call already issued cannot be recalled.
     *
     * @param params  the message parameters (model, tools, conversation so far)
     * @param options per-request options; the timeout for this call
     * @param gate    asked before every attempt; false refuses the attempt
     * @return Claude's response message
     * @throws CallRefusedException when the gate refused this attempt (no request was made)
     */
    @Retry(name = "ask")
    @CircuitBreaker(name = "ask")
    @Bulkhead(name = "ask")
    public Message createAskMessage(MessageCreateParams params, RequestOptions options, CallGate gate) {
        if (!gate.mayCall()) {
            throw new CallRefusedException();
        }
        return askClient.messages().create(params, options);
    }

    /** Decides, immediately before each attempt, whether a paid call may be made. */
    @FunctionalInterface
    public interface CallGate {

        /**
         * Whether the attempt about to be made may proceed.
         *
         * @return false to refuse it
         */
        boolean mayCall();
    }

    /** A {@link CallGate} refused an attempt: no request was made. */
    public static final class CallRefusedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** Creates the exception; it carries no stack trace, it is control flow. */
        public CallRefusedException() {
            super("the call gate refused this attempt", null, false, false);
        }
    }
}
