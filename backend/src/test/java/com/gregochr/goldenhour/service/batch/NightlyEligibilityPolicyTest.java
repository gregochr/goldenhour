package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NightlyEligibilityPolicy#permitsHorizon}, the boolean view of
 * the Gate 4 table consumed by the synchronous {@code ForecastCommandExecutor} path.
 *
 * <p>The {@code resolve()} table itself is exercised through
 * {@code ForecastTaskCollectorEligibilityPolicyTest}; these tests pin the boundary rows
 * of the boolean view and its agreement with {@code resolve()} so the two forecast
 * engines cannot drift.
 */
class NightlyEligibilityPolicyTest {

    private final NightlyEligibilityPolicy policy = NightlyEligibilityPolicy.INSTANCE;

    /**
     * The nightly table ignores the event, so the boundary tests below pick one arbitrarily.
     * {@code resolveIsEventBlind} is what proves that indifference rather than assuming it.
     */
    private static final TargetType ANY_EVENT = TargetType.SUNSET;

    @Test
    @DisplayName("SETTLED permits T+0 through T+3 and stops at T+4 (beyond horizon)")
    void settled_boundary() {
        assertThat(policy.permitsHorizon(0, ANY_EVENT, ForecastStability.SETTLED)).isTrue();
        assertThat(policy.permitsHorizon(3, ANY_EVENT, ForecastStability.SETTLED)).isTrue();
        assertThat(policy.permitsHorizon(4, ANY_EVENT, ForecastStability.SETTLED)).isFalse();
    }

    @Test
    @DisplayName("TRANSITIONAL permits T+0 through T+2 and stops at T+3")
    void transitional_boundary() {
        assertThat(policy.permitsHorizon(2, ANY_EVENT, ForecastStability.TRANSITIONAL)).isTrue();
        assertThat(policy.permitsHorizon(3, ANY_EVENT, ForecastStability.TRANSITIONAL)).isFalse();
    }

    @Test
    @DisplayName("UNSETTLED permits T+0 and T+1 only")
    void unsettled_boundary() {
        assertThat(policy.permitsHorizon(1, ANY_EVENT, ForecastStability.UNSETTLED)).isTrue();
        assertThat(policy.permitsHorizon(2, ANY_EVENT, ForecastStability.UNSETTLED)).isFalse();
    }

    @Test
    @DisplayName("permitsHorizon agrees with resolve() across the whole table, at every event")
    void permitsHorizon_mirrorsResolve() {
        for (TargetType targetType : TargetType.values()) {
            for (ForecastStability stability : ForecastStability.values()) {
                for (int daysAhead = 0; daysAhead <= 5; daysAhead++) {
                    boolean viaResolve = policy.resolve(daysAhead, targetType, stability,
                            EvaluationModel.HAIKU, EvaluationModel.SONNET).eligible();
                    assertThat(policy.permitsHorizon(daysAhead, targetType, stability))
                            .as("T+%d %s %s", daysAhead, targetType, stability)
                            .isEqualTo(viaResolve);
                }
            }
        }
    }

    /**
     * The no-op proof for the widened signature.
     *
     * <p>{@code targetType} was added to {@link EligibilityPolicy} for
     * {@link IntradayEligibilityPolicy}, whose rule is event-specific. Nightly must stay blind to
     * it — a sunrise and a sunset at the same horizon are the same decision. This is the change's
     * largest blast radius and its least interesting behaviour, so it is asserted rather than
     * assumed: if nightly ever grows an event-sensitive branch, it will be a deliberate act that
     * breaks a named test.
     */
    @Test
    @DisplayName("resolve() is invariant across TargetType at every (horizon, stability)")
    void resolveIsEventBlind() {
        for (ForecastStability stability : ForecastStability.values()) {
            for (int daysAhead = 0; daysAhead <= 5; daysAhead++) {
                EligibilityDecision baseline = policy.resolve(daysAhead, TargetType.SUNRISE,
                        stability, EvaluationModel.HAIKU, EvaluationModel.SONNET);
                for (TargetType targetType : TargetType.values()) {
                    EligibilityDecision actual = policy.resolve(daysAhead, targetType,
                            stability, EvaluationModel.HAIKU, EvaluationModel.SONNET);
                    // Whole-record equality, not field-by-field: EligibilityDecision is a record,
                    // so this also pins skipReason and skipDisposition. The javadoc claims the
                    // decision is invariant, and this asserts exactly that rather than a subset
                    // of it.
                    assertThat(actual)
                            .as("T+%d %s %s", daysAhead, targetType, stability)
                            .isEqualTo(baseline);
                }
            }
        }
    }
}
