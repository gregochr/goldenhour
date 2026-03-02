package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.gregochr.goldenhour.config.ClaudeRetryPredicate;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * Resilient wrapper around the Anthropic Messages API.
 *
 * <p>Delegates to the {@link AnthropicClient} SDK with retry logic for transient
 * failures. The {@code @Retryable} annotation replaces the hand-rolled retry loop
 * previously in {@link AbstractEvaluationStrategy}.
 *
 * <p>Retries on:
 * <ul>
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
    @Retryable(includes = AnthropicServiceException.class,
               predicate = ClaudeRetryPredicate.class,
               maxRetries = 3, delay = 1000, multiplier = 2, maxDelay = 30000)
    public Message createMessage(MessageCreateParams params) {
        return client.messages().create(params);
    }
}
