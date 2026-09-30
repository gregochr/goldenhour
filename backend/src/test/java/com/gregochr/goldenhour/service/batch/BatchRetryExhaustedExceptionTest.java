package com.gregochr.goldenhour.service.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BatchRetryExhaustedException}.
 */
class BatchRetryExhaustedExceptionTest {

    @Test
    @DisplayName("carries the attempt count and wraps the cause with a descriptive message")
    void carriesAttemptsAndCause() {
        RuntimeException cause = new RuntimeException("Internal server error");

        BatchRetryExhaustedException exception = new BatchRetryExhaustedException(4, cause);

        assertThat(exception.getAttempts()).isEqualTo(4);
        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.getMessage())
                .isEqualTo("Batch creation failed after 4 attempt(s): Internal server error");
    }

    @Test
    @DisplayName("a single-attempt (non-retryable) failure still reports attempts=1")
    void singleAttemptFailure() {
        RuntimeException cause = new RuntimeException("invalid_request_error");

        BatchRetryExhaustedException exception = new BatchRetryExhaustedException(1, cause);

        assertThat(exception.getAttempts()).isEqualTo(1);
    }
}
