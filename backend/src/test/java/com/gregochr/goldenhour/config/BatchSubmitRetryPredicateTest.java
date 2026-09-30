package com.gregochr.goldenhour.config;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BatchSubmitRetryPredicate} — the predicate behind
 * {@link com.gregochr.goldenhour.service.batch.AnthropicBatchClient}'s retry.
 */
class BatchSubmitRetryPredicateTest {

    private final BatchSubmitRetryPredicate predicate = new BatchSubmitRetryPredicate();

    @Test
    @DisplayName("Returns true for 500 internal server error")
    void test_500_returnsTrue() {
        assertThat(predicate.test(serviceException(500))).isTrue();
    }

    @Test
    @DisplayName("Returns true for 502/503/504 gateway errors")
    void test_502_503_504_returnTrue() {
        assertThat(predicate.test(serviceException(502))).isTrue();
        assertThat(predicate.test(serviceException(503))).isTrue();
        assertThat(predicate.test(serviceException(504))).isTrue();
    }

    @Test
    @DisplayName("Returns true for 529 overloaded (no dedicated exception type, still >= 500)")
    void test_529_returnsTrue() {
        assertThat(predicate.test(serviceException(529))).isTrue();
    }

    @Test
    @DisplayName("Returns false for 499 — just below the server-error threshold")
    void test_499_returnsFalse() {
        assertThat(predicate.test(serviceException(499))).isFalse();
    }

    @Test
    @DisplayName("Returns false for 429 rate limit — the SDK's own Retry-After handling owns this")
    void test_429_returnsFalse() {
        assertThat(predicate.test(serviceException(429))).isFalse();
    }

    @Test
    @DisplayName("Returns false for 400/401/403/404/413 client errors")
    void test_clientErrors_returnFalse() {
        assertThat(predicate.test(serviceException(400))).isFalse();
        assertThat(predicate.test(serviceException(401))).isFalse();
        assertThat(predicate.test(serviceException(403))).isFalse();
        assertThat(predicate.test(serviceException(404))).isFalse();
        assertThat(predicate.test(serviceException(413))).isFalse();
    }

    @Test
    @DisplayName("Returns true for AnthropicIoException")
    void test_anthropicIoException_returnsTrue() {
        assertThat(predicate.test(new AnthropicIoException("connect timed out"))).isTrue();
    }

    @Test
    @DisplayName("Returns true for a raw IOException")
    void test_ioException_returnsTrue() {
        assertThat(predicate.test(new IOException("read timed out"))).isTrue();
    }

    @Test
    @DisplayName("Returns true for InterruptedIOException (a network timeout subtype)")
    void test_interruptedIoException_returnsTrue() {
        assertThat(predicate.test(new InterruptedIOException("timeout"))).isTrue();
    }

    @Test
    @DisplayName("Returns true when the IOException is found as the cause, not the top-level throwable")
    void test_ioExceptionAsCause_returnsTrue() {
        RuntimeException wrapper = new RuntimeException("wrapped", new IOException("read timed out"));

        assertThat(predicate.test(wrapper)).isTrue();
    }

    @Test
    @DisplayName("Returns true when a retryable AnthropicServiceException is found as the cause")
    void test_serviceExceptionAsCause_returnsTrue() {
        RuntimeException wrapper = new RuntimeException("wrapped", serviceException(500));

        assertThat(predicate.test(wrapper)).isTrue();
    }

    @Test
    @DisplayName("Returns false for an unrelated exception with no retryable cause")
    void test_unrelatedException_returnsFalse() {
        assertThat(predicate.test(new IllegalStateException("not a batch problem"))).isFalse();
    }

    @Test
    @DisplayName("Returns false for a non-retryable exception wrapping a non-retryable cause")
    void test_nonRetryableCauseChain_returnsFalse() {
        RuntimeException wrapper = new RuntimeException("wrapped", serviceException(400));

        assertThat(predicate.test(wrapper)).isFalse();
    }

    @Test
    @DisplayName("Does not loop forever on a throwable that is its own cause")
    void test_selfReferentialCause_doesNotLoopForever() {
        RuntimeException selfCaused = new RuntimeException("self") {
            private static final long serialVersionUID = 1L;

            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(predicate.test(selfCaused)).isFalse();
    }

    private static AnthropicServiceException serviceException(int statusCode) {
        AnthropicServiceException ex = mock(AnthropicServiceException.class);
        when(ex.statusCode()).thenReturn(statusCode);
        return ex;
    }
}
