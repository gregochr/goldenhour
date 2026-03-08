package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("evaluateWithDetails() returns detail with null evaluation fields")
    void evaluateWithDetails_returnsNullEvaluationDetail() {
        AtmosphericData data = buildAtmosphericData();

        EvaluationDetail detail = strategy.evaluateWithDetails(data);

        assertThat(detail.evaluation().rating()).isNull();
        assertThat(detail.evaluation().fierySkyPotential()).isNull();
        assertThat(detail.promptSent()).isNull();
        assertThat(detail.rawResponse()).isNull();
        assertThat(detail.durationMs()).isZero();
        assertThat(detail.tokenUsage()).isEqualTo(TokenUsage.EMPTY);
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
        SunsetEvaluation second = strategy.evaluate(data);

        assertThat(first).isSameAs(second);
    }

    private AtmosphericData buildAtmosphericData() {
        return TestAtmosphericData.builder()
                .locationName("Test Location")
                .build();
    }
}
