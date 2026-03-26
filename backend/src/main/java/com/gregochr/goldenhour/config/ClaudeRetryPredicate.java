package com.gregochr.goldenhour.config;

import com.anthropic.errors.AnthropicServiceException;

import java.util.function.Predicate;

/**
 * Retry predicate for Anthropic API calls.
 *
 * <p>Retries on:
 * <ul>
 *   <li>500 (internal server error) — transient Anthropic-side failure</li>
 *   <li>529 (overloaded) — transient capacity issue</li>
 *   <li>400 with "content filtering" — intermittent output filter trigger</li>
 * </ul>
 */
public class ClaudeRetryPredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        if (throwable instanceof AnthropicServiceException ex) {
            boolean isServerError = ex.statusCode() == 500;
            boolean isOverloaded = ex.statusCode() == 529;
            boolean isContentFilter = ex.statusCode() == 400
                    && ex.getMessage() != null
                    && ex.getMessage().contains("content filtering");
            return isServerError || isOverloaded || isContentFilter;
        }
        return false;
    }
}
