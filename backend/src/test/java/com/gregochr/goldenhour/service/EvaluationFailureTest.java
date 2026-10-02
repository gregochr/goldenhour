package com.gregochr.goldenhour.service;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.exception.ClaudeRefusalException;
import com.gregochr.goldenhour.exception.ClaudeReplyUnreadableException;
import com.gregochr.goldenhour.exception.EvaluationFailedException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.SocketTimeoutException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one mapping from a failed Claude evaluation to the fixed phrase an admin reads: every kind the
 * code can tell apart gets exactly its phrase, and nothing an exception carries ever reaches one.
 */
class EvaluationFailureTest {

    private static final String UNKNOWN = "Evaluation failed (see server log).";

    private static AnthropicServiceException serviceError(int status, String message) {
        AnthropicServiceException ex = mock(AnthropicServiceException.class);
        when(ex.statusCode()).thenReturn(status);
        when(ex.getMessage()).thenReturn(message);
        return ex;
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "anthropic_401|Claude rejected the API key.",
            "anthropic_403|Claude rejected the API key.",
            "anthropic_429|Claude's rate limit was reached.",
            "anthropic_529|Claude was overloaded.",
            "anthropic_500|Claude returned a server error.",
            "anthropic_502|Claude returned a server error.",
            "anthropic_503|Claude returned a server error.",
            "anthropic_599|Claude returned a server error.",
            "AnthropicIoException|Claude could not be reached.",
            "parse_error|Claude's reply could not be read.",
            "reply_unreadable|Claude's reply could not be read.",
            "refusal|Claude declined to evaluate this place.",
            "content_filter|Claude declined to evaluate this place.",
            "anthropic_400|Evaluation failed (see server log).",
            "anthropic_404|Evaluation failed (see server log).",
            "anthropic_abc|Evaluation failed (see server log).",
            "CallNotPermittedException|Evaluation failed (see server log).",
            "IllegalStateException|Evaluation failed (see server log).",
            "unknown|Evaluation failed (see server log).",
    })
    @DisplayName("each errorType the engine reports maps to exactly its fixed phrase")
    void fromErrorType_mapsToExactPhrase(String errorType, String phrase) {
        assertThat(EvaluationFailure.fromErrorType(errorType).reason()).isEqualTo(phrase);
    }

    @Test
    @DisplayName("a null errorType is the fallback phrase, not an NPE")
    void fromErrorType_null_isFallback() {
        assertThat(EvaluationFailure.fromErrorType(null)).isEqualTo(EvaluationFailure.UNKNOWN);
        assertThat(EvaluationFailure.UNKNOWN.reason()).isEqualTo(UNKNOWN);
    }

    @Test
    @DisplayName("only a rejected key stops the run; rate limit, overload, server error, I/O and the rest do not")
    void stopsRun_onlyForRejectedKey() {
        assertThat(EvaluationFailure.values()).filteredOn(EvaluationFailure::stopsRun)
                .containsExactly(EvaluationFailure.KEY_REJECTED);
        assertThat(EvaluationFailure.fromErrorType("anthropic_401").stopsRun()).isTrue();
        assertThat(EvaluationFailure.fromErrorType("anthropic_403").stopsRun()).isTrue();
        assertThat(EvaluationFailure.fromErrorType("anthropic_429").stopsRun()).isFalse();
        assertThat(EvaluationFailure.fromErrorType("anthropic_529").stopsRun()).isFalse();
        assertThat(EvaluationFailure.fromErrorType("anthropic_500").stopsRun()).isFalse();
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
    @DisplayName("of() maps a thrown exception through errorTypeOf: a timeout is 'could not be reached'")
    void of_ioException_isUnreachable() {
        EvaluationFailure kind = EvaluationFailure.of(
                new AnthropicIoException("Request timed out", new SocketTimeoutException("timeout")));

        assertThat(kind).isEqualTo(EvaluationFailure.UNREACHABLE);
        assertThat(kind.reason()).isEqualTo("Claude could not be reached.");
    }

    @Test
    @DisplayName("of() reads the errorType an EvaluationFailedException carries")
    void of_evaluationFailedException_usesItsErrorType() {
        EvaluationFailedException failed = new EvaluationFailedException(
                "anthropic_401", "invalid x-api-key", "Durham", TargetType.SUNSET, LocalDate.parse("2026-10-03"));

        assertThat(EvaluationFailure.of(failed)).isEqualTo(EvaluationFailure.KEY_REJECTED);
    }

    @Test
    @DisplayName("of() finds a recognised failure down the cause chain")
    void of_looksDownTheCauseChain() {
        RuntimeException wrapped = new RuntimeException("wrapper", serviceError(429, "slow down"));

        assertThat(EvaluationFailure.of(wrapped)).isEqualTo(EvaluationFailure.RATE_LIMITED);
    }

    @Test
    @DisplayName("of() gives the fallback for an open circuit breaker: it has no HTTP status to read")
    void of_circuitOpen_isFallback() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("anthropic");
        breaker.transitionToOpenState();

        assertThat(EvaluationFailure.of(CallNotPermittedException.createCallNotPermittedException(breaker)))
                .isEqualTo(EvaluationFailure.UNKNOWN);
    }

    @Test
    @DisplayName("a phrase never contains the exception's message or class name, even a hostile one")
    void reason_neverCarriesTheRawMessage() {
        String hostile = "401: invalid x-api-key sk-ant-api03-SECRET at https://api.anthropic.com";
        for (EvaluationFailure kind : new EvaluationFailure[] {
                EvaluationFailure.of(serviceError(401, hostile)),
                EvaluationFailure.of(serviceError(500, hostile)),
                EvaluationFailure.of(new IllegalStateException(hostile)),
                EvaluationFailure.of(new ClaudeRefusalException(hostile))}) {
            assertThat(kind.reason()).doesNotContain("SECRET").doesNotContain("sk-ant")
                    .doesNotContain("Exception").doesNotContain("https://");
        }
    }

    @Test
    @DisplayName("the three run-level and per-place phrases are exactly the agreed text")
    void fixedPhrases_areExactlyTheAgreedText() {
        assertThat(EvaluationFailure.KEY_REJECTED.reason()).isEqualTo("Claude rejected the API key.");
        assertThat(EvaluationFailure.REASON_NOT_ATTEMPTED)
                .isEqualTo("Not attempted: the run stopped because Claude rejected the API key.");
        assertThat(EvaluationFailure.REASON_RUN_STOPPED)
                .isEqualTo("Claude rejected the API key. The run was stopped; no further places were attempted.");
    }
}
