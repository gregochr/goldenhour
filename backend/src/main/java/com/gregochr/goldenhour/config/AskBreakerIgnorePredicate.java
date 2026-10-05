package com.gregochr.goldenhour.config;

import com.anthropic.errors.AnthropicServiceException;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import io.github.resilience4j.bulkhead.BulkheadFullException;

import java.util.function.Predicate;

/**
 * Tells the {@code ask} circuit breaker which failures are NOT evidence that Claude is down, so they
 * count as neither a failure nor a success.
 *
 * <ul>
 *   <li>A rejected API key (401 or 403), for the reason {@link ClaudeBreakerIgnorePredicate}
 *       records; the status test is the same shared method, so the two breakers cannot disagree
 *       about what a rejected key is.</li>
 *   <li>A full bulkhead. Four conversations already in flight is load on this application, not a
 *       fault at Anthropic, and counting it would let a burst of readers open the breaker and turn
 *       every question away for the whole open interval.</li>
 *   <li>An attempt the accounting gate refused: no request was made, so it says nothing about
 *       Anthropic.</li>
 * </ul>
 */
public class AskBreakerIgnorePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        if (throwable instanceof BulkheadFullException
                || throwable instanceof AnthropicApiClient.CallRefusedException) {
            return true;
        }
        return throwable instanceof AnthropicServiceException ex
                && ClaudeBreakerIgnorePredicate.isKeyRejection(ex.statusCode());
    }
}
