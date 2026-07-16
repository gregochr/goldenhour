package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
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

    @Test
    @DisplayName("SETTLED permits T+0 through T+3 and stops at T+4 (beyond horizon)")
    void settled_boundary() {
        assertThat(policy.permitsHorizon(0, ForecastStability.SETTLED)).isTrue();
        assertThat(policy.permitsHorizon(3, ForecastStability.SETTLED)).isTrue();
        assertThat(policy.permitsHorizon(4, ForecastStability.SETTLED)).isFalse();
    }

    @Test
    @DisplayName("TRANSITIONAL permits T+0 through T+2 and stops at T+3")
    void transitional_boundary() {
        assertThat(policy.permitsHorizon(2, ForecastStability.TRANSITIONAL)).isTrue();
        assertThat(policy.permitsHorizon(3, ForecastStability.TRANSITIONAL)).isFalse();
    }

    @Test
    @DisplayName("UNSETTLED permits T+0 and T+1 only")
    void unsettled_boundary() {
        assertThat(policy.permitsHorizon(1, ForecastStability.UNSETTLED)).isTrue();
        assertThat(policy.permitsHorizon(2, ForecastStability.UNSETTLED)).isFalse();
    }

    @Test
    @DisplayName("permitsHorizon agrees with resolve() across the whole table")
    void permitsHorizon_mirrorsResolve() {
        for (ForecastStability stability : ForecastStability.values()) {
            for (int daysAhead = 0; daysAhead <= 5; daysAhead++) {
                boolean viaResolve = policy.resolve(daysAhead, stability,
                        EvaluationModel.HAIKU, EvaluationModel.SONNET).eligible();
                assertThat(policy.permitsHorizon(daysAhead, stability))
                        .as("T+%d %s", daysAhead, stability)
                        .isEqualTo(viaResolve);
            }
        }
    }
}
