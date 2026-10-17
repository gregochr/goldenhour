package com.gregochr.goldenhour.model;

import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.Usage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TokenUsage}, including extraction from an SDK {@link Usage}.
 */
class TokenUsageTest {

    private static Usage.Builder baseUsage() {
        return Usage.builder()
                .inputTokens(10)
                .outputTokens(20)
                .cacheCreationInputTokens(300)
                .cacheReadInputTokens(40)
                .inferenceGeo("not_available")
                .outputTokensDetails(OutputTokensDetails.builder().thinkingTokens(0).build())
                .serverToolUse(ServerToolUsage.builder()
                        .webFetchRequests(0).webSearchRequests(0).build())
                .serviceTier(Usage.ServiceTier.STANDARD);
    }

    @Test
    @DisplayName("totalTokens() sums all four token categories")
    void totalTokens_sumsAllCategories() {
        assertThat(new TokenUsage(100, 50, 200, 30).totalTokens()).isEqualTo(380);
    }

    @Test
    @DisplayName("EMPTY constant has all zeros")
    void empty_hasAllZeros() {
        assertThat(TokenUsage.EMPTY.inputTokens()).isZero();
        assertThat(TokenUsage.EMPTY.outputTokens()).isZero();
        assertThat(TokenUsage.EMPTY.cacheCreationInputTokens()).isZero();
        assertThat(TokenUsage.EMPTY.cacheReadInputTokens()).isZero();
        assertThat(TokenUsage.EMPTY.cacheCreationOneHourTokens()).isZero();
        assertThat(TokenUsage.EMPTY.totalTokens()).isZero();
    }

    @Test
    @DisplayName("record equality works correctly")
    void recordEquality_worksCorrectly() {
        assertThat(new TokenUsage(100, 50, 0, 0)).isEqualTo(new TokenUsage(100, 50, 0, 0));
    }

    @Test
    @DisplayName("from() reads the 1-hour portion off the cache_creation breakdown")
    void from_withBreakdown() {
        Usage usage = baseUsage()
                .cacheCreation(CacheCreation.builder()
                        .ephemeral1hInputTokens(120)
                        .ephemeral5mInputTokens(180)
                        .build())
                .build();

        assertThat(TokenUsage.from(usage)).isEqualTo(new TokenUsage(10, 20, 300, 40, 120));
    }

    @Test
    @DisplayName("from() yields a zero 1-hour portion when the API gave no breakdown")
    void from_withoutBreakdown() {
        Usage usage = baseUsage().cacheCreation(Optional.empty()).build();

        TokenUsage tokens = TokenUsage.from(usage);

        assertThat(tokens.cacheCreationOneHourTokens()).isZero();
        assertThat(tokens).isEqualTo(new TokenUsage(10, 20, 300, 40));
    }

    @Test
    @DisplayName("from() clamps a breakdown that claims more 1-hour tokens than the total")
    void from_clampsToTotal() {
        Usage usage = baseUsage()
                .cacheCreation(CacheCreation.builder()
                        .ephemeral1hInputTokens(900)
                        .ephemeral5mInputTokens(0)
                        .build())
                .build();

        assertThat(TokenUsage.from(usage).cacheCreationOneHourTokens()).isEqualTo(300);
    }

    @Test
    @DisplayName("the 1-hour portion is a subset: totalTokens() does not count it again")
    void totalTokens_excludesOneHourSubset() {
        assertThat(new TokenUsage(1, 2, 300, 4, 120).totalTokens()).isEqualTo(307);
    }
}
