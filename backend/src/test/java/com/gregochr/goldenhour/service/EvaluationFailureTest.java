package com.gregochr.goldenhour.service;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.gregochr.goldenhour.exception.ClaudeRefusalException;
import com.gregochr.goldenhour.exception.ClaudeReplyUnreadableException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one naming of a failed Claude evaluation as an {@code errorType} string, and the HTTP status that
 * travels with it.
 */
class EvaluationFailureTest {

    private static AnthropicServiceException serviceError(int status, String message) {
        AnthropicServiceException ex = mock(AnthropicServiceException.class);
        when(ex.statusCode()).thenReturn(status);
        when(ex.getMessage()).thenReturn(message);
        return ex;
    }

    @Test
    @DisplayName("errorTypeOf names an Anthropic service error by its HTTP status")
    void errorTypeOf_serviceError_isStatus() {
        assertThat(EvaluationFailure.errorTypeOf(serviceError(401, "invalid x-api-key")))
                .isEqualTo("anthropic_401");
        assertThat(EvaluationFailure.errorTypeOf(serviceError(529, "overloaded")))
                .isEqualTo("anthropic_529");
    }

    @Test
    @DisplayName("errorTypeOf names the content-filter 400 apart from any other 400")
    void errorTypeOf_contentFilter400_isNamed() {
        assertThat(EvaluationFailure.errorTypeOf(serviceError(400, "Output blocked by content filtering policy")))
                .isEqualTo("content_filter");
        assertThat(EvaluationFailure.errorTypeOf(serviceError(400, "max_tokens must be positive")))
                .isEqualTo("anthropic_400");
    }

    @Test
    @DisplayName("errorTypeOf names the refusal and unreadable-reply exceptions by class, not by message")
    void errorTypeOf_replyExceptions_areNamed() {
        assertThat(EvaluationFailure.errorTypeOf(new ClaudeRefusalException("anything")))
                .isEqualTo("refusal");
        assertThat(EvaluationFailure.errorTypeOf(new ClaudeReplyUnreadableException("anything")))
                .isEqualTo("reply_unreadable");
        assertThat(EvaluationFailure.errorTypeOf(new IllegalStateException("Claude refused")))
                .isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("errorTypeOf names the SDK's I/O failure (a timeout) by its class")
    void errorTypeOf_ioException_isItsSimpleName() {
        assertThat(EvaluationFailure.errorTypeOf(
                new AnthropicIoException("Request timed out", new SocketTimeoutException("timeout"))))
                .isEqualTo("AnthropicIoException");
    }

    @Test
    @DisplayName("errorTypeOf names an open or half-open circuit breaker refusal circuit_open")
    void errorTypeOf_circuitOpen() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("anthropic");
        breaker.transitionToOpenState();

        CallNotPermittedException refused = CallNotPermittedException.createCallNotPermittedException(breaker);

        assertThat(EvaluationFailure.errorTypeOf(refused)).isEqualTo("circuit_open");
        assertThat(EvaluationFailure.errorTypeOf(refused)).isEqualTo(EvaluationFailure.TYPE_CIRCUIT_OPEN);
    }

    @Test
    @DisplayName("errorTypeOf names a bulkhead refusal bulkhead_full")
    void errorTypeOf_bulkheadFull() {
        Bulkhead bulkhead = Bulkhead.ofDefaults("claude");
        BulkheadFullException refused = BulkheadFullException.createBulkheadFullException(bulkhead);

        assertThat(EvaluationFailure.errorTypeOf(refused)).isEqualTo("bulkhead_full");
        assertThat(EvaluationFailure.errorTypeOf(refused)).isEqualTo(EvaluationFailure.TYPE_BULKHEAD_FULL);
    }

    @ParameterizedTest(name = "HTTP {0} -> status {0}")
    @CsvSource({"401", "403", "429", "500", "529", "400"})
    @DisplayName("httpStatusOf() is the HTTP status of an Anthropic service error, content-filter 400 included")
    void httpStatusOf_anthropicServiceError_isItsStatus(int status) {
        assertThat(EvaluationFailure.httpStatusOf(serviceError(status, "x"))).isEqualTo(status);
    }

    @Test
    @DisplayName("httpStatusOf() is null for every failure that has no HTTP status of its own: a connection "
            + "failure, a refusal, an unreadable reply, an open breaker, a full bulkhead, anything else")
    void httpStatusOf_everythingElse_isNull() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("anthropic");
        breaker.transitionToOpenState();

        assertThat(EvaluationFailure.httpStatusOf(new AnthropicIoException("timed out"))).isNull();
        assertThat(EvaluationFailure.httpStatusOf(new ClaudeRefusalException("no"))).isNull();
        assertThat(EvaluationFailure.httpStatusOf(new ClaudeReplyUnreadableException("empty"))).isNull();
        assertThat(EvaluationFailure.httpStatusOf(CallNotPermittedException.createCallNotPermittedException(breaker)))
                .isNull();
        assertThat(EvaluationFailure.httpStatusOf(
                BulkheadFullException.createBulkheadFullException(Bulkhead.ofDefaults("claude")))).isNull();
        assertThat(EvaluationFailure.httpStatusOf(new IllegalStateException("boom"))).isNull();
    }
}
