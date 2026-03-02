package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TokenUsage}.
 */
class TokenUsageTest {

    @Test
    @DisplayName("totalTokens() sums all four token categories")
    void totalTokens_sumsAllCategories() {
        TokenUsage usage = new TokenUsage(100, 50, 200, 30);

        assertThat(usage.totalTokens()).isEqualTo(380);
    }

    @Test
    @DisplayName("EMPTY constant has all zeros")
    void empty_hasAllZeros() {
        assertThat(TokenUsage.EMPTY.inputTokens()).isZero();
        assertThat(TokenUsage.EMPTY.outputTokens()).isZero();
        assertThat(TokenUsage.EMPTY.cacheCreationInputTokens()).isZero();
        assertThat(TokenUsage.EMPTY.cacheReadInputTokens()).isZero();
        assertThat(TokenUsage.EMPTY.totalTokens()).isZero();
    }

    @Test
    @DisplayName("totalTokens() returns zero for EMPTY")
    void totalTokens_returnsZero_forEmpty() {
        assertThat(TokenUsage.EMPTY.totalTokens()).isZero();
    }

    @Test
    @DisplayName("record equality works correctly")
    void recordEquality_worksCorrectly() {
        TokenUsage a = new TokenUsage(100, 50, 0, 0);
        TokenUsage b = new TokenUsage(100, 50, 0, 0);

        assertThat(a).isEqualTo(b);
    }
}
