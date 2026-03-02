package com.gregochr.goldenhour.config;

import com.anthropic.errors.AnthropicServiceException;
import org.springframework.resilience.retry.MethodRetryPredicate;

import java.lang.reflect.Method;

/**
 * Retry predicate for Anthropic API calls.
 *
 * <p>Retries on:
 * <ul>
 *   <li>529 (overloaded) — transient capacity issue</li>
 *   <li>400 with "content filtering" — intermittent output filter trigger</li>
 * </ul>
 */
public class ClaudeRetryPredicate implements MethodRetryPredicate {

    @Override
    public boolean shouldRetry(Method method, Throwable throwable) {
        if (throwable instanceof AnthropicServiceException ex) {
            boolean isOverloaded = ex.statusCode() == 529;
            boolean isContentFilter = ex.statusCode() == 400
                    && ex.getMessage() != null
                    && ex.getMessage().contains("content filtering");
            return isOverloaded || isContentFilter;
        }
        return false;
    }
}
