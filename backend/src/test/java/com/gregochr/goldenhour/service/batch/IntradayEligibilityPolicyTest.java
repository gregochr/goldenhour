package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IntradayEligibilityPolicy} — the later-look gate.
 *
 * <p>The boundary is no longer stability alone. TRANSITIONAL and UNSETTLED are always evaluated;
 * SETTLED is skipped only where a later look before the event is structurally guaranteed, which is
 * tomorrow's sunset and nothing else.
 */
class IntradayEligibilityPolicyTest {

    private static final EvaluationModel NEAR = EvaluationModel.HAIKU;
    private static final EvaluationModel FAR = EvaluationModel.SONNET;

    private static final int TODAY = 0;
    private static final int TOMORROW = 1;

    private final IntradayEligibilityPolicy policy = IntradayEligibilityPolicy.INSTANCE;

    @Nested
    @DisplayName("SETTLED — the only stability the gate reasons about")
    class Settled {

        @Test
        @DisplayName("tonight's sunset is evaluated: this afternoon is the last look before it")
        void todaySunset_included() {
            EligibilityDecision d = policy.resolve(
                    TODAY, TargetType.SUNSET, ForecastStability.SETTLED, NEAR, FAR);

            assertThat(d.eligible()).isTrue();
            assertThat(d.model()).isEqualTo(NEAR);
            assertThat(d.skipDisposition()).isNull();
        }

        @Test
        @DisplayName("tomorrow's sunrise is evaluated: its only later run lands while the reader "
                + "is asleep")
        void tomorrowSunrise_included() {
            EligibilityDecision d = policy.resolve(
                    TOMORROW, TargetType.SUNRISE, ForecastStability.SETTLED, NEAR, FAR);

            assertThat(d.eligible()).isTrue();
            assertThat(d.model()).isEqualTo(NEAR);
            assertThat(d.skipDisposition()).isNull();
        }

        @Test
        @DisplayName("tomorrow's sunset is skipped as SKIPPED_NO_REFRESH_NEEDED — two further "
                + "looks are guaranteed before it")
        void tomorrowSunset_skipped() {
            EligibilityDecision d = policy.resolve(
                    TOMORROW, TargetType.SUNSET, ForecastStability.SETTLED, NEAR, FAR);

            assertThat(d.eligible()).isFalse();
            assertThat(d.skipDisposition())
                    .isEqualTo(DispositionCategory.SKIPPED_NO_REFRESH_NEEDED);
            assertThat(d.skipReason()).contains("settled");
            assertThat(d.model()).isNull();
        }

        @Test
        @DisplayName("the event decides, not the horizon: same day, opposite outcomes")
        void sameHorizonDifferentEvent_divergent() {
            // The whole reason EligibilityPolicy.resolve takes a TargetType. Before this rule the
            // policy could not tell these two apart, and a caller passing a constant event would
            // have gone unnoticed.
            EligibilityDecision sunrise = policy.resolve(
                    TOMORROW, TargetType.SUNRISE, ForecastStability.SETTLED, NEAR, FAR);
            EligibilityDecision sunset = policy.resolve(
                    TOMORROW, TargetType.SUNSET, ForecastStability.SETTLED, NEAR, FAR);

            assertThat(sunrise.eligible()).isTrue();
            assertThat(sunset.eligible()).isFalse();
        }

        @Test
        @DisplayName("an unexpected event type is evaluated rather than silently dropped")
        void unreachableEventType_included() {
            // HOURLY cannot reach the policy — BriefingHierarchyBuilder emits only SUNRISE/SUNSET
            // and the candidate strategy rejects anything else. Pinned anyway: if that ever
            // changes, the safe failure is a wasted call, not a forecast that quietly vanishes.
            EligibilityDecision d = policy.resolve(
                    TOMORROW, TargetType.HOURLY, ForecastStability.SETTLED, NEAR, FAR);

            assertThat(d.eligible()).isTrue();
        }
    }

    @Nested
    @DisplayName("TRANSITIONAL and UNSETTLED — always evaluated, unchanged by this rule")
    class Volatile2 {

        @ParameterizedTest
        @EnumSource(value = TargetType.class, names = {"SUNRISE", "SUNSET"})
        @DisplayName("TRANSITIONAL is evaluated at every slot in the window")
        void transitional_alwaysIncluded(TargetType targetType) {
            assertThat(policy.resolve(TODAY, targetType,
                    ForecastStability.TRANSITIONAL, NEAR, FAR).model()).isEqualTo(NEAR);
            assertThat(policy.resolve(TOMORROW, targetType,
                    ForecastStability.TRANSITIONAL, NEAR, FAR).model()).isEqualTo(NEAR);
        }

        @ParameterizedTest
        @EnumSource(value = TargetType.class, names = {"SUNRISE", "SUNSET"})
        @DisplayName("UNSETTLED is evaluated at every slot in the window")
        void unsettled_alwaysIncluded(TargetType targetType) {
            assertThat(policy.resolve(TODAY, targetType,
                    ForecastStability.UNSETTLED, NEAR, FAR).model()).isEqualTo(NEAR);
            assertThat(policy.resolve(TOMORROW, targetType,
                    ForecastStability.UNSETTLED, NEAR, FAR).model()).isEqualTo(NEAR);
        }

        @Test
        @DisplayName("the later-look argument does NOT withdraw the T+1 sunset include for "
                + "volatile cells")
        void tomorrowSunset_stillIncludedWhenNotSettled() {
            // The skip is one-directional and about SETTLED only. "Adding a call for the calmest
            // cells buys nothing" is not the same claim as "withdraw one from the most volatile",
            // and this change must remove no evaluation that happens today.
            assertThat(policy.resolve(TOMORROW, TargetType.SUNSET,
                    ForecastStability.TRANSITIONAL, NEAR, FAR).eligible()).isTrue();
            assertThat(policy.resolve(TOMORROW, TargetType.SUNSET,
                    ForecastStability.UNSETTLED, NEAR, FAR).eligible()).isTrue();
        }
    }
}
