package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BriefingEvaluationResult}'s forced-provenance and submission-instant
 * withers — {@link BriefingEvaluationResult#forced}, {@link BriefingEvaluationResult#withForced},
 * {@link BriefingEvaluationResult#withForcedFromCombination}, {@link
 * BriefingEvaluationResult#submittedAt}, {@link BriefingEvaluationResult#withSubmittedAtFromCombination}
 * — and how every OTHER rebuild-style wither on the record interacts with the mark (round 10, P1-A
 * audit; extended round 12). {@code BriefingEvaluationServiceTest}'s {@code recombineBluebell}
 * tests cover the one true COMBINATION site (two evaluated sources folded into one) end to end,
 * including the round-12 staleness/same-cycle gates; {@code EvaluationViewServiceTest} covers the
 * retraction marker and the triage/forecast-row "not a rating" sites. This class pins the
 * record-level primitives those two rely on, directly.
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

    // ── withForcedFromCombination — the one shared combination rule ──
    // (round 10, P1-A; corrected to a genuine two-argument OR in round 12 — see the method's own
    // javadoc for why the original single-argument "read one side alone" shape was wrong in
    // general even though it agreed with this OR in every case production could actually produce)

    @Test
    @DisplayName("withForcedFromCombination(a, b): forced when ONLY a is forced")
    void withForcedFromCombination_forcedWhenOnlyAIsForced() {
        BriefingEvaluationResult combined = rated(false);
        assertThat(combined.withForcedFromCombination(rated(true), rated(false)).forced())
                .isTrue();
    }

    @Test
    @DisplayName("withForcedFromCombination(a, b): forced when ONLY b is forced — this is the "
            + "exact case round 12 found the old single-argument shape got wrong")
    void withForcedFromCombination_forcedWhenOnlyBIsForced() {
        BriefingEvaluationResult combined = rated(false);
        assertThat(combined.withForcedFromCombination(rated(false), rated(true)).forced())
                .isTrue();
    }

    @Test
    @DisplayName("withForcedFromCombination(a, b): forced when BOTH are forced")
    void withForcedFromCombination_forcedWhenBothForced() {
        BriefingEvaluationResult combined = rated(true);
        assertThat(combined.withForcedFromCombination(rated(true), rated(true)).forced())
                .isTrue();
    }

    @Test
    @DisplayName("withForcedFromCombination(a, b): not forced when NEITHER is forced, even if "
            + "this result's own prior mark was true")
    void withForcedFromCombination_notForcedWhenNeitherForced() {
        BriefingEvaluationResult combined = rated(true);
        assertThat(combined.withForcedFromCombination(rated(false), rated(false)).forced())
                .isFalse();
    }

    @Test
    @DisplayName("withForcedFromCombination treats a null side as not forced, on either argument")
    void withForcedFromCombination_nullSideTreatedAsNotForced() {
        assertThat(rated(true).withForcedFromCombination(null, rated(false)).forced()).isFalse();
        assertThat(rated(true).withForcedFromCombination(rated(false), null).forced()).isFalse();
        assertThat(rated(true).withForcedFromCombination(null, rated(true)).forced()).isTrue();
        assertThat(rated(true).withForcedFromCombination(rated(true), null).forced()).isTrue();
    }

    @Test
    @DisplayName("withForcedFromCombination is a no-op on an unrated combined result, matching "
            + "withForced's own guard")
    void withForcedFromCombination_noOpWhenCombinedIsUnrated() {
        BriefingEvaluationResult unratedCombined = new BriefingEvaluationResult(
                "X", null, null, null, null, TriageReason.HIGH_CLOUD, "cloudy");
        assertThat(unratedCombined.withForcedFromCombination(rated(true), rated(true)).forced())
                .isFalse();
    }

    // ── withSubmittedAtFromCombination — mirrors withForcedFromCombination's shape ──

    private static BriefingEvaluationResult withSubmittedAt(String suffix, java.time.Instant at) {
        return new BriefingEvaluationResult("X", 4, 70, 65, "sky-" + suffix).withSubmittedAt(at);
    }

    @Test
    @DisplayName("withSubmittedAtFromCombination(a, b) prefers b's instant when both are known")
    void withSubmittedAtFromCombination_prefersB() {
        java.time.Instant aAt = java.time.Instant.parse("2026-03-30T01:05:00Z");
        java.time.Instant bAt = java.time.Instant.parse("2026-03-30T14:04:00Z");
        BriefingEvaluationResult combined = rated(false);
        assertThat(combined.withSubmittedAtFromCombination(
                withSubmittedAt("a", aAt), withSubmittedAt("b", bAt)).submittedAt())
                .isEqualTo(bAt);
    }

    @Test
    @DisplayName("withSubmittedAtFromCombination(a, b) falls back to a's instant when b's is "
            + "unknown, rather than reverting to null")
    void withSubmittedAtFromCombination_fallsBackToAWhenBUnknown() {
        java.time.Instant aAt = java.time.Instant.parse("2026-03-30T01:05:00Z");
        BriefingEvaluationResult combined = rated(false);
        assertThat(combined.withSubmittedAtFromCombination(
                withSubmittedAt("a", aAt), rated(true)).submittedAt())
                .isEqualTo(aAt);
    }

    @Test
    @DisplayName("withSubmittedAtFromCombination(a, b) is null when both are unknown")
    void withSubmittedAtFromCombination_nullWhenBothUnknown() {
        BriefingEvaluationResult combined = rated(false);
        assertThat(combined.withSubmittedAtFromCombination(rated(true), rated(false))
                .submittedAt()).isNull();
    }
}
