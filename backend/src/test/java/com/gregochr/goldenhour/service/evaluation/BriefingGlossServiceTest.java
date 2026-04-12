package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BriefingGlossService}.
 */
@ExtendWith(MockitoExtension.class)
class BriefingGlossServiceTest {

    @Mock
    private AnthropicApiClient anthropicApiClient;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private ModelSelectionService modelSelectionService;

    private BriefingGlossService glossService;

    @BeforeEach
    void setUp() {
        glossService = new BriefingGlossService(
                anthropicApiClient, new ObjectMapper(), jobRunService, modelSelectionService);
    }

    // ── Core behaviour: gloss populated per verdict ──────────────────────────

    @Test
    @DisplayName("GO region gets Haiku-generated gloss from JSON response")
    void goRegion_glossPopulated() {
        stubModelSelection();
        Message response = mockResponse(
                "{\"headline\": \"High cirrus — good colour potential\","
                        + " \"detail\": \"40% high cloud provides canvas.\"}");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        List<BriefingDay> days = List.of(dayWith(region("Northumberland", Verdict.GO)));
        List<BriefingDay> enriched = glossService.generateGlosses(days, 1L);

        BriefingRegion r = enriched.getFirst().eventSummaries().getFirst().regions().getFirst();
        assertThat(r.glossHeadline()).isEqualTo("High cirrus — good colour potential");
        assertThat(r.glossDetail()).isEqualTo("40% high cloud provides canvas.");
    }

    @Test
    @DisplayName("MARGINAL region gets Haiku-generated gloss from JSON response")
    void marginalRegion_glossPopulated() {
        stubModelSelection();
        Message response = mockResponse(
                "{\"headline\": \"Clear all layers — flat light\","
                        + " \"detail\": \"No cloud canvas to catch colour.\"}");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        List<BriefingDay> days = List.of(dayWith(region("Northumberland", Verdict.MARGINAL)));
        List<BriefingDay> enriched = glossService.generateGlosses(days, 1L);

        BriefingRegion r = enriched.getFirst().eventSummaries().getFirst().regions().getFirst();
        assertThat(r.glossHeadline()).isEqualTo("Clear all layers — flat light");
        assertThat(r.glossDetail()).isEqualTo("No cloud canvas to catch colour.");
    }

    @Test
    @DisplayName("STANDDOWN region skipped — no Haiku call, gloss stays null")
    void standdownRegion_noCall() {
        List<BriefingDay> days = List.of(dayWith(region("Northumberland", Verdict.STANDDOWN)));
        List<BriefingDay> enriched = glossService.generateGlosses(days, 1L);

        BriefingRegion r = enriched.getFirst().eventSummaries().getFirst().regions().getFirst();
        assertThat(r.glossHeadline()).isNull();
        assertThat(r.glossDetail()).isNull();
        verify(anthropicApiClient, never()).createMessage(any());
    }

    @Test
    @DisplayName("Mixed verdicts: GO gets gloss, STANDDOWN stays null in same event")
    void mixedVerdicts_goGlossedStanddownNull() {
        stubModelSelection();
        Message response = mockResponse(
                "{\"headline\": \"High cloud with clear horizon\","
                        + " \"detail\": \"Horizon clear for colour.\"}");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        BriefingRegion goRegion = region("Northumberland", Verdict.GO);
        BriefingRegion standdownRegion = region("Lake District", Verdict.STANDDOWN);
        List<BriefingDay> days = List.of(dayWith(goRegion, standdownRegion));
        List<BriefingDay> enriched = glossService.generateGlosses(days, 1L);

        List<BriefingRegion> regions = enriched.getFirst().eventSummaries()
                .getFirst().regions();
        assertThat(regions.get(0).glossHeadline()).isEqualTo("High cloud with clear horizon");
        assertThat(regions.get(1).glossHeadline()).isNull();
        assertThat(regions.get(1).glossDetail()).isNull();

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient, times(1)).createMessage(captor.capture());
        String userMsg = captor.getValue().messages().getFirst().content().asString();
        assertThat(userMsg).contains("\"region\":\"Northumberland\"");
        assertThat(userMsg).doesNotContain("Lake District");
    }

    // ── Error handling ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Haiku failure for one region leaves gloss null, others populated")
    void partialFailure_oneGlossNull() {
        stubModelSelection();
        Message successResponse = mockResponse("Clear skies with high cloud canvas");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(successResponse)
                .thenThrow(new RuntimeException("API error"));

        BriefingRegion go1 = region("Northumberland", Verdict.GO);
        BriefingRegion go2 = region("Lake District", Verdict.GO);
        List<BriefingDay> days = List.of(dayWith(go1, go2));
        List<BriefingDay> enriched = glossService.generateGlosses(days, 1L);

        List<BriefingRegion> regions = enriched.getFirst().eventSummaries()
                .getFirst().regions();
        // One succeeded, one failed — order may vary due to parallel execution
        long withGloss = regions.stream().filter(r -> r.glossHeadline() != null).count();
        long withoutGloss = regions.stream().filter(r -> r.glossHeadline() == null).count();
        assertThat(withGloss).isEqualTo(1);
        assertThat(withoutGloss).isEqualTo(1);
    }

    @Test
    @DisplayName("Empty days returns empty days, no Haiku calls")
    void emptyDays_noApiCalls() {
        List<BriefingDay> enriched = glossService.generateGlosses(List.of(), 1L);

        assertThat(enriched).isEmpty();
        verify(anthropicApiClient, never()).createMessage(any());
    }

    // ── API call logging ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Successful API call logged with ServiceName.ANTHROPIC, status 200, response body")
    void apiCallLogged_successDetails() {
        stubModelSelection();
        Message response = mockResponse("Good colour potential");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        List<BriefingDay> days = List.of(dayWith(region("Northumberland", Verdict.GO)));
        glossService.generateGlosses(days, 42L);

        verify(jobRunService).logApiCall(
                eq(42L),
                eq(ServiceName.ANTHROPIC),
                eq("POST"),
                eq("briefing-gloss"),
                isNull(),
                anyLong(),
                eq(200),
                eq("Good colour potential"),
                eq(true),
                isNull(),
                eq(EvaluationModel.HAIKU));
    }

    @Test
    @DisplayName("Failed API call logged with error message and request body containing region")
    void failedCallLogged_errorDetails() {
        stubModelSelection();
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenThrow(new RuntimeException("Connection timeout"));

        List<BriefingDay> days = List.of(dayWith(region("Northumberland", Verdict.GO)));
        glossService.generateGlosses(days, 42L);

        verify(jobRunService).logApiCall(
                eq(42L),
                eq(ServiceName.ANTHROPIC),
                eq("POST"),
                eq("briefing-gloss"),
                argThat(body -> body != null && body.contains("\"region\":\"Northumberland\"")),
                anyLong(),
                isNull(),
                isNull(),
                eq(false),
                eq("Connection timeout"),
                eq(EvaluationModel.HAIKU));
    }

    // ── API call params (model, maxTokens, system prompt) ────────────────────

    @Test
    @DisplayName("Anthropic call uses model from ModelSelectionService and correct maxTokens")
    void anthropicCall_usesConfiguredModelAndMaxTokens() {
        when(modelSelectionService.getActiveModel(RunType.BRIEFING_GLOSS))
                .thenReturn(EvaluationModel.SONNET);
        Message response = mockResponse("Mid-level cloud canvas");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        List<BriefingDay> days = List.of(dayWith(region("Northumberland", Verdict.GO)));
        glossService.generateGlosses(days, 1L);

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());
        MessageCreateParams params = captor.getValue();
        assertThat(params.model().toString()).isEqualTo(EvaluationModel.SONNET.getModelId());
        assertThat(params.maxTokens()).isEqualTo(256);
    }

    @Test
    @DisplayName("Anthropic call includes system prompt about photography forecast")
    void anthropicCall_includesSystemPrompt() {
        stubModelSelection();
        Message response = mockResponse("Good colour potential");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        List<BriefingDay> days = List.of(dayWith(region("Northumberland", Verdict.GO)));
        glossService.generateGlosses(days, 1L);

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());
        String systemPrompt = captor.getValue().system().get()
                .asTextBlockParams().get(0).text();
        assertThat(systemPrompt).contains("photography forecast assistant");
        assertThat(systemPrompt).contains("7 words maximum");
        assertThat(systemPrompt).contains("clearAllLayers is true");
    }

    // ── Hierarchy reassembly (immutable records) ─────────────────────────────

    @Test
    @DisplayName("Enriched region preserves all original fields alongside gloss")
    void reassembly_preservesOriginalFields() {
        stubModelSelection();
        Message response = mockResponse(
                "{\"headline\": \"High cloud canvas\", \"detail\": \"Detailed explanation.\"}");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        BriefingRegion original = region("Northumberland", Verdict.GO);
        List<BriefingDay> days = List.of(dayWith(original));
        List<BriefingDay> enriched = glossService.generateGlosses(days, 1L);

        BriefingRegion enrichedRegion = enriched.getFirst().eventSummaries()
                .getFirst().regions().getFirst();
        assertThat(enrichedRegion.regionName()).isEqualTo("Northumberland");
        assertThat(enrichedRegion.verdict()).isEqualTo(Verdict.GO);
        assertThat(enrichedRegion.summary()).isEqualTo("Summary");
        assertThat(enrichedRegion.regionTemperatureCelsius()).isEqualTo(10.0);
        assertThat(enrichedRegion.regionApparentTemperatureCelsius()).isEqualTo(8.0);
        assertThat(enrichedRegion.regionWindSpeedMs()).isEqualTo(3.5);
        assertThat(enrichedRegion.regionWeatherCode()).isEqualTo(0);
        assertThat(enrichedRegion.slots()).hasSize(1);
        assertThat(enrichedRegion.glossHeadline()).isEqualTo("High cloud canvas");
        assertThat(enrichedRegion.glossDetail()).isEqualTo("Detailed explanation.");
    }

    @Test
    @DisplayName("Multiple days and events all enriched correctly")
    void multiDayMultiEvent_allEnriched() {
        stubModelSelection();
        Message response = mockResponse(
                "{\"headline\": \"Good colour potential\", \"detail\": \"Details.\"}");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        BriefingRegion goRegion1 = region("Northumberland", Verdict.GO);
        BriefingRegion goRegion2 = region("Lake District", Verdict.GO);
        BriefingEventSummary sunset = new BriefingEventSummary(
                TargetType.SUNSET, List.of(goRegion1), List.of());
        BriefingEventSummary sunrise = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(goRegion2), List.of());
        BriefingDay day1 = new BriefingDay(LocalDate.of(2026, 4, 10), List.of(sunset));
        BriefingDay day2 = new BriefingDay(LocalDate.of(2026, 4, 11), List.of(sunrise));

        List<BriefingDay> enriched = glossService.generateGlosses(List.of(day1, day2), 1L);

        assertThat(enriched).hasSize(2);
        assertThat(enriched.get(0).eventSummaries().getFirst().regions().getFirst().glossHeadline())
                .isEqualTo("Good colour potential");
        assertThat(enriched.get(1).eventSummaries().getFirst().regions().getFirst().glossHeadline())
                .isEqualTo("Good colour potential");

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient, times(2)).createMessage(captor.capture());
        List<String> userMessages = captor.getAllValues().stream()
                .map(p -> p.messages().getFirst().content().asString())
                .toList();
        assertThat(userMessages).anySatisfy(msg ->
                assertThat(msg).contains("\"region\":\"Northumberland\""));
        assertThat(userMessages).anySatisfy(msg ->
                assertThat(msg).contains("\"region\":\"Lake District\""));
    }

    @Test
    @DisplayName("Day date and event target type preserved after enrichment")
    void reassembly_preservesDayAndEventMetadata() {
        stubModelSelection();
        Message response = mockResponse("High cirrus");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        List<BriefingDay> days = List.of(dayWith(region("Northumberland", Verdict.GO)));
        List<BriefingDay> enriched = glossService.generateGlosses(days, 1L);

        assertThat(enriched.getFirst().date()).isEqualTo(LocalDate.of(2026, 4, 10));
        assertThat(enriched.getFirst().eventSummaries().getFirst().targetType())
                .isEqualTo(TargetType.SUNSET);
    }

    // ── User message construction ────────────────────────────────────────────

    @Test
    @DisplayName("buildUserMessage produces valid JSON with expected fields and values")
    void buildUserMessage_containsExpectedFieldsAndValues() {
        BriefingRegion goRegion = region("Northumberland", Verdict.GO);
        BriefingDay day = dayWith(goRegion);
        BriefingEventSummary es = day.eventSummaries().getFirst();
        BriefingGlossService.GlossWorkItem item =
                new BriefingGlossService.GlossWorkItem(0, 0, 0, day, es, goRegion);

        String json = glossService.buildUserMessage(item);

        assertThat(json).contains("\"region\":\"Northumberland\"");
        assertThat(json).contains("\"verdict\":\"GO\"");
        assertThat(json).contains("\"event\":\"sunset\"");
        assertThat(json).contains("\"date\":\"2026-04-10\"");
        assertThat(json).contains("\"cloudLow\":15");
        assertThat(json).contains("\"cloudMid\":25");
        assertThat(json).contains("\"cloudHigh\":40");
        assertThat(json).contains("\"totalLocations\":1");
        assertThat(json).contains("\"goCount\":1");
        assertThat(json).contains("\"marginalCount\":0");
    }

    @Test
    @DisplayName("buildUserMessage for MARGINAL region includes correct verdict")
    void buildUserMessage_marginalVerdict() {
        BriefingRegion marginalRegion = region("Lake District", Verdict.MARGINAL);
        BriefingDay day = dayWith(marginalRegion);
        BriefingEventSummary es = day.eventSummaries().getFirst();
        BriefingGlossService.GlossWorkItem item =
                new BriefingGlossService.GlossWorkItem(0, 0, 0, day, es, marginalRegion);

        String json = glossService.buildUserMessage(item);

        assertThat(json).contains("\"region\":\"Lake District\"");
        assertThat(json).contains("\"verdict\":\"MARGINAL\"");
        assertThat(json).contains("\"marginalCount\":1");
        assertThat(json).contains("\"goCount\":0");
    }

    @Test
    @DisplayName("buildUserMessage with coastal GO slot produces non-zero tide counts")
    void buildUserMessage_coastalSlot_nonZeroTideCounts() {
        BriefingSlot coastalSlot = new BriefingSlot(
                "Bamburgh", LocalDateTime.of(2026, 4, 10, 18, 30), Verdict.GO,
                new BriefingSlot.WeatherConditions(15, BigDecimal.ZERO, 20000, 65,
                        10.0, 8.0, 0, BigDecimal.valueOf(3.5), 25, 40),
                new BriefingSlot.TideInfo("HIGH", true, null,
                        new BigDecimal("4.5"), true, false, null, null, null),
                List.of(), null);
        BriefingRegion region = new BriefingRegion(
                "Northumberland", Verdict.GO, "Summary", List.of(),
                List.of(coastalSlot), 10.0, 8.0, 3.5, 0, null, null);
        BriefingDay day = dayWith(region);
        BriefingEventSummary es = day.eventSummaries().getFirst();
        BriefingGlossService.GlossWorkItem item =
                new BriefingGlossService.GlossWorkItem(0, 0, 0, day, es, region);

        String json = glossService.buildUserMessage(item);

        assertThat(json).contains("\"tideAlignedCount\":1");
        assertThat(json).contains("\"coastalLocationCount\":1");
        assertThat(json).contains("\"hasKingTide\":true");
    }

    // ── clearAllLayers flag ─────────────────────────────────────────────────

    @Test
    @DisplayName("buildUserMessage sets clearAllLayers:true when slot has 'Clear all layers' flag")
    void buildUserMessage_clearAllLayers_trueWhenFlagPresent() {
        BriefingRegion clearRegion = regionWithFlags("Northumberland", Verdict.MARGINAL,
                List.of("Clear all layers"));
        BriefingDay day = dayWith(clearRegion);
        BriefingEventSummary es = day.eventSummaries().getFirst();
        BriefingGlossService.GlossWorkItem item =
                new BriefingGlossService.GlossWorkItem(0, 0, 0, day, es, clearRegion);

        String json = glossService.buildUserMessage(item);

        assertThat(json).contains("\"clearAllLayers\":true");
    }

    @Test
    @DisplayName("buildUserMessage sets clearAllLayers:false when no flag present")
    void buildUserMessage_clearAllLayers_falseWhenNoFlag() {
        BriefingRegion goRegion = region("Northumberland", Verdict.GO);
        BriefingDay day = dayWith(goRegion);
        BriefingEventSummary es = day.eventSummaries().getFirst();
        BriefingGlossService.GlossWorkItem item =
                new BriefingGlossService.GlossWorkItem(0, 0, 0, day, es, goRegion);

        String json = glossService.buildUserMessage(item);

        assertThat(json).contains("\"clearAllLayers\":false");
    }

    @Test
    @DisplayName("Clear-all-layers region sends clearAllLayers:true in user message to Claude")
    void clearAllLayersRegion_userMessageSentToClaude() {
        stubModelSelection();
        Message response = mockResponse("Clear sky — no canvas");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        BriefingRegion clearRegion = regionWithFlags("Northumberland", Verdict.MARGINAL,
                List.of("Clear all layers"));
        List<BriefingDay> days = List.of(dayWith(clearRegion));
        glossService.generateGlosses(days, 1L);

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());
        String userMessage = captor.getValue().messages().getFirst()
                .content().asString();
        assertThat(userMessage).contains("\"clearAllLayers\":true");
        assertThat(userMessage).contains("\"verdict\":\"MARGINAL\"");
    }

    @Test
    @DisplayName("System prompt contains clearAllLayers cautionary rule")
    void systemPrompt_containsClearAllLayersCautionaryRule() {
        stubModelSelection();
        Message response = mockResponse("Canvas potential");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        List<BriefingDay> days = List.of(dayWith(region("Northumberland", Verdict.GO)));
        glossService.generateGlosses(days, 1L);

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());
        String systemPrompt = captor.getValue().system().get()
                .asTextBlockParams().get(0).text();
        assertThat(systemPrompt).contains("clearAllLayers is true");
        assertThat(systemPrompt).contains("MUST be cautionary");
        assertThat(systemPrompt).contains("no cloud canvas");
    }

    @Test
    @DisplayName("buildUserMessage sets buildingTrend:true when slot has 'Cloud building' flag")
    void buildUserMessage_buildingTrend_trueWhenFlagPresent() {
        BriefingRegion buildingRegion = regionWithFlags("Lake District", Verdict.GO,
                List.of("Cloud building"));
        BriefingDay day = dayWith(buildingRegion);
        BriefingEventSummary es = day.eventSummaries().getFirst();
        BriefingGlossService.GlossWorkItem item =
                new BriefingGlossService.GlossWorkItem(0, 0, 0, day, es, buildingRegion);

        String json = glossService.buildUserMessage(item);

        assertThat(json).contains("\"buildingTrend\":true");
    }

    @Test
    @DisplayName("System prompt instructs Claude to return JSON with headline and detail fields")
    void systemPrompt_requestsJsonFormat() {
        stubModelSelection();
        Message response = mockResponse(
                "{\"headline\": \"Test\", \"detail\": \"Test detail.\"}");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        List<BriefingDay> days = List.of(dayWith(region("Northumberland", Verdict.GO)));
        glossService.generateGlosses(days, 1L);

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());
        String systemPrompt = captor.getValue().system().get()
                .asTextBlockParams().get(0).text();
        assertThat(systemPrompt).contains("\"headline\"");
        assertThat(systemPrompt).contains("\"detail\"");
        assertThat(systemPrompt).containsIgnoringCase("JSON");
    }

    @Test
    @DisplayName("GO glossDetail survives reassembly while STANDDOWN glossDetail stays null")
    void mixedVerdicts_glossDetailSurvivesReassembly() {
        stubModelSelection();
        Message response = mockResponse(
                "{\"headline\": \"Clear horizon\","
                        + " \"detail\": \"Low cloud minimal, mid clear.\"}");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        BriefingRegion goRegion = region("Northumberland", Verdict.GO);
        BriefingRegion standdownRegion = region("Lake District", Verdict.STANDDOWN);
        List<BriefingDay> days = List.of(dayWith(goRegion, standdownRegion));
        List<BriefingDay> enriched = glossService.generateGlosses(days, 1L);

        List<BriefingRegion> regions = enriched.getFirst().eventSummaries()
                .getFirst().regions();
        assertThat(regions.get(0).glossDetail()).isEqualTo("Low cloud minimal, mid clear.");
        assertThat(regions.get(1).glossDetail()).isNull();
    }

    // ── JSON parsing and fallback ──────────────────────────────────────────

    @Test
    @DisplayName("Valid JSON response parses headline and detail")
    void validJson_parsesHeadlineAndDetail() {
        stubModelSelection();
        Message response = mockResponse(
                "{\"headline\": \"Cirrus canvas — good colour\","
                        + " \"detail\": \"High cloud at 40% provides canvas. "
                        + "Low cloud minimal. Tide aligned at 2 spots.\"}");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        List<BriefingDay> days = List.of(dayWith(region("Northumberland", Verdict.GO)));
        List<BriefingDay> enriched = glossService.generateGlosses(days, 1L);

        BriefingRegion r = enriched.getFirst().eventSummaries().getFirst().regions().getFirst();
        assertThat(r.glossHeadline()).isEqualTo("Cirrus canvas — good colour");
        assertThat(r.glossDetail()).startsWith("High cloud at 40%");
    }

    @Test
    @DisplayName("Invalid JSON falls back to truncated headline, null detail")
    void invalidJson_fallsBackToTruncatedHeadline() {
        stubModelSelection();
        Message response = mockResponse(
                "High cirrus canvas providing excellent colour potential today");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        List<BriefingDay> days = List.of(dayWith(region("Northumberland", Verdict.GO)));
        List<BriefingDay> enriched = glossService.generateGlosses(days, 1L);

        BriefingRegion r = enriched.getFirst().eventSummaries().getFirst().regions().getFirst();
        assertThat(r.glossHeadline()).isEqualTo("High cirrus canvas providing excellent colour potential");
        assertThat(r.glossDetail()).isNull();
    }

    @Test
    @DisplayName("Headline exceeding 7 words is truncated")
    void longHeadline_truncatedToSevenWords() {
        stubModelSelection();
        Message response = mockResponse(
                "{\"headline\": \"One two three four five six seven eight nine\","
                        + " \"detail\": \"Detail text.\"}");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        List<BriefingDay> days = List.of(dayWith(region("Northumberland", Verdict.GO)));
        List<BriefingDay> enriched = glossService.generateGlosses(days, 1L);

        BriefingRegion r = enriched.getFirst().eventSummaries().getFirst().regions().getFirst();
        assertThat(r.glossHeadline()).isEqualTo("One two three four five six seven");
        assertThat(r.glossDetail()).isEqualTo("Detail text.");
    }

    @Test
    @DisplayName("```json ... ``` code fence wrapper is stripped before parsing")
    void jsonCodeFence_strippedAndParsedCorrectly() {
        stubModelSelection();
        Message response = mockResponse(
                "```json\n{\"headline\": \"Cirrus canvas — colour potential\","
                        + " \"detail\": \"High cloud at 35%.\"\n}```");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        List<BriefingDay> days = List.of(dayWith(region("Northumberland", Verdict.GO)));
        List<BriefingDay> enriched = glossService.generateGlosses(days, 1L);

        BriefingRegion r = enriched.getFirst().eventSummaries().getFirst().regions().getFirst();
        assertThat(r.glossHeadline()).isEqualTo("Cirrus canvas — colour potential");
        assertThat(r.glossDetail()).isEqualTo("High cloud at 35%.");
    }

    @Test
    @DisplayName("Plain ``` fence wrapper (no language tag) is stripped before parsing")
    void plainCodeFence_strippedAndParsedCorrectly() {
        stubModelSelection();
        Message response = mockResponse(
                "```\n{\"headline\": \"Low cloud clearing by sunset\","
                        + " \"detail\": \"Breaks expected by 19:00.\"\n}```");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        List<BriefingDay> days = List.of(dayWith(region("Northumberland", Verdict.GO)));
        List<BriefingDay> enriched = glossService.generateGlosses(days, 1L);

        BriefingRegion r = enriched.getFirst().eventSummaries().getFirst().regions().getFirst();
        assertThat(r.glossHeadline()).isEqualTo("Low cloud clearing by sunset");
        assertThat(r.glossDetail()).isEqualTo("Breaks expected by 19:00.");
    }

    // ── truncateToWords ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("truncateToWords")
    class TruncateToWordsTests {

        @Test
        @DisplayName("Truncates to maxWords when input exceeds limit")
        void truncates_whenExceedsLimit() {
            assertThat(BriefingGlossService.truncateToWords("one two three", 2))
                    .isEqualTo("one two");
        }

        @Test
        @DisplayName("Returns original when within limit")
        void returnsOriginal_whenWithinLimit() {
            assertThat(BriefingGlossService.truncateToWords("short", 5))
                    .isEqualTo("short");
        }

        @Test
        @DisplayName("Returns null for null input")
        void returnsNull_forNull() {
            assertThat(BriefingGlossService.truncateToWords(null, 5)).isNull();
        }

        @Test
        @DisplayName("Returns original for blank input")
        void returnsBlank_forBlank() {
            assertThat(BriefingGlossService.truncateToWords("  ", 5)).isEqualTo("  ");
        }

        @Test
        @DisplayName("Returns original for empty string")
        void returnsEmpty_forEmpty() {
            assertThat(BriefingGlossService.truncateToWords("", 5)).isEqualTo("");
        }

        @Test
        @DisplayName("Exactly maxWords returns original stripped")
        void exactlyMaxWords_returnsOriginal() {
            assertThat(BriefingGlossService.truncateToWords("one two three", 3))
                    .isEqualTo("one two three");
        }

        @Test
        @DisplayName("Single word with limit 1 returns that word")
        void singleWord_limitOne() {
            assertThat(BriefingGlossService.truncateToWords("hello", 1))
                    .isEqualTo("hello");
        }

        @Test
        @DisplayName("Multiple spaces between words handled correctly")
        void multipleSpaces_handled() {
            assertThat(BriefingGlossService.truncateToWords("one  two  three  four", 2))
                    .isEqualTo("one two");
        }

        @Test
        @DisplayName("Leading/trailing whitespace stripped before counting")
        void leadingTrailingWhitespace_stripped() {
            assertThat(BriefingGlossService.truncateToWords("  one two three  ", 2))
                    .isEqualTo("one two");
        }
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("median returns middle value of sorted array")
    void median_basicCases() {
        assertThat(BriefingGlossService.median(new int[]{1, 2, 3})).isEqualTo(2);
        assertThat(BriefingGlossService.median(new int[]{10, 20})).isEqualTo(20);
        assertThat(BriefingGlossService.median(new int[]{})).isEqualTo(0);
        assertThat(BriefingGlossService.median(new int[]{42})).isEqualTo(42);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubModelSelection() {
        when(modelSelectionService.getActiveModel(RunType.BRIEFING_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
    }

    private static BriefingDay dayWith(BriefingRegion... regions) {
        BriefingEventSummary es = new BriefingEventSummary(
                TargetType.SUNSET, List.of(regions), List.of());
        return new BriefingDay(LocalDate.of(2026, 4, 10), List.of(es));
    }

    private static BriefingRegion region(String name, Verdict verdict) {
        return regionWithFlags(name, verdict, List.of());
    }

    private static BriefingRegion regionWithFlags(String name, Verdict verdict,
            List<String> flags) {
        BriefingSlot slot = new BriefingSlot(
                "Location1", LocalDateTime.of(2026, 4, 10, 18, 30), verdict,
                new BriefingSlot.WeatherConditions(15, BigDecimal.ZERO, 20000, 65,
                        10.0, 8.0, 0, BigDecimal.valueOf(3.5), 25, 40),
                BriefingSlot.TideInfo.NONE, flags, null);
        return new BriefingRegion(name, verdict, "Summary", List.of(), List.of(slot),
                10.0, 8.0, 3.5, 0, null, null);
    }

    private static Message mockResponse(String text) {
        TextBlock textBlock = TextBlock.builder().text(text).citations(List.of()).build();
        ContentBlock contentBlock = ContentBlock.ofText(textBlock);
        Message message = mock(Message.class);
        when(message.content()).thenReturn(List.of(contentBlock));
        return message;
    }
}
