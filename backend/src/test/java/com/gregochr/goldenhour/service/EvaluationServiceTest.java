package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.service.evaluation.HaikuEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.NoOpEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.OpusEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.SonnetEvaluationStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
    private HaikuEvaluationStrategy haikuStrategy;

    @Mock
    private SonnetEvaluationStrategy sonnetStrategy;

    @Mock
    private OpusEvaluationStrategy opusStrategy;

    @Mock
    private NoOpEvaluationStrategy noOpStrategy;

    @InjectMocks
    private EvaluationService evaluationService;

    @Test
    @DisplayName("evaluate() with SONNET delegates to the Sonnet strategy")
    void evaluate_sonnet_delegatesToSonnetStrategy() {
        AtmosphericData data = buildAtmosphericData();
        SunsetEvaluation expected = new SunsetEvaluation(4, 70, 75, "Promising conditions.");
        when(sonnetStrategy.evaluate(data, null)).thenReturn(expected);

        SunsetEvaluation result = evaluationService.evaluate(data, EvaluationModel.SONNET);

        assertThat(result).isSameAs(expected);
        verify(sonnetStrategy).evaluate(data, null);
        verifyNoInteractions(haikuStrategy);
        verifyNoInteractions(opusStrategy);
    }

    @Test
    @DisplayName("evaluate() with HAIKU delegates to the Haiku strategy")
    void evaluate_haiku_delegatesToHaikuStrategy() {
        AtmosphericData data = buildAtmosphericData();
        SunsetEvaluation expected = new SunsetEvaluation(4, 65, 70, "Good conditions.");
        when(haikuStrategy.evaluate(data, null)).thenReturn(expected);

        SunsetEvaluation result = evaluationService.evaluate(data, EvaluationModel.HAIKU);

        assertThat(result).isSameAs(expected);
        verify(haikuStrategy).evaluate(data, null);
        verifyNoInteractions(sonnetStrategy);
        verifyNoInteractions(opusStrategy);
    }

    @Test
    @DisplayName("evaluate() with OPUS delegates to the Opus strategy")
    void evaluate_opus_delegatesToOpusStrategy() {
        AtmosphericData data = buildAtmosphericData();
        SunsetEvaluation expected = new SunsetEvaluation(5, 85, 80, "Outstanding conditions.");
        when(opusStrategy.evaluate(data, null)).thenReturn(expected);

        SunsetEvaluation result = evaluationService.evaluate(data, EvaluationModel.OPUS);

        assertThat(result).isSameAs(expected);
        verify(opusStrategy).evaluate(data, null);
        verifyNoInteractions(haikuStrategy);
        verifyNoInteractions(sonnetStrategy);
    }

    @Test
    @DisplayName("evaluateWithDetails() with HAIKU delegates to Haiku strategy")
    void evaluateWithDetails_haiku_delegatesToHaikuStrategy() {
        AtmosphericData data = buildAtmosphericData();
        EvaluationDetail expected = new EvaluationDetail(
                new SunsetEvaluation(4, 65, 70, "Good."), "prompt", "raw", 500L);
        when(haikuStrategy.evaluateWithDetails(data, null)).thenReturn(expected);

        EvaluationDetail result = evaluationService.evaluateWithDetails(data, EvaluationModel.HAIKU, null);

        assertThat(result).isSameAs(expected);
        verify(haikuStrategy).evaluateWithDetails(data, null);
    }

    @Test
    @DisplayName("evaluateWithDetails() with SONNET delegates to Sonnet strategy")
    void evaluateWithDetails_sonnet_delegatesToSonnetStrategy() {
        AtmosphericData data = buildAtmosphericData();
        EvaluationDetail expected = new EvaluationDetail(
                new SunsetEvaluation(4, 70, 75, "Good."), "prompt", "raw", 800L);
        when(sonnetStrategy.evaluateWithDetails(data, null)).thenReturn(expected);

        EvaluationDetail result = evaluationService.evaluateWithDetails(data, EvaluationModel.SONNET, null);

        assertThat(result).isSameAs(expected);
        verify(sonnetStrategy).evaluateWithDetails(data, null);
    }

    @Test
    @DisplayName("evaluateWithDetails() with OPUS delegates to Opus strategy")
    void evaluateWithDetails_opus_delegatesToOpusStrategy() {
        AtmosphericData data = buildAtmosphericData();
        EvaluationDetail expected = new EvaluationDetail(
                new SunsetEvaluation(5, 85, 80, "Outstanding."), "prompt", "raw", 1200L);
        when(opusStrategy.evaluateWithDetails(data, null)).thenReturn(expected);

        EvaluationDetail result = evaluationService.evaluateWithDetails(data, EvaluationModel.OPUS, null);

        assertThat(result).isSameAs(expected);
        verify(opusStrategy).evaluateWithDetails(data, null);
    }

    @Test
    @DisplayName("evaluateWithDetails() with WILDLIFE throws IllegalArgumentException")
    void evaluateWithDetails_wildlife_throws() {
        AtmosphericData data = buildAtmosphericData();

        assertThatThrownBy(() -> evaluationService.evaluateWithDetails(data, EvaluationModel.WILDLIFE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WILDLIFE");
    }

    private AtmosphericData buildAtmosphericData() {
        return new AtmosphericData(
                "Durham UK", LocalDateTime.of(2026, 6, 21, 20, 47), TargetType.SUNSET,
                10, 50, 30, 25000,
                new BigDecimal("3.50"), 225, new BigDecimal("0.00"),
                62, 3, 1200, new BigDecimal("180.00"),
                new BigDecimal("8.50"), new BigDecimal("2.10"), new BigDecimal("0.120"),
                null, null, null,
                null, null, null, null, null, null);
    }
}
