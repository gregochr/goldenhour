package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideRiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CoastalPromptBuilder}.
 *
 * <p><b>v2.13.2 decomposition.</b> Tide is no longer a prompt rating lever — Claude scores the
 * sky alone and the {@code TideVisitor} re-adds the tide contribution at the combine seam. The
 * coastal builder therefore no longer emits a {@code Tide:} data line or tide-boost system
 * guidance; it adds only the storm-surge block (a foreground/safety concern). These tests pin
 * that: the system prompt carries surge guidance but no tide guidance, and the user message
 * carries the surge block (when significant) but never a {@code Tide:} line.
 */
class CoastalPromptBuilderTest {

    private final CoastalPromptBuilder builder = new CoastalPromptBuilder();

    private static StormSurgeBreakdown significantSurge() {
        return new StormSurgeBreakdown(
                0.23, 0.12, 0.35, 990.0, 15.0, 60.0, 0.85,
                TideRiskLevel.MODERATE, "Test surge");
    }

    // ── System Prompt ───────────────────────────────────────────────────────

    @Test
    @DisplayName("getSystemPrompt includes base prompt content")
    void getSystemPrompt_includesBaseContent() {
        String prompt = builder.getSystemPrompt();

        assertThat(prompt)
                .contains("1–5")
                .contains("fiery_sky")
                .contains("golden_hour");
    }

    @Test
    @DisplayName("getSystemPrompt includes coastal surge guidance but NOT tide-boost guidance")
    void getSystemPrompt_includesSurgeGuidanceNotTideGuidance() {
        String prompt = builder.getSystemPrompt();

        assertThat(prompt)
                .contains("COASTAL CONDITIONS GUIDANCE:")
                .contains("STORM SURGE FORECAST")
                .doesNotContain("COASTAL TIDE GUIDANCE")
                .doesNotContain("Tide boost")
                .doesNotContain("aligned king tide");
    }

    @Test
    @DisplayName("getSystemPrompt starts with the base prompt (no duplication)")
    void getSystemPrompt_startsWithBasePrompt() {
        PromptBuilder base = new PromptBuilder();
        assertThat(builder.getSystemPrompt()).startsWith(base.getSystemPrompt());
    }

    @Test
    @DisplayName("coastal system prompt is strictly longer than base system prompt")
    void getSystemPrompt_coastalIsLongerThanBase() {
        PromptBuilder base = new PromptBuilder();
        assertThat(builder.getSystemPrompt().length())
                .isGreaterThan(base.getSystemPrompt().length());
    }

    @Test
    @DisplayName("coastal system prompt suffix appears exactly once")
    void getSystemPrompt_coastalSuffixAppearsExactlyOnce() {
        int count = countOccurrences(builder.getSystemPrompt(), "COASTAL CONDITIONS GUIDANCE:");
        assertThat(count).as("coastal guidance should appear exactly once").isEqualTo(1);
    }

    // ── User message: no tide line ───────────────────────────────────────────

    @Test
    @DisplayName("buildUserMessage never emits a Tide: line (tide is scored separately)")
    void buildUserMessage_neverEmitsTideLine() {
        AtmosphericData data = TestAtmosphericData.defaults();

        assertThat(builder.buildUserMessage(data)).doesNotContain("Tide:");
    }

    @Test
    @DisplayName("buildUserMessage (sky only) ends with the prompt suffix exactly once")
    void buildUserMessage_skyOnly_endsWithSuffixOnce() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = builder.buildUserMessage(data);

        assertThat(message).endsWith(PromptBuilder.PROMPT_SUFFIX);
        assertThat(countOccurrences(message, PromptBuilder.PROMPT_SUFFIX)).isEqualTo(1);
    }

    // ── Surge via CoastalPromptBuilder ──────────────────────────────────────

    @Test
    @DisplayName("buildUserMessage with significant surge includes the surge block, not a tide line")
    void buildUserMessage_significantSurge_includesSurgeBlockNoTide() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = builder.buildUserMessage(data, significantSurge(), 4.15, 3.80);

        assertThat(message)
                .contains("STORM SURGE FORECAST:")
                .contains("Risk level: MODERATE")
                .doesNotContain("Tide:");
    }

    @Test
    @DisplayName("buildUserMessage with surge ends with the prompt suffix exactly once")
    void buildUserMessage_surge_endsWithSuffixOnce() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = builder.buildUserMessage(data, significantSurge(), 5.4, 5.0);

        assertThat(message).endsWith(builder.getPromptSuffix());
        assertThat(countOccurrences(message, PromptBuilder.PROMPT_SUFFIX)).isEqualTo(1);
    }

    @Test
    @DisplayName("buildUserMessage surge block appears after the sky content")
    void buildUserMessage_surgeAfterSkyContent() {
        AtmosphericData data = TestAtmosphericData.builder()
                .directionalCloud(new DirectionalCloudData(15, 40, 60, 30, 55, 70, 10))
                .build();

        String message = builder.buildUserMessage(data, significantSurge(), 5.4, 5.0);

        int directionalIdx = message.indexOf("DIRECTIONAL CLOUD");
        int surgeIdx = message.indexOf("STORM SURGE FORECAST:");
        int suffixIdx = message.indexOf(PromptBuilder.PROMPT_SUFFIX);
        assertThat(directionalIdx).as("sky content present").isGreaterThan(0);
        assertThat(surgeIdx).as("surge after sky content").isGreaterThan(directionalIdx);
        assertThat(suffixIdx).as("surge before suffix").isGreaterThan(surgeIdx);
    }

    @Test
    @DisplayName("buildUserMessage with insignificant surge omits the surge block entirely")
    void buildUserMessage_insignificantSurge_omitsSurgeBlock() {
        AtmosphericData data = TestAtmosphericData.defaults();
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.01, 0.01, 0.02, 1015.0, 3.0, 180.0, 0.0,
                TideRiskLevel.NONE, "No significant surge");

        String message = builder.buildUserMessage(data, surge, 4.02, 4.0);

        assertThat(message)
                .doesNotContain("STORM SURGE FORECAST:")
                .doesNotContain("Tide:");
    }

    // --- Helper ---

    private static int countOccurrences(String text, String search) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(search, idx)) != -1) {
            count++;
            idx += search.length();
        }
        return count;
    }
}
