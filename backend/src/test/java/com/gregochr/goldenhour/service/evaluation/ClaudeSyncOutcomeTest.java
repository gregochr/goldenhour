package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a synchronous outcome carries for the {@code api_call_log} row: the HTTP status when the failure
 * had one, the error type always, taken from the exception rather than re-derived from a string.
 */
class ClaudeSyncOutcomeTest {

    private static AnthropicServiceException serviceError(int status, String message) {
        AnthropicServiceException ex = mock(AnthropicServiceException.class);
        when(ex.statusCode()).thenReturn(status);
        when(ex.getMessage()).thenReturn(message);
        return ex;
    }

    @Test
    @DisplayName("a success is logged as 200 with no error type")
    void success_isLoggedAs200() {
        ClaudeSyncOutcome outcome = ClaudeSyncOutcome.success(
                "text", new TokenUsage(1, 2, 0, 0), EvaluationModel.HAIKU, 5L);

        assertThat(outcome.loggedStatusCode()).isEqualTo(200);
        assertThat(outcome.statusCode()).isNull();
        assertThat(outcome.errorType()).isNull();
    }

    @Test
    @DisplayName("a failure built from an Anthropic service error carries its status and anthropic_<status>")
    void failureFromServiceError_carriesStatusAndType() {
        ClaudeSyncOutcome outcome = ClaudeSyncOutcome.failure(
                serviceError(529, "Overloaded"), EvaluationModel.SONNET, 1500L);

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.statusCode()).isEqualTo(529);
        assertThat(outcome.loggedStatusCode()).isEqualTo(529);
        assertThat(outcome.errorType()).isEqualTo("anthropic_529");
        assertThat(outcome.errorMessage()).isEqualTo("Overloaded");
        assertThat(outcome.model()).isEqualTo(EvaluationModel.SONNET);
        assertThat(outcome.durationMs()).isEqualTo(1500L);
        assertThat(outcome.rawText()).isNull();
        assertThat(outcome.tokenUsage()).isNull();
    }

    @Test
    @DisplayName("the content-filter 400 keeps its status 400 and is typed content_filter")
    void failureFromContentFilter_keepsStatus400() {
        ClaudeSyncOutcome outcome = ClaudeSyncOutcome.failure(
                serviceError(400, "Output blocked by content filtering policy"), EvaluationModel.HAIKU, 10L);

        assertThat(outcome.statusCode()).isEqualTo(400);
        assertThat(outcome.errorType()).isEqualTo("content_filter");
    }

    @Test
    @DisplayName("a failure with no HTTP status (a connection failure) logs a null status, not a made-up one")
    void failureWithoutStatus_logsNull() {
        ClaudeSyncOutcome outcome = ClaudeSyncOutcome.failure(
                new AnthropicIoException("timed out"), EvaluationModel.HAIKU, 90_000L);

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.statusCode()).isNull();
        assertThat(outcome.loggedStatusCode()).isNull();
        assertThat(outcome.errorType()).isEqualTo("AnthropicIoException");
    }

    @Test
    @DisplayName("a failure built from a type string (a reply the parser rejected) has no HTTP status")
    void failureFromTypeString_hasNoStatus() {
        ClaudeSyncOutcome outcome = ClaudeSyncOutcome.failure("parse_error", "bad json", EvaluationModel.HAIKU, 7L);

        assertThat(outcome.errorType()).isEqualTo("parse_error");
        assertThat(outcome.loggedStatusCode()).isNull();
    }
}
