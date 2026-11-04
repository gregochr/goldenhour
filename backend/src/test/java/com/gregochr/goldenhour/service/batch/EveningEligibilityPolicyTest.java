package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EveningEligibilityPolicy}: every stability is evaluated on the near-term model.
 */
class EveningEligibilityPolicyTest {

    private static final EvaluationModel NEAR = EvaluationModel.HAIKU;
    private static final EvaluationModel FAR = EvaluationModel.SONNET;

    static Stream<org.junit.jupiter.params.provider.Arguments> everyStabilityAndEvent() {
        return Stream.of(TargetType.SUNRISE, TargetType.SUNSET).flatMap(type ->
                java.util.Arrays.stream(ForecastStability.values())
                        .map(s -> org.junit.jupiter.params.provider.Arguments.of(type, s)));
    }

    @ParameterizedTest
    @MethodSource("everyStabilityAndEvent")
    @DisplayName("tomorrow's sunrise and sunset are included on the near-term model whatever the stability")
    void everyStability_isIncludedOnTheNearTermModel(TargetType type, ForecastStability stability) {
        EligibilityDecision d = EveningEligibilityPolicy.INSTANCE.resolve(
                1, type, stability, NEAR, FAR);

        assertThat(d.eligible()).isTrue();
        assertThat(d.model()).isEqualTo(NEAR);
        assertThat(d.skipDisposition()).isNull();
    }
}
