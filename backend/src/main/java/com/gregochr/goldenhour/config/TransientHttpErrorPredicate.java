package com.gregochr.goldenhour.config;

import org.springframework.resilience.retry.MethodRetryPredicate;
import org.springframework.web.client.RestClientResponseException;

import java.lang.reflect.Method;

/**
 * Retry predicate for Open-Meteo and WorldTides API calls.
 *
 * <p>Retries on 5xx server errors and 429 Too Many Requests only.
 * Other 4xx client errors are not retried.
 */
public class TransientHttpErrorPredicate implements MethodRetryPredicate {

    @Override
    public boolean shouldRetry(Method method, Throwable throwable) {
        if (throwable instanceof RestClientResponseException ex) {
            return ex.getStatusCode().is5xxServerError()
                    || ex.getStatusCode().value() == 429;
        }
        return false;
    }
}
