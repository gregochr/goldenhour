package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EvaluationService}.
 */
@ExtendWith(MockitoExtension.class)
class EvaluationServiceTest {

    @Mock
    private EvaluationStrategy strategy;

    @InjectMocks
    private EvaluationService evaluationService;

    @Test
    @DisplayName("evaluate() delegates to the injected strategy")
    void evaluate_delegatesToStrategy() {
        AtmosphericData data = new AtmosphericData(
                "Durham UK", LocalDateTime.of(2026, 6, 21, 20, 47), TargetType.SUNSET,
                10, 50, 30, 25000,
                new BigDecimal("3.50"), 225, new BigDecimal("0.00"),
                62, 3, 1200, new BigDecimal("180.00"),
                new BigDecimal("8.50"), new BigDecimal("2.10"), new BigDecimal("0.120"),
                null, null, null, null, null, null);

        SunsetEvaluation expected = new SunsetEvaluation(4, "Promising conditions.");
        when(strategy.evaluate(data)).thenReturn(expected);

        SunsetEvaluation result = evaluationService.evaluate(data);

        assertThat(result).isSameAs(expected);
        verify(strategy).evaluate(data);
    }
}
