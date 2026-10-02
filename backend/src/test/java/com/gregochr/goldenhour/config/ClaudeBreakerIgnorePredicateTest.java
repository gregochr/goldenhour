package com.gregochr.goldenhour.config;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which Anthropic failures the circuit breaker must NOT count: a rejected key and nothing else.
 */
class ClaudeBreakerIgnorePredicateTest {

    private final ClaudeBreakerIgnorePredicate predicate = new ClaudeBreakerIgnorePredicate();

    private static AnthropicServiceException serviceError(int status) {
        AnthropicServiceException ex = mock(AnthropicServiceException.class);
        when(ex.statusCode()).thenReturn(status);
        return ex;
    }

    @ParameterizedTest(name = "HTTP {0} is ignored")
    @ValueSource(ints = {401, 403})
    @DisplayName("a rejected key (401, 403) is ignored: not an outage")
    void rejectedKey_isIgnored(int status) {
        assertThat(predicate.test(serviceError(status))).isTrue();
    }

    @ParameterizedTest(name = "HTTP {0} still counts as a failure")
    @ValueSource(ints = {400, 404, 413, 422, 429, 500, 502, 503, 529})
    @DisplayName("every other status keeps counting toward opening the breaker")
    void otherStatuses_stillCount(int status) {
        assertThat(predicate.test(serviceError(status))).isFalse();
    }

    @Test
    @DisplayName("a connection failure and a non-Anthropic exception still count")
    void nonServiceErrors_stillCount() {
        assertThat(predicate.test(new AnthropicIoException("timed out"))).isFalse();
        assertThat(predicate.test(new IllegalStateException("boom"))).isFalse();
    }

    @Test
    @DisplayName("isKeyRejection is the one definition of a rejected key: 401 and 403, nothing near them")
    void isKeyRejection_isExactlyTheTwoStatuses() {
        assertThat(ClaudeBreakerIgnorePredicate.isKeyRejection(401)).isTrue();
        assertThat(ClaudeBreakerIgnorePredicate.isKeyRejection(403)).isTrue();
        assertThat(ClaudeBreakerIgnorePredicate.isKeyRejection(400)).isFalse();
        assertThat(ClaudeBreakerIgnorePredicate.isKeyRejection(402)).isFalse();
        assertThat(ClaudeBreakerIgnorePredicate.isKeyRejection(404)).isFalse();
    }
}
