package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link ForecastTaskCollector#resolveEligibility} — the
 * Gate 4 batch eligibility policy. Covers all 15 combinations of
 * {@code daysAhead ∈ {0, 1, 2, 3, 4} × stability ∈ {SETTLED, TRANSITIONAL,
 * UNSETTLED}}.
 *
 * <p>The model arguments are fixtures (HAIKU for near, SONNET for far); the
 * test asserts <em>which tier</em> the policy picks via the fixture, not the
 * production assignment of models to tiers (that is a {@code model_selection}
 * concern out of scope here).
 */
class ForecastTaskCollectorEligibilityPolicyTest {

    private static final EvaluationModel NEAR = EvaluationModel.HAIKU;
    private static final EvaluationModel FAR = EvaluationModel.SONNET;

    @ParameterizedTest(name = "T+{0} {1} → eligible={2}, model={3}, skipReason={4}")
    @CsvSource({
            // daysAhead, stability,    eligible, expectedModel, skipReason
            "0,           SETTLED,      true,     HAIKU,         ",
            "0,           TRANSITIONAL, true,     HAIKU,         ",
            "0,           UNSETTLED,    true,     HAIKU,         ",
            "1,           SETTLED,      true,     HAIKU,         ",
            "1,           TRANSITIONAL, true,     HAIKU,         ",
            "1,           UNSETTLED,    true,     HAIKU,         ",
            "2,           SETTLED,      true,     SONNET,        ",
            "2,           TRANSITIONAL, true,     SONNET,        ",
            "2,           UNSETTLED,    false,    ,              T+2 UNSETTLED",
            "3,           SETTLED,      true,     SONNET,        ",
            "3,           TRANSITIONAL, false,    ,              T+3 TRANSITIONAL",
            "3,           UNSETTLED,    false,    ,              T+3 UNSETTLED",
            "4,           SETTLED,      false,    ,              T+4 beyond horizon",
            "4,           TRANSITIONAL, false,    ,              T+4 beyond horizon",
            "4,           UNSETTLED,    false,    ,              T+4 beyond horizon",
    })
    @DisplayName("resolveEligibility honours the Gate 4 policy table")
    void resolveEligibility_policyTable(int daysAhead, ForecastStability stability,
            boolean expectedEligible, EvaluationModel expectedModel, String expectedSkipReason) {
        EligibilityDecision decision = ForecastTaskCollector.resolveEligibility(
                daysAhead, stability, NEAR, FAR);

        assertThat(decision.eligible()).isEqualTo(expectedEligible);
        if (expectedEligible) {
            assertThat(decision.model()).isEqualTo(expectedModel);
            assertThat(decision.skipReason()).isNull();
        } else {
            assertThat(decision.model()).isNull();
            assertThat(decision.skipReason()).isEqualTo(expectedSkipReason);
        }
    }

    // ── Explicit boundary tests at each policy threshold ─────────────────────

    @org.junit.jupiter.api.Test
    @DisplayName("T+1 → T+2 boundary: TRANSITIONAL switches tier (near→far) but stays eligible")
    void boundary_t1ToT2_transitional_switchesTier() {
        EligibilityDecision t1 = ForecastTaskCollector.resolveEligibility(
                1, ForecastStability.TRANSITIONAL, NEAR, FAR);
        EligibilityDecision t2 = ForecastTaskCollector.resolveEligibility(
                2, ForecastStability.TRANSITIONAL, NEAR, FAR);

        assertThat(t1.eligible()).isTrue();
        assertThat(t1.model()).isEqualTo(NEAR);
        assertThat(t2.eligible()).isTrue();
        assertThat(t2.model()).isEqualTo(FAR);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("T+1 → T+2 boundary: UNSETTLED drops out of the batch")
    void boundary_t1ToT2_unsettled_dropsOut() {
        EligibilityDecision t1 = ForecastTaskCollector.resolveEligibility(
                1, ForecastStability.UNSETTLED, NEAR, FAR);
        EligibilityDecision t2 = ForecastTaskCollector.resolveEligibility(
                2, ForecastStability.UNSETTLED, NEAR, FAR);

        assertThat(t1.eligible()).isTrue();
        assertThat(t2.eligible()).isFalse();
        assertThat(t2.skipReason()).isEqualTo("T+2 UNSETTLED");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("T+2 → T+3 boundary: TRANSITIONAL drops out of the batch")
    void boundary_t2ToT3_transitional_dropsOut() {
        EligibilityDecision t2 = ForecastTaskCollector.resolveEligibility(
                2, ForecastStability.TRANSITIONAL, NEAR, FAR);
        EligibilityDecision t3 = ForecastTaskCollector.resolveEligibility(
                3, ForecastStability.TRANSITIONAL, NEAR, FAR);

        assertThat(t2.eligible()).isTrue();
        assertThat(t3.eligible()).isFalse();
        assertThat(t3.skipReason()).isEqualTo("T+3 TRANSITIONAL");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("T+3 → T+4 boundary: SETTLED drops out of the batch")
    void boundary_t3ToT4_settled_dropsOut() {
        EligibilityDecision t3 = ForecastTaskCollector.resolveEligibility(
                3, ForecastStability.SETTLED, NEAR, FAR);
        EligibilityDecision t4 = ForecastTaskCollector.resolveEligibility(
                4, ForecastStability.SETTLED, NEAR, FAR);

        assertThat(t3.eligible()).isTrue();
        assertThat(t3.model()).isEqualTo(FAR);
        assertThat(t4.eligible()).isFalse();
        assertThat(t4.skipReason()).isEqualTo("T+4 beyond horizon");
    }
}
