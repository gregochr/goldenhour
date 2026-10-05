package com.gregochr.goldenhour.config;

import com.anthropic.errors.AnthropicServiceException;

import java.util.function.Predicate;

/**
 * Retry predicate for the {@code "ask"} Resilience4j instance (Ask PhotoCast's model turns).
 *
 * <p>Retries only an {@link AnthropicServiceException} whose status is a server error (500 to 599,
 * which includes 529 "overloaded"). Deliberately NOT:
 * <ul>
 *   <li>the intermittent content-filter 400 that {@link ClaudeRetryPredicate} retries for the
 *       forecast pipeline: a reader's question is text a user chose, so a content-filter rejection
 *       is an answer, and re-sending it only pays again;</li>
 *   <li>429: the SDK's own retry is switched off for this instance's calls, and a rate-limit
 *       response is a reason to stop, not to try again within a 30-second conversation;</li>
 *   <li>an I/O failure or a timeout: the turn has already used its time budget, and a second
 *       attempt would have none left.</li>
 * </ul>
 */
public class AskRetryPredicate implements Predicate<Throwable> {

    private static final int SERVER_ERROR_FLOOR = 500;
    private static final int SERVER_ERROR_CEILING = 599;

    @Override
    public boolean test(Throwable throwable) {
        if (throwable instanceof AnthropicServiceException ex) {
            int status = ex.statusCode();
            return status >= SERVER_ERROR_FLOOR && status <= SERVER_ERROR_CEILING;
        }
        return false;
    }
}
