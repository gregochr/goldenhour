package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideStatisticalSize;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.MistTrend;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideRiskLevel;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.UpwindCloudSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CoastalPromptBuilder}.
 *
 * <p>Tests tide and surge formatting in the coastal prompt subclass.
 * Sky-only prompt tests remain in {@link PromptBuilderTest}.
 */
class CoastalPromptBuilderTest {

    private final CoastalPromptBuilder builder = new CoastalPromptBuilder();

    // ── System Prompt ───────────────────────────────────────────────────────

    @Test
    @DisplayName("getSystemPrompt includes base prompt content")
    void getSystemPrompt_includesBaseContent() {
        String prompt = builder.getSystemPrompt();

        assertThat(prompt)
                .contains("1\u20135")
                .contains("fiery_sky")
                .contains("golden_hour");
    }

    @Test
    @DisplayName("getSystemPrompt includes COASTAL TIDE GUIDANCE section")
    void getSystemPrompt_includesCoastalTideGuidance() {
        String prompt = builder.getSystemPrompt();

        assertThat(prompt)
                .contains("COASTAL TIDE GUIDANCE:")
                .contains("Sky score first")
                .contains("Tide boost")
                .contains("King tide / spring tide")
                .contains("Storm surge");
    }

    @Test
    @DisplayName("getSystemPrompt does not duplicate base content")
    void getSystemPrompt_startsWithBasePrompt() {
        PromptBuilder base = new PromptBuilder();
        String basePrompt = base.getSystemPrompt();
        String coastalPrompt = builder.getSystemPrompt();

        assertThat(coastalPrompt).startsWith(basePrompt);
    }

    // ── Tide Block in buildUserMessage ───────────────────────────────────────

    @Test
    @DisplayName("buildUserMessage with tide data includes tide block")
    void buildUserMessage_withTideData_includesTideBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null, null, null, null, null))
                .build();

        String message = builder.buildUserMessage(data);

        assertThat(message).contains("Tide:");
    }

    @Test
    @DisplayName("buildUserMessage without tide omits tide block")
    void buildUserMessage_withoutTide_omitsTideBlock() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = builder.buildUserMessage(data);

        assertThat(message).doesNotContain("Tide:");
    }

    @Test
    @DisplayName("buildUserMessage with king tide shows lunar + statistical labels")
    void buildUserMessage_kingTideWithExtraExtraHigh_showsBothLabels() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("6.20"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null,
                        LunarTideType.KING_TIDE, "New Moon", true,
                        TideStatisticalSize.EXTRA_EXTRA_HIGH))
                .build();

        String message = builder.buildUserMessage(data);

        assertThat(message)
                .contains("Tide: KING TIDE, Extra Extra High")
                .contains("range: 6.20m")
                .contains("moon: New Moon")
                .contains("perigee: yes")
                .contains("aligned: yes");
    }

    @Test
    @DisplayName("buildUserMessage with spring tide and extra high shows both labels")
    void buildUserMessage_springTideWithExtraHigh_showsBothLabels() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("5.10"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.30"),
                        false,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null,
                        LunarTideType.SPRING_TIDE, "Full Moon", false,
                        TideStatisticalSize.EXTRA_HIGH))
                .build();

        String message = builder.buildUserMessage(data);

        assertThat(message)
                .contains("Tide: SPRING TIDE, Extra High")
                .contains("moon: Full Moon")
                .contains("perigee: no")
                .contains("aligned: no");
    }

    @Test
    @DisplayName("buildUserMessage with regular tide and no statistical size omits size label")
    void buildUserMessage_regularTide_omitsSizeLabel() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.MID,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("3.80"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.50"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null,
                        LunarTideType.REGULAR_TIDE, "Waxing Crescent", false,
                        null))
                .build();

        String message = builder.buildUserMessage(data);

        assertThat(message)
                .contains("Tide: REGULAR TIDE (range:")
                .doesNotContain("Extra Extra High")
                .doesNotContain("Extra High")
                .contains("moon: Waxing Crescent")
                .contains("perigee: no");
    }

    @Test
    @DisplayName("buildUserMessage with null lunar fields falls back to Regular Tide")
    void buildUserMessage_nullLunarFields_fallsBackToRegularTide() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null, null, null, null, null))
                .build();

        String message = builder.buildUserMessage(data);

        assertThat(message)
                .contains("Tide: Regular Tide (range:")
                .doesNotContain("moon:")
                .doesNotContain("perigee:");
    }

    // ── Prompt Suffix Position ──────────────────────────────────────────────

    @Test
    @DisplayName("buildUserMessage ends with prompt suffix (tide present)")
    void buildUserMessage_withTide_endsWithSuffix() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null, null, null, null, null))
                .build();

        String message = builder.buildUserMessage(data);

        assertThat(message).endsWith(PromptBuilder.PROMPT_SUFFIX);
    }

    @Test
    @DisplayName("buildUserMessage ends with prompt suffix (no tide)")
    void buildUserMessage_withoutTide_endsWithSuffix() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = builder.buildUserMessage(data);

        assertThat(message).endsWith(PromptBuilder.PROMPT_SUFFIX);
    }

    // ── Surge via CoastalPromptBuilder ──────────────────────────────────────

    @Test
    @DisplayName("buildUserMessage with tide + surge includes both blocks")
    void buildUserMessage_tideAndSurge_includesBothBlocks() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null,
                        LunarTideType.SPRING_TIDE, "Full Moon", false, null))
                .build();

        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.23, 0.12, 0.35, 990.0, 15.0, 60.0, 0.85,
                TideRiskLevel.MODERATE, "Test surge");

        String message = builder.buildUserMessage(data, surge, 4.15, 3.80);

        assertThat(message)
                .contains("Tide: SPRING TIDE")
                .contains("STORM SURGE FORECAST:")
                .contains("Risk level: MODERATE");
    }

    @Test
    @DisplayName("buildUserMessage with tide + surge ends with prompt suffix")
    void buildUserMessage_tideAndSurge_endsWithSuffix() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null, null, null, null, null))
                .build();

        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.25, 0.15, 0.40, 985.0, 18.0, 80.0, 0.85,
                TideRiskLevel.MODERATE, "Test");

        String message = builder.buildUserMessage(data, surge, 5.4, 5.0);

        assertThat(message).endsWith(builder.getPromptSuffix());
    }

    @Test
    @DisplayName("buildUserMessage tide block appears before surge block")
    void buildUserMessage_tideBeforeSurge() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null, null, null, null, null))
                .build();

        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.25, 0.15, 0.40, 985.0, 18.0, 80.0, 0.85,
                TideRiskLevel.MODERATE, "Test");

        String message = builder.buildUserMessage(data, surge, 5.4, 5.0);

        int tideIdx = message.indexOf("Tide:");
        int surgeIdx = message.indexOf("STORM SURGE FORECAST:");
        assertThat(tideIdx).isGreaterThan(0);
        assertThat(surgeIdx).isGreaterThan(tideIdx);
    }

    @Test
    @DisplayName("buildUserMessage with insignificant surge includes tide but not surge")
    void buildUserMessage_insignificantSurge_tideOnlyNoSurge() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null, null, null, null, null))
                .build();

        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.01, 0.01, 0.02, 1015.0, 3.0, 180.0, 0.0,
                TideRiskLevel.NONE, "No significant surge");

        String message = builder.buildUserMessage(data, surge, 4.02, 4.0);

        assertThat(message).contains("Tide:");
        assertThat(message).doesNotContain("STORM SURGE FORECAST:");
    }

    // ── Prompt Suffix Uniqueness ─────────────────────────────────────────────

    @Test
    @DisplayName("buildUserMessage with tide contains prompt suffix exactly once")
    void buildUserMessage_withTide_suffixAppearsExactlyOnce() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null, null, null, null, null))
                .build();

        String message = builder.buildUserMessage(data);

        int count = countOccurrences(message, PromptBuilder.PROMPT_SUFFIX);
        assertThat(count).as("prompt suffix should appear exactly once").isEqualTo(1);
    }

    @Test
    @DisplayName("buildUserMessage with tide + surge contains prompt suffix exactly once")
    void buildUserMessage_tideAndSurge_suffixAppearsExactlyOnce() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null, null, null, null, null))
                .build();

        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.25, 0.15, 0.40, 985.0, 18.0, 80.0, 0.85,
                TideRiskLevel.MODERATE, "Test");

        String message = builder.buildUserMessage(data, surge, 5.4, 5.0);

        int count = countOccurrences(message, PromptBuilder.PROMPT_SUFFIX);
        assertThat(count).as("prompt suffix should appear exactly once").isEqualTo(1);
    }

    // ── Sky Content Preservation ─────────────────────────────────────────────

    @Test
    @DisplayName("buildUserMessage with tide preserves directional cloud data")
    void buildUserMessage_withTide_preservesDirectionalCloud() {
        AtmosphericData data = TestAtmosphericData.builder()
                .directionalCloud(new DirectionalCloudData(15, 40, 60, 30, 55, 70, 10))
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null,
                        LunarTideType.KING_TIDE, "New Moon", true,
                        TideStatisticalSize.EXTRA_EXTRA_HIGH))
                .build();

        String message = builder.buildUserMessage(data);

        assertThat(message)
                .contains("DIRECTIONAL CLOUD (113km sample):")
                .contains("Solar horizon (toward sun):")
                .contains("Antisolar horizon (away from sun):")
                .contains("Tide: KING TIDE, Extra Extra High");
    }

    @Test
    @DisplayName("buildUserMessage with tide preserves mist trend data")
    void buildUserMessage_withTide_preservesMistTrend() {
        AtmosphericData data = TestAtmosphericData.builder()
                .mistTrend(new MistTrend(List.of(
                        new MistTrend.MistSlot(-2, 800, 10.0, 11.5),
                        new MistTrend.MistSlot(-1, 1200, 10.0, 12.0),
                        new MistTrend.MistSlot(0, 2000, 10.0, 13.0))))
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null, null, null, null, null))
                .build();

        String message = builder.buildUserMessage(data);

        assertThat(message)
                .contains("MIST/VISIBILITY TREND")
                .contains("Tide:");
    }

    @Test
    @DisplayName("buildUserMessage with tide preserves cloud approach risk data")
    void buildUserMessage_withTide_preservesCloudApproachRisk() {
        SolarCloudTrend trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 10),
                new SolarCloudTrend.SolarCloudSlot(2, 25),
                new SolarCloudTrend.SolarCloudSlot(1, 45),
                new SolarCloudTrend.SolarCloudSlot(0, 55)));
        UpwindCloudSample upwind = new UpwindCloudSample(80, 225, 60, 20);
        AtmosphericData data = TestAtmosphericData.builder()
                .cloudApproach(new CloudApproachData(trend, upwind))
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null, null, null, null, null))
                .build();

        String message = builder.buildUserMessage(data);

        assertThat(message)
                .contains("CLOUD APPROACH RISK:")
                .contains("Tide:");
    }

    @Test
    @DisplayName("buildUserMessage with tide preserves inversion forecast when score is high")
    void buildUserMessage_withTide_preservesInversionForecast() {
        AtmosphericData data = TestAtmosphericData.builder()
                .inversionScore(8.5)
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null, null, null, null, null))
                .build();

        String message = builder.buildUserMessage(data);

        assertThat(message)
                .contains("CLOUD INVERSION FORECAST:")
                .contains("Tide:");
    }

    @Test
    @DisplayName("buildUserMessage tide block appears after sky content but before suffix")
    void buildUserMessage_tideAfterSkyBeforeSuffix() {
        AtmosphericData data = TestAtmosphericData.builder()
                .directionalCloud(new DirectionalCloudData(15, 40, 60, 30, 55, 70, 10))
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null, null, null, null, null))
                .build();

        String message = builder.buildUserMessage(data);

        int directionalIdx = message.indexOf("DIRECTIONAL CLOUD");
        int tideIdx = message.indexOf("Tide:");
        int suffixIdx = message.indexOf(PromptBuilder.PROMPT_SUFFIX);

        assertThat(directionalIdx).as("directional cloud before tide").isLessThan(tideIdx);
        assertThat(tideIdx).as("tide before suffix").isLessThan(suffixIdx);
    }

    // ── System Prompt Distinctness ───────────────────────────────────────────

    @Test
    @DisplayName("coastal system prompt is strictly longer than base system prompt")
    void getSystemPrompt_coastalIsLongerThanBase() {
        PromptBuilder base = new PromptBuilder();
        String basePrompt = base.getSystemPrompt();
        String coastalPrompt = builder.getSystemPrompt();

        assertThat(coastalPrompt.length()).isGreaterThan(basePrompt.length());
    }

    @Test
    @DisplayName("coastal system prompt suffix appears exactly once in the full prompt")
    void getSystemPrompt_coastalSuffixAppearsExactlyOnce() {
        String prompt = builder.getSystemPrompt();

        int count = countOccurrences(prompt, "COASTAL TIDE GUIDANCE:");
        assertThat(count).as("COASTAL TIDE GUIDANCE should appear exactly once").isEqualTo(1);
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
