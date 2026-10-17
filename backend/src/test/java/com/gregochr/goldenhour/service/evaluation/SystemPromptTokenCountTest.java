package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCountTokensParams;
import com.anthropic.models.messages.OutputConfig;
import com.gregochr.goldenhour.service.WoodlandVerdictEvaluator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures each batch system prompt with {@code messages.count_tokens} against Haiku 4.5's
 * 4,096-token minimum cacheable prefix, replacing the character-ratio estimates the offline guard
 * ({@link SystemPromptCacheabilityTest}) is built on. Token counting is free.
 *
 * <p>Tagged "prompt-regression" so it never runs in the default build or CI. Run on demand with:
 * <pre>
 *   cd backend && ANTHROPIC_API_KEY=sk-ant-... ./mvnw test -Pprompt-regression \
 *       -Dtest=SystemPromptTokenCountTest
 * </pre>
 */
@Tag("prompt-regression")
class SystemPromptTokenCountTest {

    private static final String HAIKU_MODEL = "claude-haiku-4-5-20251001";
    private static final long HAIKU_CACHE_FLOOR_TOKENS = 4_096;

    private static AnthropicClient client;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getProperty("ANTHROPIC_API_KEY",
                System.getenv("ANTHROPIC_API_KEY"));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY must be set as a system property or environment variable");
        }
        client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    private static long count(String system, OutputConfig outputConfig) {
        MessageCountTokensParams.Builder params = MessageCountTokensParams.builder()
                .model(HAIKU_MODEL)
                .system(system)
                .addUserMessage("go");
        if (outputConfig != null) {
            params.outputConfig(outputConfig);
        }
        return client.messages().countTokens(params.build()).inputTokens();
    }

    private static long report(String name, String system, OutputConfig outputConfig) {
        long plain = count(system, null);
        long withConfig = -1;
        try {
            withConfig = count(system, outputConfig);
        } catch (RuntimeException e) {
            System.out.println("TOKENCOUNT " + name + " with output_config rejected: " + e.getMessage());
        }
        System.out.println("TOKENCOUNT " + name + ": chars=" + system.length()
                + " tokens=" + plain + " tokensWithOutputConfig=" + withConfig
                + " charsPerToken=" + String.format("%.3f", (double) system.length() / plain));
        return plain;
    }

    @Test
    void inlandSkyPrompt_clearsHaikuCacheFloor() {
        PromptBuilder builder = new PromptBuilder();
        long tokens = report("inland", builder.getSystemPrompt(), builder.buildOutputConfig());
        assertThat(tokens)
                .as("Inland sky system prompt must be >= %d tokens (Haiku 4.5 cache floor); margin is "
                        + "tokens - %d", HAIKU_CACHE_FLOOR_TOKENS, HAIKU_CACHE_FLOOR_TOKENS)
                .isGreaterThanOrEqualTo(HAIKU_CACHE_FLOOR_TOKENS);
    }

    @Test
    void coastalSkyPrompt_clearsHaikuCacheFloor() {
        CoastalPromptBuilder builder = new CoastalPromptBuilder();
        long tokens = report("coastal", builder.getSystemPrompt(), builder.buildOutputConfig());
        assertThat(tokens)
                .as("Coastal sky system prompt must be >= %d tokens (Haiku 4.5 cache floor); margin is "
                        + "tokens - %d", HAIKU_CACHE_FLOOR_TOKENS, HAIKU_CACHE_FLOOR_TOKENS)
                .isGreaterThanOrEqualTo(HAIKU_CACHE_FLOOR_TOKENS);
    }

    @Test
    void woodlandPrompt_isMeasured() {
        WoodlandPromptBuilder builder = new WoodlandPromptBuilder(new WoodlandVerdictEvaluator());
        long tokens = report("woodland", builder.getSystemPrompt(), builder.buildOutputConfig());
        assertThat(tokens).isPositive();
    }

    @Test
    void bluebellPrompt_isMeasured() {
        BluebellPromptBuilder builder = new BluebellPromptBuilder();
        long tokens = report("bluebell", builder.getSystemPrompt(), builder.buildOutputConfig());
        assertThat(tokens).isPositive();
    }
}
