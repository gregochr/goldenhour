package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EvaluationService}.
 */
@ExtendWith(MockitoExtension.class)
class EvaluationServiceTest {

    @Mock
    private EvaluationStrategy haikuStrategy;

    @Mock
    private EvaluationStrategy sonnetStrategy;

    @Mock
    private EvaluationStrategy opusStrategy;

    @Mock
    private EvaluationStrategy noOpStrategy;

    @Mock
    private JobRunService jobRunService;

    private EvaluationService evaluationService;

    @BeforeEach
    void setUp() {
        Map<EvaluationModel, EvaluationStrategy> strategies = Map.of(
                EvaluationModel.HAIKU, haikuStrategy,
                EvaluationModel.SONNET, sonnetStrategy,
                EvaluationModel.OPUS, opusStrategy,
                EvaluationModel.WILDLIFE, noOpStrategy);
        evaluationService = new EvaluationService(strategies, jobRunService);
    }

    @Test
    @DisplayName("evaluate() with SONNET delegates to the Sonnet strategy")
    void evaluate_sonnet_delegatesToSonnetStrategy() {
        AtmosphericData data = TestAtmosphericData.defaults();
        SunsetEvaluation expected = new SunsetEvaluation(4, 70, 75, "Promising conditions.");
        when(sonnetStrategy.evaluate(data)).thenReturn(expected);

        SunsetEvaluation result = evaluationService.evaluate(data, EvaluationModel.SONNET);

        assertThat(result).isSameAs(expected);
        verify(sonnetStrategy).evaluate(data);
        verifyNoInteractions(haikuStrategy);
        verifyNoInteractions(opusStrategy);
    }

    @Test
    @DisplayName("evaluate() with HAIKU delegates to the Haiku strategy")
    void evaluate_haiku_delegatesToHaikuStrategy() {
        AtmosphericData data = TestAtmosphericData.defaults();
        SunsetEvaluation expected = new SunsetEvaluation(4, 65, 70, "Good conditions.");
        when(haikuStrategy.evaluate(data)).thenReturn(expected);

        SunsetEvaluation result = evaluationService.evaluate(data, EvaluationModel.HAIKU);

        assertThat(result).isSameAs(expected);
        verify(haikuStrategy).evaluate(data);
        verifyNoInteractions(sonnetStrategy);
        verifyNoInteractions(opusStrategy);
    }

    @Test
    @DisplayName("evaluate() with OPUS delegates to the Opus strategy")
    void evaluate_opus_delegatesToOpusStrategy() {
        AtmosphericData data = TestAtmosphericData.defaults();
        SunsetEvaluation expected = new SunsetEvaluation(5, 85, 80, "Outstanding conditions.");
        when(opusStrategy.evaluate(data)).thenReturn(expected);

        SunsetEvaluation result = evaluationService.evaluate(data, EvaluationModel.OPUS);

        assertThat(result).isSameAs(expected);
        verify(opusStrategy).evaluate(data);
        verifyNoInteractions(haikuStrategy);
        verifyNoInteractions(sonnetStrategy);
    }

    @Test
    @DisplayName("evaluate() with WILDLIFE delegates to the NoOp strategy")
    void evaluate_wildlife_delegatesToNoOpStrategy() {
        AtmosphericData data = TestAtmosphericData.defaults();
        SunsetEvaluation expected = new SunsetEvaluation(null, null, null, null);
        when(noOpStrategy.evaluate(data)).thenReturn(expected);

        SunsetEvaluation result = evaluationService.evaluate(data, EvaluationModel.WILDLIFE);

        assertThat(result).isSameAs(expected);
        verify(noOpStrategy).evaluate(data);
    }

    @Test
    @DisplayName("evaluateWithDetails() with HAIKU delegates to Haiku strategy")
    void evaluateWithDetails_haiku_delegatesToHaikuStrategy() {
        AtmosphericData data = TestAtmosphericData.defaults();
        EvaluationDetail expected = new EvaluationDetail(
                new SunsetEvaluation(4, 65, 70, "Good."), "prompt", "raw", 500L, TokenUsage.EMPTY);
        when(haikuStrategy.evaluateWithDetails(data)).thenReturn(expected);

        EvaluationDetail result = evaluationService.evaluateWithDetails(data, EvaluationModel.HAIKU, null);

        assertThat(result).isSameAs(expected);
        verify(haikuStrategy).evaluateWithDetails(data);
    }

    @Test
    @DisplayName("evaluateWithDetails() with SONNET delegates to Sonnet strategy")
    void evaluateWithDetails_sonnet_delegatesToSonnetStrategy() {
        AtmosphericData data = TestAtmosphericData.defaults();
        EvaluationDetail expected = new EvaluationDetail(
                new SunsetEvaluation(4, 70, 75, "Good."), "prompt", "raw", 800L, TokenUsage.EMPTY);
        when(sonnetStrategy.evaluateWithDetails(data)).thenReturn(expected);

        EvaluationDetail result = evaluationService.evaluateWithDetails(data, EvaluationModel.SONNET, null);

        assertThat(result).isSameAs(expected);
        verify(sonnetStrategy).evaluateWithDetails(data);
    }

    @Test
    @DisplayName("evaluateWithDetails() with OPUS delegates to Opus strategy")
    void evaluateWithDetails_opus_delegatesToOpusStrategy() {
        AtmosphericData data = TestAtmosphericData.defaults();
        EvaluationDetail expected = new EvaluationDetail(
                new SunsetEvaluation(5, 85, 80, "Outstanding."), "prompt", "raw", 1200L, TokenUsage.EMPTY);
        when(opusStrategy.evaluateWithDetails(data)).thenReturn(expected);

        EvaluationDetail result = evaluationService.evaluateWithDetails(data, EvaluationModel.OPUS, null);

        assertThat(result).isSameAs(expected);
        verify(opusStrategy).evaluateWithDetails(data);
    }

    @Test
    @DisplayName("evaluate() with jobRun wraps strategy with MetricsLoggingDecorator")
    void evaluate_withJobRun_usesDecorator() {
        AtmosphericData data = TestAtmosphericData.defaults();
        JobRunEntity jobRun = JobRunEntity.builder().id(1L).build();
        EvaluationDetail detail = new EvaluationDetail(
                new SunsetEvaluation(4, 70, 75, "Good."), "prompt", "raw", 500L,
                new TokenUsage(100, 50, 0, 0));

        // When jobRun is provided, the decorator calls evaluateWithDetails on the delegate
        when(haikuStrategy.getEvaluationModel()).thenReturn(EvaluationModel.HAIKU);
        when(haikuStrategy.evaluateWithDetails(data)).thenReturn(detail);

        SunsetEvaluation result = evaluationService.evaluate(data, EvaluationModel.HAIKU, jobRun);

        assertThat(result.fierySkyPotential()).isEqualTo(70);
        // The decorator calls evaluateWithDetails, not evaluate directly
        verify(haikuStrategy).evaluateWithDetails(data);
    }

    @Test
    @DisplayName("evaluate() without jobRun does NOT wrap with decorator — calls evaluate directly")
    void evaluate_withoutJobRun_callsEvaluateDirectly() {
        AtmosphericData data = TestAtmosphericData.defaults();
        SunsetEvaluation expected = new SunsetEvaluation(3, 50, 55, "Moderate.");
        when(sonnetStrategy.evaluate(data)).thenReturn(expected);

        SunsetEvaluation result = evaluationService.evaluate(data, EvaluationModel.SONNET, null);

        assertThat(result).isSameAs(expected);
        // Without jobRun, evaluate (not evaluateWithDetails) is called
        verify(sonnetStrategy).evaluate(data);
    }

    @Test
    @DisplayName("evaluate() with unknown model throws IllegalArgumentException")
    void evaluate_unknownModel_throwsIllegalArgumentException() {
        AtmosphericData data = TestAtmosphericData.defaults();
        // Create a service with only HAIKU — requesting SONNET should fail
        Map<EvaluationModel, EvaluationStrategy> limited = Map.of(
                EvaluationModel.HAIKU, haikuStrategy);
        EvaluationService limitedService = new EvaluationService(limited, jobRunService);

        assertThatThrownBy(() -> limitedService.evaluate(data, EvaluationModel.SONNET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SONNET");
    }

    @Test
    @DisplayName("evaluateWithDetails() with unknown model throws IllegalArgumentException")
    void evaluateWithDetails_unknownModel_throwsIllegalArgumentException() {
        AtmosphericData data = TestAtmosphericData.defaults();
        Map<EvaluationModel, EvaluationStrategy> limited = Map.of(
                EvaluationModel.HAIKU, haikuStrategy);
        EvaluationService limitedService = new EvaluationService(limited, jobRunService);

        assertThatThrownBy(() -> limitedService.evaluateWithDetails(
                data, EvaluationModel.SONNET, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SONNET");
    }

    @Test
    @DisplayName("evaluate() with jobRun logs API call via decorator")
    void evaluate_withJobRun_logsApiCall() {
        AtmosphericData data = TestAtmosphericData.defaults();
        JobRunEntity jobRun = JobRunEntity.builder().id(42L).build();
        EvaluationDetail detail = new EvaluationDetail(
                new SunsetEvaluation(5, 90, 85, "Spectacular."), "prompt", "raw", 600L,
                new TokenUsage(200, 100, 500, 100));

        when(opusStrategy.getEvaluationModel()).thenReturn(EvaluationModel.OPUS);
        when(opusStrategy.evaluateWithDetails(data)).thenReturn(detail);

        SunsetEvaluation result = evaluationService.evaluate(data, EvaluationModel.OPUS, jobRun);

        assertThat(result.fierySkyPotential()).isEqualTo(90);
        // The decorator should have logged the API call
        verify(jobRunService).logAnthropicApiCall(
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(600L),
                org.mockito.ArgumentMatchers.eq(200),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(EvaluationModel.OPUS),
                org.mockito.ArgumentMatchers.eq(new TokenUsage(200, 100, 500, 100)),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.any(LocalDate.class),
                org.mockito.ArgumentMatchers.any(TargetType.class));
    }
}
