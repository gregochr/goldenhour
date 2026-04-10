package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.AuroraLocationSlot;
import com.gregochr.goldenhour.model.AuroraRegionSummary;
import com.gregochr.goldenhour.model.MoonTransitionData;
import com.gregochr.goldenhour.model.MoonTransitionData.WindowQuality;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.solarutils.LunarPhase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuroraGlossService}.
 */
@ExtendWith(MockitoExtension.class)
class AuroraGlossServiceTest {

    @Mock
    private AnthropicApiClient anthropicApiClient;

    @Mock
    private ModelSelectionService modelSelectionService;

    private AuroraGlossService glossService;

    @BeforeEach
    void setUp() {
        glossService = new AuroraGlossService(
                anthropicApiClient, new ObjectMapper(), modelSelectionService);
    }

    // ── GO region gets glossed ──

    @Test
    @DisplayName("GO region gets Claude-generated gloss")
    void goRegion_glossPopulated() {
        when(modelSelectionService.getActiveModel(RunType.AURORA_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        mockClaudeResponse("Strong Kp — excellent conditions");

        List<AuroraRegionSummary> regions = List.of(goRegion("Northumberland"));
        MoonTransitionData moon = new MoonTransitionData(LunarPhase.FIRST_QUARTER,
                45.0, WindowQuality.MOONLIT_ALL_WINDOW, null, null, true, true);

        List<AuroraRegionSummary> result = glossService.enrichGlosses(
                regions, moon, AlertLevel.MODERATE, 5.0);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).gloss()).isEqualTo("Strong Kp — excellent conditions");
        // Region data should be preserved through the gloss enrichment
        assertThat(result.get(0).regionName()).isEqualTo("Northumberland");
        assertThat(result.get(0).verdict()).isEqualTo("GO");
        assertThat(result.get(0).clearLocationCount()).isEqualTo(1);
    }

    // ── STANDDOWN region is skipped ──

    @Test
    @DisplayName("STANDDOWN region is not glossed — no Claude call")
    void standdownRegion_noCall() {
        when(modelSelectionService.getActiveModel(RunType.AURORA_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);

        List<AuroraRegionSummary> regions = List.of(standdownRegion("North York Moors"));

        List<AuroraRegionSummary> result = glossService.enrichGlosses(
                regions, null, AlertLevel.MODERATE, 5.0);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).gloss()).isNull();
        verify(anthropicApiClient, never()).createMessage(any(MessageCreateParams.class));
    }

    // ── Mixed: GO glossed, STANDDOWN skipped ──

    @Test
    @DisplayName("Mixed regions: GO gets gloss, STANDDOWN stays null — exactly one Claude call")
    void mixedRegions_onlyGoGlossed() {
        when(modelSelectionService.getActiveModel(RunType.AURORA_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        mockClaudeResponse("Clear dark sky — Bortle 2");

        List<AuroraRegionSummary> regions = List.of(
                goRegion("Northumberland"),
                standdownRegion("Lake District"));

        List<AuroraRegionSummary> result = glossService.enrichGlosses(
                regions, null, AlertLevel.MODERATE, 5.0);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).gloss()).isEqualTo("Clear dark sky — Bortle 2");
        assertThat(result.get(1).gloss()).isNull();
        // Exactly one call — only the GO region should trigger a Claude call
        verify(anthropicApiClient, times(1)).createMessage(any(MessageCreateParams.class));
    }

    // ── Multiple GO regions each get separate calls ──

    @Test
    @DisplayName("Two GO regions each get their own Claude call")
    void multipleGoRegions_eachGetsSeparateCall() {
        when(modelSelectionService.getActiveModel(RunType.AURORA_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        mockClaudeResponse("Strong aurora potential");

        List<AuroraRegionSummary> regions = List.of(
                goRegion("Northumberland"),
                goRegion("Lake District"));

        List<AuroraRegionSummary> result = glossService.enrichGlosses(
                regions, null, AlertLevel.MODERATE, 5.0);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).gloss()).isEqualTo("Strong aurora potential");
        assertThat(result.get(1).gloss()).isEqualTo("Strong aurora potential");
        verify(anthropicApiClient, times(2)).createMessage(any(MessageCreateParams.class));
    }

    // ── Region ordering is preserved ──

    @Test
    @DisplayName("Output order matches input order: GO, STANDDOWN, GO")
    void regionOrdering_preserved() {
        when(modelSelectionService.getActiveModel(RunType.AURORA_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        mockClaudeResponse("Active Kp tonight");

        List<AuroraRegionSummary> regions = List.of(
                goRegion("Northumberland"),
                standdownRegion("Lake District"),
                goRegion("Scottish Borders"));

        List<AuroraRegionSummary> result = glossService.enrichGlosses(
                regions, null, AlertLevel.MODERATE, 5.0);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).regionName()).isEqualTo("Northumberland");
        assertThat(result.get(0).gloss()).isEqualTo("Active Kp tonight");
        assertThat(result.get(1).regionName()).isEqualTo("Lake District");
        assertThat(result.get(1).gloss()).isNull();
        assertThat(result.get(2).regionName()).isEqualTo("Scottish Borders");
        assertThat(result.get(2).gloss()).isEqualTo("Active Kp tonight");
    }

    // ── Empty regions ──

    @Test
    @DisplayName("Empty regions list returns empty — no Claude calls")
    void emptyRegions_noCalls() {
        List<AuroraRegionSummary> result = glossService.enrichGlosses(
                List.of(), null, AlertLevel.MODERATE, 5.0);

        assertThat(result).isEmpty();
        verify(anthropicApiClient, never()).createMessage(any(MessageCreateParams.class));
    }

    // ── Claude failure ──

    @Test
    @DisplayName("Claude failure for a region leaves gloss null, region data intact")
    void claudeFailure_glossNull() {
        when(modelSelectionService.getActiveModel(RunType.AURORA_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenThrow(new RuntimeException("API error"));

        List<AuroraRegionSummary> regions = List.of(goRegion("Northumberland"));

        List<AuroraRegionSummary> result = glossService.enrichGlosses(
                regions, null, AlertLevel.MODERATE, 5.0);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).gloss()).isNull();
        // Region data must survive the failure
        assertThat(result.get(0).regionName()).isEqualTo("Northumberland");
        assertThat(result.get(0).verdict()).isEqualTo("GO");
    }

    // ── Correct model sent to API ──

    @Test
    @DisplayName("API call uses the model returned by ModelSelectionService")
    void apiCall_usesConfiguredModel() {
        when(modelSelectionService.getActiveModel(RunType.AURORA_GLOSS))
                .thenReturn(EvaluationModel.SONNET);
        mockClaudeResponse("Great conditions");

        glossService.enrichGlosses(
                List.of(goRegion("Northumberland")), null, AlertLevel.MODERATE, 5.0);

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());
        assertThat(captor.getValue().model().toString())
                .isEqualTo(EvaluationModel.SONNET.getModelId());
    }

    // ── User message contains region data ──

    @Test
    @DisplayName("User message contains regionName, verdict, alertLevel, kp, and moon data")
    void userMessage_containsRegionData() {
        when(modelSelectionService.getActiveModel(RunType.AURORA_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        mockClaudeResponse("Great conditions");

        List<AuroraRegionSummary> regions = List.of(goRegion("Northumberland"));
        MoonTransitionData moon = new MoonTransitionData(LunarPhase.WAXING_GIBBOUS,
                72.0, WindowQuality.DARK_THEN_MOONLIT, "23:05", null, false, true);

        glossService.enrichGlosses(regions, moon, AlertLevel.STRONG, 7.3);

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());

        String userMessage = captor.getValue().messages().getFirst()
                .content().asString();
        assertThat(userMessage).contains("\"regionName\":\"Northumberland\"");
        assertThat(userMessage).contains("\"verdict\":\"GO\"");
        assertThat(userMessage).contains("\"alertLevel\":\"STRONG\"");
        assertThat(userMessage).contains("\"kp\":7.3");
        assertThat(userMessage).contains("\"moonIlluminationPct\":72");
        assertThat(userMessage).contains("\"windowQuality\":\"DARK_THEN_MOONLIT\"");
        assertThat(userMessage).contains("\"moonRiseTime\":\"23:05\"");
        assertThat(userMessage).contains("\"moonUpAtStart\":false");
        assertThat(userMessage).contains("\"moonUpAtEnd\":true");
    }

    // ── System prompt contains moonlight cautionary rule ──

    @Test
    @DisplayName("System prompt contains moonlight cautionary rule")
    void systemPrompt_containsMoonlightRule() {
        when(modelSelectionService.getActiveModel(RunType.AURORA_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        mockClaudeResponse("Bright moon — aurora washed out");

        glossService.enrichGlosses(
                List.of(goRegion("Northumberland")), null, AlertLevel.MODERATE, 5.0);

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());

        String systemPrompt = captor.getValue().system().get().asTextBlockParams()
                .getFirst().text();
        assertThat(systemPrompt).contains("windowQuality is MOONLIT_ALL_WINDOW");
        assertThat(systemPrompt).contains("moonIlluminationPct > 60");
        assertThat(systemPrompt).contains("MUST be cautionary about moonlight");
    }

    // ── buildUserMessage ──

    @Test
    @DisplayName("buildUserMessage includes clearLocationCount, totalDarkSkyLocations, bestBortleClass")
    void buildUserMessage_includesLocationStats() {
        AuroraRegionSummary region = goRegion("Northumberland");

        String json = glossService.buildUserMessage(
                region, null, AlertLevel.MODERATE, 5.0);

        assertThat(json).contains("\"clearLocationCount\":1");
        assertThat(json).contains("\"totalDarkSkyLocations\":1");
        assertThat(json).contains("\"bestBortleClass\":3");
    }

    @Test
    @DisplayName("buildUserMessage includes transition-aware moon fields when moon up all window")
    void buildUserMessage_moonUpAllWindow() {
        AuroraRegionSummary region = goRegion("Northumberland");
        MoonTransitionData moon = new MoonTransitionData(LunarPhase.WAXING_GIBBOUS,
                80.0, WindowQuality.MOONLIT_ALL_WINDOW, null, null, true, true);

        String json = glossService.buildUserMessage(
                region, moon, AlertLevel.MODERATE, 5.0);

        assertThat(json).contains("\"moonIlluminationPct\":80");
        assertThat(json).contains("\"moonPhase\":\"WAXING_GIBBOUS\"");
        assertThat(json).contains("\"windowQuality\":\"MOONLIT_ALL_WINDOW\"");
        assertThat(json).contains("\"moonUpAtStart\":true");
        assertThat(json).contains("\"moonUpAtEnd\":true");
    }

    @Test
    @DisplayName("buildUserMessage includes transition-aware moon fields when moon below all window")
    void buildUserMessage_moonBelowAllWindow() {
        AuroraRegionSummary region = goRegion("Northumberland");
        MoonTransitionData moon = new MoonTransitionData(LunarPhase.NEW_MOON,
                5.0, WindowQuality.DARK_ALL_WINDOW, null, null, false, false);

        String json = glossService.buildUserMessage(
                region, moon, AlertLevel.MODERATE, 5.0);

        assertThat(json).contains("\"moonIlluminationPct\":5");
        assertThat(json).contains("\"moonPhase\":\"NEW_MOON\"");
        assertThat(json).contains("\"windowQuality\":\"DARK_ALL_WINDOW\"");
        assertThat(json).contains("\"moonUpAtStart\":false");
        assertThat(json).contains("\"moonUpAtEnd\":false");
    }

    @Test
    @DisplayName("buildUserMessage handles null moon gracefully")
    void buildUserMessage_nullMoon() {
        AuroraRegionSummary region = goRegion("Northumberland");

        String json = glossService.buildUserMessage(
                region, null, AlertLevel.MODERATE, 5.0);

        assertThat(json).doesNotContain("windowQuality");
        assertThat(json).doesNotContain("moonIlluminationPct");
        assertThat(json).doesNotContain("moonPhase");
        assertThat(json).contains("\"regionName\":\"Northumberland\"");
    }

    @Test
    @DisplayName("buildUserMessage omits bestBortleClass when null")
    void buildUserMessage_nullBortleOmitted() {
        AuroraLocationSlot slot = new AuroraLocationSlot(
                "TestLocation", null, true, 30, 5.0, 3.0, 0);
        AuroraRegionSummary region = new AuroraRegionSummary(
                "Northumberland", "GO", 1, 1, null, List.of(slot),
                5.0, 3.0, 0, null);

        String json = glossService.buildUserMessage(
                region, null, AlertLevel.MODERATE, 5.0);

        assertThat(json).doesNotContain("bestBortleClass");
    }

    @Test
    @DisplayName("buildUserMessage omits kp when null")
    void buildUserMessage_nullKpOmitted() {
        AuroraRegionSummary region = goRegion("Northumberland");

        String json = glossService.buildUserMessage(
                region, null, AlertLevel.MODERATE, null);

        assertThat(json).doesNotContain("\"kp\"");
        assertThat(json).contains("\"alertLevel\":\"MODERATE\"");
    }

    // ── Helpers ──

    private void mockClaudeResponse(String text) {
        Message response = mock(Message.class);
        TextBlock textBlock = mock(TextBlock.class);
        when(textBlock.text()).thenReturn(text);
        ContentBlock block = mock(ContentBlock.class);
        when(block.isText()).thenReturn(true);
        when(block.asText()).thenReturn(textBlock);
        when(response.content()).thenReturn(List.of(block));
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);
    }

    private static AuroraRegionSummary goRegion(String name) {
        AuroraLocationSlot slot = new AuroraLocationSlot(
                "TestLocation", 3, true, 30, 5.0, 3.0, 0);
        return new AuroraRegionSummary(
                name, "GO", 1, 1, 3, List.of(slot),
                5.0, 3.0, 0, null);
    }

    private static AuroraRegionSummary standdownRegion(String name) {
        AuroraLocationSlot slot = new AuroraLocationSlot(
                "CloudyLocation", 4, false, 90, 8.0, 5.0, 61);
        return new AuroraRegionSummary(
                name, "STANDDOWN", 0, 1, 4, List.of(slot),
                8.0, 5.0, 61, null);
    }
}
