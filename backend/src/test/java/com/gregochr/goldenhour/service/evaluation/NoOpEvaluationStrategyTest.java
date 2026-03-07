package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NoOpEvaluationStrategy}.
 */
class NoOpEvaluationStrategyTest {

    private final NoOpEvaluationStrategy strategy = new NoOpEvaluationStrategy();

    @Test
    @DisplayName("evaluate(data) returns null evaluation")
    void evaluate_returnsNullEvaluation() {
        AtmosphericData data = buildAtmosphericData();

        SunsetEvaluation result = strategy.evaluate(data);

        assertThat(result.rating()).isNull();
        assertThat(result.fierySkyPotential()).isNull();
        assertThat(result.goldenHourPotential()).isNull();
        assertThat(result.summary()).isNull();
    }

    @Test
    @DisplayName("evaluate(data, jobRun) returns null evaluation")
    void evaluate_withJobRun_returnsNullEvaluation() {
        AtmosphericData data = buildAtmosphericData();
        JobRunEntity jobRun = new JobRunEntity();

        SunsetEvaluation result = strategy.evaluate(data, jobRun);

        assertThat(result.rating()).isNull();
        assertThat(result.fierySkyPotential()).isNull();
        assertThat(result.goldenHourPotential()).isNull();
        assertThat(result.summary()).isNull();
    }

    @Test
    @DisplayName("getEvaluationModel() returns WILDLIFE")
    void getEvaluationModel_returnsWildlife() {
        assertThat(strategy.getEvaluationModel()).isEqualTo(EvaluationModel.WILDLIFE);
    }

    @Test
    @DisplayName("evaluate returns same singleton instance each time")
    void evaluate_returnsSameSingleton() {
        AtmosphericData data = buildAtmosphericData();

        SunsetEvaluation first = strategy.evaluate(data);
        SunsetEvaluation second = strategy.evaluate(data, null);

        assertThat(first).isSameAs(second);
    }

    private AtmosphericData buildAtmosphericData() {
        return new AtmosphericData(
                "Test Location", LocalDateTime.of(2026, 6, 21, 20, 47), TargetType.SUNSET,
                10, 50, 30, 25000,
                new BigDecimal("3.50"), 225, new BigDecimal("0.00"),
                62, 3, 1200, new BigDecimal("180.00"),
                new BigDecimal("8.50"), new BigDecimal("2.10"), new BigDecimal("0.120"),
                null, null, null,
                null,
                null, null, null, null, null, null);
    }
}
