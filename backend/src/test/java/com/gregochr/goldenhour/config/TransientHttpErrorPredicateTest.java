package com.gregochr.goldenhour.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TransientHttpErrorPredicate}.
 */
class TransientHttpErrorPredicateTest {

    private final TransientHttpErrorPredicate predicate = new TransientHttpErrorPredicate();

    @Test
    @DisplayName("Returns true for 500 Internal Server Error")
    void shouldRetry_500_returnsTrue() {
        var ex = new RestClientResponseException("Server Error", 500, "Internal Server Error",
                null, null, null);

        assertThat(predicate.shouldRetry(null, ex)).isTrue();
    }

    @Test
    @DisplayName("Returns true for 503 Service Unavailable")
    void shouldRetry_503_returnsTrue() {
        var ex = new RestClientResponseException("Unavailable", 503, "Service Unavailable",
                null, null, null);

        assertThat(predicate.shouldRetry(null, ex)).isTrue();
    }

    @Test
    @DisplayName("Returns true for 429 Too Many Requests")
    void shouldRetry_429_returnsTrue() {
        var ex = new RestClientResponseException("Too Many Requests", 429, "Too Many Requests",
                null, null, null);

        assertThat(predicate.shouldRetry(null, ex)).isTrue();
    }

    @Test
    @DisplayName("Returns false for 404 Not Found")
    void shouldRetry_404_returnsFalse() {
        var ex = new RestClientResponseException("Not Found", 404, "Not Found",
                null, null, null);

        assertThat(predicate.shouldRetry(null, ex)).isFalse();
    }

    @Test
    @DisplayName("Returns false for 400 Bad Request")
    void shouldRetry_400_returnsFalse() {
        var ex = new RestClientResponseException("Bad Request", 400, "Bad Request",
                null, null, null);

        assertThat(predicate.shouldRetry(null, ex)).isFalse();
    }

    @Test
    @DisplayName("Returns false for non-RestClientResponseException")
    void shouldRetry_otherException_returnsFalse() {
        assertThat(predicate.shouldRetry(null, new RuntimeException("timeout"))).isFalse();
    }
}
