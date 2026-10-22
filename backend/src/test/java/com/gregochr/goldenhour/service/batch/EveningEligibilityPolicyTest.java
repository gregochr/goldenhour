package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EveningEligibilityPolicy}: every stability is evaluated on the near-term model.
 */
class EveningEligibilityPolicyTest {

    private static final EvaluationModel NEAR = EvaluationModel.HAIKU;
    private static final EvaluationModel FAR = EvaluationModel.SONNET;

    @ParameterizedTest
    @EnumSource(ForecastStability.class)
    @DisplayName("tomorrow's sunrise is included on the near-term model whatever the stability")
    void everyStability_isIncludedOnTheNearTermModel(ForecastStability stability) {
        EligibilityDecision d = EveningEligibilityPolicy.INSTANCE.resolve(
                1, TargetType.SUNRISE, stability, NEAR, FAR);

        assertThat(d.eligible()).isTrue();
        assertThat(d.model()).isEqualTo(NEAR);
        assertThat(d.skipDisposition()).isNull();
    }
}
