package com.gregochr.goldenhour.config;

import org.springframework.web.client.RestClientResponseException;

import java.util.function.Predicate;

/**
 * Retry predicate for Open-Meteo and WorldTides API calls.
 *
 * <p>Retries on 5xx server errors and 429 Too Many Requests only.
 * Other 4xx client errors are not retried.
 */
public class TransientHttpErrorPredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        if (throwable instanceof RestClientResponseException ex) {
            return ex.getStatusCode().is5xxServerError()
                    || ex.getStatusCode().value() == 429;
        }
        return false;
    }
}
