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
    void test_500_returnsTrue() {
        var ex = new RestClientResponseException("Server Error", 500, "Internal Server Error",
                null, null, null);

        assertThat(predicate.test(ex)).isTrue();
    }

    @Test
    @DisplayName("Returns true for 503 Service Unavailable")
    void test_503_returnsTrue() {
        var ex = new RestClientResponseException("Unavailable", 503, "Service Unavailable",
                null, null, null);

        assertThat(predicate.test(ex)).isTrue();
    }

    @Test
    @DisplayName("Returns false for 429 Too Many Requests (retrying compounds the rate limit)")
    void test_429_returnsFalse() {
        var ex = new RestClientResponseException("Too Many Requests", 429, "Too Many Requests",
                null, null, null);

        assertThat(predicate.test(ex)).isFalse();
    }

    @Test
    @DisplayName("Returns false for 404 Not Found")
    void test_404_returnsFalse() {
        var ex = new RestClientResponseException("Not Found", 404, "Not Found",
                null, null, null);

        assertThat(predicate.test(ex)).isFalse();
    }

    @Test
    @DisplayName("Returns false for 400 Bad Request")
    void test_400_returnsFalse() {
        var ex = new RestClientResponseException("Bad Request", 400, "Bad Request",
                null, null, null);

        assertThat(predicate.test(ex)).isFalse();
    }

    @Test
    @DisplayName("Returns false for non-RestClientResponseException")
    void test_otherException_returnsFalse() {
        assertThat(predicate.test(new RuntimeException("timeout"))).isFalse();
    }
}
