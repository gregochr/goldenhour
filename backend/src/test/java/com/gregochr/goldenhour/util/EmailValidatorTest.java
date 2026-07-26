package com.gregochr.goldenhour.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Unit tests for {@link EmailValidator}.
 *
 * <p>Two properties are protected here: the shape rules the registration endpoint relies on,
 * and the linear-time matching that keeps that public endpoint from being a denial-of-service
 * target.
 */
class EmailValidatorTest {

    /** Mirrors {@code EmailValidator.MAX_EMAIL_LENGTH} (the RFC 5321 forward-path limit). */
    private static final int MAX_EMAIL_LENGTH = 254;

    /** Domain used to build length-boundary addresses; six characters including the {@code @}. */
    private static final String BOUNDARY_DOMAIN = "@e.com";

    /** Enough repetitions that a quadratic pattern would blow the timeout; a linear one will not. */
    private static final int REDOS_ITERATIONS = 20_000;

    /**
     * Ceiling on the whole loop. The linear pattern finishes it in well under a second, but the
     * suite forks one JVM per core, so wall-clock here is measured on a fully contended machine —
     * the margin is for that contention, not for the pattern. Catastrophic backtracking does not
     * come in slightly late; it does not come back.
     */
    private static final Duration REDOS_TIMEOUT = Duration.ofSeconds(30);

    @Test
    @DisplayName("Ordinary addresses are accepted, including subdomains and a plus-tag local part")
    void ordinaryAddresses_areAccepted() {
        assertThat(EmailValidator.isValid("chris@thegregorysonline.com")).isTrue();
        assertThat(EmailValidator.isValid("chris@mail.photocast.online")).isTrue();
        assertThat(EmailValidator.isValid("chris+aurora@photocast.co.uk")).isTrue();
    }

    @Test
    @DisplayName("Null, empty, blank and whitespace-only values are rejected")
    void absentValues_areRejected() {
        assertThat(EmailValidator.isValid(null)).isFalse();
        assertThat(EmailValidator.isValid("")).isFalse();
        assertThat(EmailValidator.isValid("   ")).isFalse();
        assertThat(EmailValidator.isValid("\t\n")).isFalse();
    }

    @Test
    @DisplayName("Surrounding whitespace is tolerated, so a pasted address still registers")
    void surroundingWhitespace_isTrimmed() {
        assertThat(EmailValidator.isValid("  chris@photocast.online  ")).isTrue();
        assertThat(EmailValidator.isValid("\tchris@photocast.online\n")).isTrue();
    }

    @Test
    @DisplayName("A missing @, a missing domain dot and an interior space are each rejected")
    void malformedAddresses_areRejected() {
        assertThat(EmailValidator.isValid("chris.photocast.online")).isFalse();
        assertThat(EmailValidator.isValid("chris@localhost")).isFalse();
        assertThat(EmailValidator.isValid("chris smith@photocast.online")).isFalse();
        assertThat(EmailValidator.isValid("chris@photo cast.online")).isFalse();
        assertThat(EmailValidator.isValid("@photocast.online")).isFalse();
        assertThat(EmailValidator.isValid("chris@")).isFalse();
        assertThat(EmailValidator.isValid("chris@@photocast.online")).isFalse();
    }

    @Test
    @DisplayName("An address at the length cap is accepted")
    void addressAtLengthCap_isAccepted() {
        String atCap = "a".repeat(MAX_EMAIL_LENGTH - BOUNDARY_DOMAIN.length()) + BOUNDARY_DOMAIN;

        assertThat(atCap).hasSize(MAX_EMAIL_LENGTH);
        assertThat(EmailValidator.isValid(atCap)).isTrue();
    }

    @Test
    @DisplayName("An address one character over the cap is rejected even though it is well-formed")
    void addressOverLengthCap_isRejected() {
        String overCap = "a".repeat(MAX_EMAIL_LENGTH - BOUNDARY_DOMAIN.length() + 1) + BOUNDARY_DOMAIN;

        assertThat(overCap).hasSize(MAX_EMAIL_LENGTH + 1);
        assertThat(EmailValidator.isValid(overCap)).isFalse();
    }

    @Test
    @DisplayName("Empty domain labels are rejected: a doubled dot and a trailing dot")
    void emptyDomainLabels_areRejected() {
        assertThat(EmailValidator.isValid("a@b..com")).isFalse();
        assertThat(EmailValidator.isValid("a@b.com.")).isFalse();
        assertThat(EmailValidator.isValid("a@.b.com")).isFalse();
    }

    @Test
    @DisplayName("A long crafted non-matching input returns promptly, pinning linear-time matching")
    void craftedNonMatchingInput_returnsPromptly() {
        // No dot in the domain, so the match fails only after the whole input has been considered —
        // the case an ambiguous pattern would backtrack over. Sized to the largest input accepted.
        String crafted = "a".repeat(120) + "@" + "b".repeat(MAX_EMAIL_LENGTH - 121);

        assertThat(crafted).hasSize(MAX_EMAIL_LENGTH);
        assertTimeoutPreemptively(REDOS_TIMEOUT, () -> {
            // Accumulate rather than assert per iteration: an assertion object per pass would put
            // the assertion library, not the matcher, in the timed loop.
            boolean anyAccepted = false;
            for (int i = 0; i < REDOS_ITERATIONS; i++) {
                anyAccepted |= EmailValidator.isValid(crafted);
            }
            assertThat(anyAccepted).isFalse();
        });
    }
}
