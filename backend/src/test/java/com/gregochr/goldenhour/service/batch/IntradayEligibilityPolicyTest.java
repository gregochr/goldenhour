package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IntradayEligibilityPolicy} — the skip-settled cost-gate.
 *
 * <p>Boundary is the stability class: SETTLED is skipped (and recorded as
 * {@code SKIPPED_NO_REFRESH_NEEDED}, not {@code SKIPPED_STABILITY}); both
 * TRANSITIONAL and UNSETTLED are evaluated on the near-term model.
 */
class IntradayEligibilityPolicyTest {

    private static final EvaluationModel NEAR = EvaluationModel.HAIKU;
    private static final EvaluationModel FAR = EvaluationModel.SONNET;

    private final IntradayEligibilityPolicy policy = IntradayEligibilityPolicy.INSTANCE;

    @Test
    @DisplayName("SETTLED is skipped as SKIPPED_NO_REFRESH_NEEDED (not SKIPPED_STABILITY)")
    void settled_skippedNoRefreshNeeded() {
        EligibilityDecision d = policy.resolve(0, ForecastStability.SETTLED, NEAR, FAR);

        assertThat(d.eligible()).isFalse();
        assertThat(d.skipDisposition())
                .isEqualTo(DispositionCategory.SKIPPED_NO_REFRESH_NEEDED);
        assertThat(d.skipReason()).contains("settled");
        assertThat(d.model()).isNull();
    }

    @Test
    @DisplayName("TRANSITIONAL is evaluated on the near-term model")
    void transitional_includedNearTerm() {
        EligibilityDecision d = policy.resolve(1, ForecastStability.TRANSITIONAL, NEAR, FAR);

        assertThat(d.eligible()).isTrue();
        assertThat(d.model()).isEqualTo(NEAR);
        assertThat(d.skipDisposition()).isNull();
    }

    @Test
    @DisplayName("UNSETTLED is evaluated on the near-term model")
    void unsettled_includedNearTerm() {
        EligibilityDecision d = policy.resolve(1, ForecastStability.UNSETTLED, NEAR, FAR);

        assertThat(d.eligible()).isTrue();
        assertThat(d.model()).isEqualTo(NEAR);
    }

    @Test
    @DisplayName("daysAhead does not change the decision — the window is already constrained")
    void daysAhead_isIgnored() {
        // Same stability, different horizons → same outcome. The candidate strategy
        // owns the window; the policy is purely the stability cost-gate.
        EligibilityDecision t0 = policy.resolve(0, ForecastStability.SETTLED, NEAR, FAR);
        EligibilityDecision t1 = policy.resolve(1, ForecastStability.SETTLED, NEAR, FAR);

        assertThat(t0.skipDisposition()).isEqualTo(t1.skipDisposition());
        assertThat(t0.eligible()).isEqualTo(t1.eligible());
    }
}
