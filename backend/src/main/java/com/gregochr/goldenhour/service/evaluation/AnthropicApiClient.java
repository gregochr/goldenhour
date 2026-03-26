package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
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
 */
@Service
public class AnthropicApiClient {

    private final AnthropicClient client;

    /**
     * Constructs an {@code AnthropicApiClient}.
     *
     * @param client the configured Anthropic SDK client
     */
    public AnthropicApiClient(AnthropicClient client) {
        this.client = client;
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
}
