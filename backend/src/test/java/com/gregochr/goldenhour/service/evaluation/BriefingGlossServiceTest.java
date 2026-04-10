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
        org.mockito.Mockito.lenient()
                .when(modelSelectionService.getActiveModel(RunType.BRIEFING_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
    }

    // ── Core behaviour: gloss populated per verdict ──────────────────────────

    @Test
    @DisplayName("GO region gets Haiku-generated gloss")
    void goRegion_glossPopulated() {
        Message response = mockResponse("High cirrus canvas — good colour potential");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        List<BriefingDay> days = List.of(dayWith(region("Northumberland", Verdict.GO)));
        List<BriefingDay> enriched = glossService.generateGlosses(days, 1L);

        assertThat(enriched.getFirst().eventSummaries().getFirst().regions().getFirst().gloss())
                .isEqualTo("High cirrus canvas — good colour potential");
    }

    @Test
    @DisplayName("MARGINAL region gets Haiku-generated gloss")
    void marginalRegion_glossPopulated() {
        Message response = mockResponse("Clear all layers — flat light, nothing to catch colour");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        List<BriefingDay> days = List.of(dayWith(region("Northumberland", Verdict.MARGINAL)));
        List<BriefingDay> enriched = glossService.generateGlosses(days, 1L);

        assertThat(enriched.getFirst().eventSummaries().getFirst().regions().getFirst().gloss())
                .isEqualTo("Clear all layers — flat light, nothing to catch colour");
    }

    @Test
    @DisplayName("STANDDOWN region skipped — no Haiku call, gloss stays null")
    void standdownRegion_noCall() {
        List<BriefingDay> days = List.of(dayWith(region("Northumberland", Verdict.STANDDOWN)));
        List<BriefingDay> enriched = glossService.generateGlosses(days, 1L);

        assertThat(enriched.getFirst().eventSummaries().getFirst().regions().getFirst().gloss())
                .isNull();
        verify(anthropicApiClient, never()).createMessage(any());
    }

    @Test
    @DisplayName("Mixed verdicts: GO gets gloss, STANDDOWN stays null in same event")
    void mixedVerdicts_goGlossedStanddownNull() {
        Message response = mockResponse("High cloud canvas with clear horizon");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);

        BriefingRegion goRegion = region("Northumberland", Verdict.GO);
        BriefingRegion standdownRegion = region("Lake District", Verdict.STANDDOWN);
        List<BriefingDay> days = List.of(dayWith(goRegion, standdownRegion));
        List<BriefingDay> enriched = glossService.generateGlosses(days, 1L);

        List<BriefingRegion> regions = enriched.getFirst().eventSummaries()
                .getFirst().regions();
        assertThat(regions.get(0).gloss()).isEqualTo("High cloud canvas with clear horizon");
        assertThat(regions.get(1).gloss()).isNull();
        verify(anthropicApiClient, times(1)).createMessage(any(MessageCreateParams.class));
    }

    // ── Error handling ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Haiku failure for one region leaves gloss null, others populated")
    void partialFailure_oneGlossNull() {
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
        long withGloss = regions.stream().filter(r -> r.gloss() != null).count();
        long withoutGloss = regions.stream().filter(r -> r.gloss() == null).count();
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
        assertThat(params.maxTokens()).isEqualTo(64);
    }

    @Test
    @DisplayName("Anthropic call includes system prompt about photography forecast")
    void anthropicCall_includesSystemPrompt() {
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
        assertThat(systemPrompt).contains("10 words or fewer");
    }

    // ── Hierarchy reassembly (immutable records) ─────────────────────────────

    @Test
    @DisplayName("Enriched region preserves all original fields alongside gloss")
    void reassembly_preservesOriginalFields() {
        Message response = mockResponse("High cloud canvas");
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
        assertThat(enrichedRegion.gloss()).isEqualTo("High cloud canvas");
    }

    @Test
    @DisplayName("Multiple days and events all enriched correctly")
    void multiDayMultiEvent_allEnriched() {
        Message response = mockResponse("Good colour potential");
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
        assertThat(enriched.get(0).eventSummaries().getFirst().regions().getFirst().gloss())
                .isEqualTo("Good colour potential");
        assertThat(enriched.get(1).eventSummaries().getFirst().regions().getFirst().gloss())
                .isEqualTo("Good colour potential");
        verify(anthropicApiClient, times(2)).createMessage(any(MessageCreateParams.class));
    }

    @Test
    @DisplayName("Day date and event target type preserved after enrichment")
    void reassembly_preservesDayAndEventMetadata() {
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

    private static BriefingDay dayWith(BriefingRegion... regions) {
        BriefingEventSummary es = new BriefingEventSummary(
                TargetType.SUNSET, List.of(regions), List.of());
        return new BriefingDay(LocalDate.of(2026, 4, 10), List.of(es));
    }

    private static BriefingRegion region(String name, Verdict verdict) {
        BriefingSlot slot = new BriefingSlot(
                "Location1", LocalDateTime.of(2026, 4, 10, 18, 30), verdict,
                new BriefingSlot.WeatherConditions(15, BigDecimal.ZERO, 20000, 65,
                        10.0, 8.0, 0, BigDecimal.valueOf(3.5), 25, 40),
                BriefingSlot.TideInfo.NONE, List.of(), null);
        return new BriefingRegion(name, verdict, "Summary", List.of(), List.of(slot),
                10.0, 8.0, 3.5, 0, null);
    }

    private static Message mockResponse(String text) {
        TextBlock textBlock = TextBlock.builder().text(text).citations(List.of()).build();
        ContentBlock contentBlock = ContentBlock.ofText(textBlock);
        Message message = mock(Message.class);
        when(message.content()).thenReturn(List.of(contentBlock));
        return message;
    }
}
