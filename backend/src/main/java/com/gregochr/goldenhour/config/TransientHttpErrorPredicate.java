package com.gregochr.goldenhour.config;

import org.springframework.web.client.RestClientResponseException;

import java.util.function.Predicate;

/**
 * Retry predicate for Open-Meteo and WorldTides API calls.
 *
 * <p>Retries on 5xx server errors only. 429 Too Many Requests is NOT retried
 * because retrying immediately compounds the rate limit problem — the rate
 * limiter handles throttling instead.
 */
public class TransientHttpErrorPredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        if (throwable instanceof RestClientResponseException ex) {
            return ex.getStatusCode().is5xxServerError();
        }
        return false;
    }
}
