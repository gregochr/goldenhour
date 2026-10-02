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
            return isServerError || isOverloaded || isContentFilter(ex);
        }
        return false;
    }

    /**
     * Whether an Anthropic error is the intermittent output content-filter rejection (HTTP 400 whose
     * message names "content filtering"). Shared with the failure classifier so the retry rule and the
     * reason an admin reads can never disagree about what a content-filter failure is.
     *
     * @param ex the Anthropic service error
     * @return {@code true} for a content-filter 400
     */
    public static boolean isContentFilter(AnthropicServiceException ex) {
        return ex.statusCode() == 400
                && ex.getMessage() != null
                && ex.getMessage().contains("content filtering");
    }
}
