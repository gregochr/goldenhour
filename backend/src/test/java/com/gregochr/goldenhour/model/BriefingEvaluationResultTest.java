package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BriefingEvaluationResult}'s forced-provenance withers — {@link
 * BriefingEvaluationResult#forced}, {@link BriefingEvaluationResult#withForced},
 * {@link BriefingEvaluationResult#withForcedFromCombination} — and how every OTHER rebuild-style
 * wither on the record interacts with the mark (round 10, P1-A audit). {@code
 * BriefingEvaluationServiceTest}'s {@code recombineBluebell} tests cover the one true
 * COMBINATION site (two evaluated sources folded into one); {@code EvaluationViewServiceTest}
 * covers the retraction marker and the triage/forecast-row "not a rating" sites end to end. This
 * class pins the record-level primitives those two rely on, directly.
 */
class BriefingEvaluationResultTest {

    private static BriefingEvaluationResult rated(boolean forced) {
        return new BriefingEvaluationResult("X", 4, 70, 65, "sky").withForced(forced);
    }

    // ── withRating — a rebuild site that clears the mark, deliberately ──

    @Test
    @DisplayName("withRating(null) clears forced — an unrated result cannot carry a forced rating")
    void withRating_null_clearsForced() {
        assertThat(rated(true).withRating(null).forced()).isFalse();
    }

    @Test
    @DisplayName("withRating(newRating) preserves forced when a rating remains")
    void withRating_nonNull_preservesForced() {
        BriefingEvaluationResult reRated = rated(true).withRating(5);
        assertThat(reRated.rating()).isEqualTo(5);
        assertThat(reRated.forced()).isTrue();
    }

    // ── withEvaluatedAt — a rebuild site that must never touch the mark ──

    @Test
    @DisplayName("withEvaluatedAt preserves forced=true")
    void withEvaluatedAt_preservesForcedTrue() {
        assertThat(rated(true).withEvaluatedAt(java.time.Instant.now()).forced()).isTrue();
    }

    @Test
    @DisplayName("withEvaluatedAt preserves forced=false")
    void withEvaluatedAt_preservesForcedFalse() {
        assertThat(rated(false).withEvaluatedAt(java.time.Instant.now()).forced()).isFalse();
    }

    // ── withForced — the no-op-on-unrated guard ──

    @Test
    @DisplayName("withForced(true) is a no-op on an unrated (triaged) result")
    void withForced_noOpWhenUnrated() {
        BriefingEvaluationResult triaged = new BriefingEvaluationResult(
                "X", null, null, null, null, TriageReason.HIGH_CLOUD, "cloudy");
        assertThat(triaged.withForced(true).forced()).isFalse();
    }

    // ── retracted() — never a rating, never the mark ──

    @Test
    @DisplayName("retracted() never carries the forced mark")
    void retracted_neverForced() {
        assertThat(BriefingEvaluationResult.retracted("X").forced()).isFalse();
    }

    @Test
    @DisplayName("retracted() carries no rating either — forced cannot survive a later withForced")
    void retracted_hasNoRatingSoWithForcedIsANoOp() {
        BriefingEvaluationResult retracted = BriefingEvaluationResult.retracted("X");
        assertThat(retracted.withForced(true).forced()).isFalse();
    }

    // ── withForcedFromCombination — the one shared combination rule (round 10, P1-A) ──

    @Test
    @DisplayName("withForcedFromCombination(forced) stamps forced regardless of this result's own "
            + "prior mark")
    void withForcedFromCombination_stampsFromTheNewlyArrivedSide() {
        BriefingEvaluationResult combined = rated(false);
        assertThat(combined.withForcedFromCombination(rated(true)).forced()).isTrue();
    }

    @Test
    @DisplayName("withForcedFromCombination(not forced) clears forced even if this result's own "
            + "prior mark was true")
    void withForcedFromCombination_clearsWhenNewlyArrivedIsOrdinary() {
        BriefingEvaluationResult combined = rated(true);
        assertThat(combined.withForcedFromCombination(rated(false)).forced()).isFalse();
    }

    @Test
    @DisplayName("withForcedFromCombination(null) treats a missing newly-arrived side as not forced")
    void withForcedFromCombination_nullTreatedAsNotForced() {
        assertThat(rated(true).withForcedFromCombination(null).forced()).isFalse();
    }

    @Test
    @DisplayName("withForcedFromCombination is a no-op on an unrated combined result, matching "
            + "withForced's own guard")
    void withForcedFromCombination_noOpWhenCombinedIsUnrated() {
        BriefingEvaluationResult unratedCombined = new BriefingEvaluationResult(
                "X", null, null, null, null, TriageReason.HIGH_CLOUD, "cloudy");
        assertThat(unratedCombined.withForcedFromCombination(rated(true)).forced()).isFalse();
    }
}
