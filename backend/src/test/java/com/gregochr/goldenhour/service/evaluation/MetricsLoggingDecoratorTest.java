package com.gregochr.goldenhour.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.anthropic.errors.AnthropicServiceException;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.exception.WeatherDataFetchException;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.JobRunService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

/**
 * Unit tests for {@link MetricsLoggingDecorator}.
 *
 * <p>Verifies that the decorator correctly delegates evaluation calls,
 * records metrics on success and failure, and handles edge cases such as
 * null job runs and different exception types.
 */
@ExtendWith(MockitoExtension.class)
class MetricsLoggingDecoratorTest {

    @Mock
    private EvaluationStrategy delegate;

    @Mock
    private JobRunService jobRunService;

    private JobRunEntity jobRun;
    private MetricsLoggingDecorator decorator;
    private AtmosphericData atmosphericData;
    private EvaluationDetail evaluationDetail;

    @BeforeEach
    void setUp() {
        jobRun = new JobRunEntity();
        jobRun.setId(42L);

        decorator = new MetricsLoggingDecorator(delegate, jobRunService, jobRun);
        atmosphericData = TestAtmosphericData.defaults();

        evaluationDetail = new EvaluationDetail(
                new SunsetEvaluation(4, 70, 75, "Promising conditions."),
                "user prompt text",
                "{\"rating\": 4, \"fiery_sky\": 70, \"golden_hour\": 75}",
                350L,
                new TokenUsage(500, 80, 200, 100));

        when(delegate.getEvaluationModel()).thenReturn(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("evaluate() delegates to evaluateWithDetails() and returns the evaluation")
    void evaluate_delegatesToEvaluateWithDetails() {
        when(delegate.evaluateWithDetails(atmosphericData)).thenReturn(evaluationDetail);

        SunsetEvaluation result = decorator.evaluate(atmosphericData);

        assertThat(result).isEqualTo(evaluationDetail.evaluation());
        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isEqualTo(70);
        assertThat(result.goldenHourPotential()).isEqualTo(75);
    }

    @Test
    @DisplayName("evaluateWithDetails() delegates to the wrapped strategy and returns the full detail")
    void evaluateWithDetails_delegatesAndReturnsDetail() {
        when(delegate.evaluateWithDetails(atmosphericData)).thenReturn(evaluationDetail);

        EvaluationDetail result = decorator.evaluateWithDetails(atmosphericData);

        assertThat(result).isSameAs(evaluationDetail);
        assertThat(result.promptSent()).isEqualTo("user prompt text");
        assertThat(result.durationMs()).isEqualTo(350L);
        assertThat(result.tokenUsage().inputTokens()).isEqualTo(500);
        verify(delegate, times(1)).evaluateWithDetails(atmosphericData);
    }

    @Test
    @DisplayName("evaluateWithDetails() logs a successful API call with correct arguments")
    void evaluateWithDetails_logsApiCallOnSuccess() {
        when(delegate.evaluateWithDetails(atmosphericData)).thenReturn(evaluationDetail);

        decorator.evaluateWithDetails(atmosphericData);

        ArgumentCaptor<TokenUsage> tokenCaptor = ArgumentCaptor.forClass(TokenUsage.class);
        verify(jobRunService).logAnthropicApiCall(
                eq(42L),
                eq(350L),
                eq(200),
                eq(null),
                eq(true),
                eq(null),
                eq(EvaluationModel.SONNET),
                tokenCaptor.capture(),
                eq(false),
                eq(LocalDate.of(2026, 6, 21)),
                eq(TargetType.SUNSET));

        TokenUsage captured = tokenCaptor.getValue();
        assertThat(captured.inputTokens()).isEqualTo(500);
        assertThat(captured.outputTokens()).isEqualTo(80);
        assertThat(captured.cacheCreationInputTokens()).isEqualTo(200);
        assertThat(captured.cacheReadInputTokens()).isEqualTo(100);
    }

    @Test
    @DisplayName("evaluateWithDetails() logs a failed API call when AnthropicServiceException is thrown")
    void evaluateWithDetails_logsApiCallOnFailure() {
        AnthropicServiceException ex = mock(AnthropicServiceException.class);
        when(ex.statusCode()).thenReturn(429);
        when(ex.getMessage()).thenReturn("rate limit exceeded");

        when(delegate.evaluateWithDetails(atmosphericData)).thenThrow(ex);

        assertThatThrownBy(() -> decorator.evaluateWithDetails(atmosphericData))
                .isSameAs(ex);

        verify(jobRunService).logAnthropicApiCall(
                eq(42L),
                eq(0L),
                eq(429),
                eq("rate limit exceeded"),
                eq(false),
                eq("rate limit exceeded"),
                eq(EvaluationModel.SONNET),
                eq(TokenUsage.EMPTY),
                eq(false),
                eq(LocalDate.of(2026, 6, 21)),
                eq(TargetType.SUNSET));
    }

    @Test
    @DisplayName("evaluateWithDetails() rethrows WeatherDataFetchException without recording metrics")
    void evaluateWithDetails_rethrowsWeatherDataFetchException() {
        WeatherDataFetchException ex = new WeatherDataFetchException(
                "Weather data unavailable", "Durham UK", "SUNSET", null);

        when(delegate.evaluateWithDetails(atmosphericData)).thenThrow(ex);

        assertThatThrownBy(() -> decorator.evaluateWithDetails(atmosphericData))
                .isSameAs(ex);

        verifyNoInteractions(jobRunService);
    }

    @Test
    @DisplayName("evaluateWithDetails() rethrows AnthropicServiceException after logging")
    void evaluateWithDetails_rethrowsAnthropicException() {
        AnthropicServiceException ex = mock(AnthropicServiceException.class);
        when(ex.statusCode()).thenReturn(529);
        when(ex.getMessage()).thenReturn("overloaded");

        when(delegate.evaluateWithDetails(atmosphericData)).thenThrow(ex);

        assertThatThrownBy(() -> decorator.evaluateWithDetails(atmosphericData))
                .isSameAs(ex);
    }

    @Test
    @DisplayName("getEvaluationModel() delegates to the wrapped strategy")
    void getEvaluationModel_delegatesToDelegate() {
        when(delegate.getEvaluationModel()).thenReturn(EvaluationModel.SONNET);

        EvaluationModel result = decorator.getEvaluationModel();

        assertThat(result).isEqualTo(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("evaluateWithDetails() does not record metrics when jobRun is null")
    void evaluateWithDetails_noMetricsWhenJobRunNull() {
        MetricsLoggingDecorator nullRunDecorator =
                new MetricsLoggingDecorator(delegate, jobRunService, null);
        when(delegate.evaluateWithDetails(atmosphericData)).thenReturn(evaluationDetail);

        EvaluationDetail result = nullRunDecorator.evaluateWithDetails(atmosphericData);

        assertThat(result).isSameAs(evaluationDetail);
        verifyNoInteractions(jobRunService);
    }
}
