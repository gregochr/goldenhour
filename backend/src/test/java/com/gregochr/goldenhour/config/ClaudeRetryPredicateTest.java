package com.gregochr.goldenhour.config;

import com.anthropic.errors.AnthropicServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClaudeRetryPredicate}.
 */
class ClaudeRetryPredicateTest {

    private final ClaudeRetryPredicate predicate = new ClaudeRetryPredicate();

    @Test
    @DisplayName("Returns true for 500 internal server error")
    void test_500_returnsTrue() {
        AnthropicServiceException ex = buildException(500, "Internal server error");

        assertThat(predicate.test(ex)).isTrue();
    }

    @Test
    @DisplayName("Returns true for 529 overloaded error")
    void test_529_returnsTrue() {
        AnthropicServiceException ex = buildException(529, "overloaded");

        assertThat(predicate.test(ex)).isTrue();
    }

    @Test
    @DisplayName("Returns true for 400 content filtering error")
    void test_400_contentFiltering_returnsTrue() {
        AnthropicServiceException ex = buildException(400,
                "Output blocked by content filtering policy");

        assertThat(predicate.test(ex)).isTrue();
    }

    @Test
    @DisplayName("Returns false for 400 non-content-filter error")
    void test_400_otherError_returnsFalse() {
        AnthropicServiceException ex = buildException(400,
                "invalid_request_error: max_tokens must be positive");

        assertThat(predicate.test(ex)).isFalse();
    }

    @Test
    @DisplayName("Returns false for 429 rate limit error")
    void test_429_returnsFalse() {
        AnthropicServiceException ex = buildException(429, "rate_limit_exceeded");

        assertThat(predicate.test(ex)).isFalse();
    }

    @Test
    @DisplayName("Returns false for non-AnthropicServiceException")
    void test_otherException_returnsFalse() {
        assertThat(predicate.test(new RuntimeException("timeout"))).isFalse();
    }

    @Test
    @DisplayName("Returns false for 400 with null message")
    void test_400_nullMessage_returnsFalse() {
        AnthropicServiceException ex = buildException(400, null);

        assertThat(predicate.test(ex)).isFalse();
    }

    private AnthropicServiceException buildException(int statusCode, String message) {
        AnthropicServiceException ex = mock(AnthropicServiceException.class);
        when(ex.statusCode()).thenReturn(statusCode);
        when(ex.getMessage()).thenReturn(message);
        return ex;
    }
}
