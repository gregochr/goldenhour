package com.gregochr.goldenhour.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the one property that decides whether a restart could seed the gloss reuse map from the
 * persisted briefing: a region that has been through {@code daily_briefing_cache} JSON (the
 * Jackson 2 mapper {@code AppConfig} builds) must produce the identical gloss user message.
 */
class BriefingGlossReuseTest {

    /** The mapper {@code AppConfig.objectMapper()} builds, which persists and reads the cache. */
    private static final ObjectMapper PERSIST_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private static BriefingSlot.WeatherConditions weather(int low) {
        return new BriefingSlot.WeatherConditions(low, BigDecimal.ZERO, 20000, 65,
                10.0, 8.0, 0, BigDecimal.valueOf(3.5), 25, 40);
    }

    private static BriefingRegion richRegion() {
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo("HIGH", true,
                LocalDateTime.of(2026, 4, 10, 18, 5), BigDecimal.valueOf(5.2), true, true,
                LunarTideType.KING_TIDE, "FULL_MOON", true);
        BriefingSlot coastal = new BriefingSlot("Bamburgh", LocalDateTime.of(2026, 4, 10, 18, 30),
                Verdict.GO, weather(15), tide, List.of("Cloud building"), null)
                .withClaudeScores(4, 80, 70, "Good light");
        BriefingSlot inland = new BriefingSlot("Inland", LocalDateTime.of(2026, 4, 10, 18, 30),
                Verdict.MARGINAL, weather(60), BriefingSlot.TideInfo.NONE,
                List.of("Clear all layers"), null).withClaudeScores(2, 40, 30, "Flat");
        BriefingSlot wood = BriefingSlot.canopySlot("Bluebell Wood",
                LocalDateTime.of(2026, 4, 10, 18, 30), Verdict.GO, weather(20), List.of(), null)
                .withClaudeScores(5, 80, 70, "Carpet");
        return new BriefingRegion("Northumberland", Verdict.GO, "Summary", List.of("High tide"),
                List.of(coastal, inland, wood), 10.0, 8.0, 3.5, 0, "Headline", "Detail");
    }

    private static DailyBriefingResponse responseOf(BriefingDay day) {
        return new DailyBriefingResponse(LocalDateTime.of(2026, 4, 10, 6, 0), "Headline",
                List.of(day), List.of(), null, null, false, false, 0, null, List.of(), List.of(),
                null, null, null, null, null, null);
    }

    @Test
    @DisplayName("A region survives the daily_briefing_cache JSON round trip with an identical gloss input")
    void roundTripYieldsIdenticalUserMessage() throws Exception {
        BriefingGlossService service = new BriefingGlossService(mock(AnthropicApiClient.class),
                new ObjectMapper(), mock(JobRunService.class), mock(ModelSelectionService.class), 0.5);
        BriefingRegion region = richRegion();
        BriefingEventSummary es = new BriefingEventSummary(TargetType.SUNSET, List.of(region), List.of());
        BriefingDay day = new BriefingDay(LocalDate.of(2026, 4, 10), List.of(es));
        String before = service.buildUserMessage(
                new BriefingGlossService.GlossWorkItem(0, 0, 0, day, es, region));

        String json = PERSIST_MAPPER.writeValueAsString(responseOf(day));
        DailyBriefingResponse back = PERSIST_MAPPER.readValue(json, DailyBriefingResponse.class);
        BriefingDay day2 = back.days().getFirst();
        BriefingEventSummary es2 = day2.eventSummaries().getFirst();
        BriefingRegion region2 = es2.regions().getFirst();
        String after = service.buildUserMessage(
                new BriefingGlossService.GlossWorkItem(0, 0, 0, day2, es2, region2));

        assertThat(after).isEqualTo(before);
    }

    // ── Reuse behaviour ──────────────────────────────────────────────────────

    private static final String OK_JSON = "{\"headline\": \"High cirrus\", \"detail\": \"Canvas.\"}";

    private AnthropicApiClient client;
    private ModelSelectionService models;
    private BriefingGlossService reuseService;

    @BeforeEach
    void setUpReuse() {
        client = mock(AnthropicApiClient.class);
        models = mock(ModelSelectionService.class);
        when(models.getActiveModel(RunType.BRIEFING_GLOSS)).thenReturn(EvaluationModel.HAIKU);
        reuseService = new BriefingGlossService(client, new ObjectMapper(), mock(JobRunService.class), models, 0.5);
        stubReply(OK_JSON);
    }

    private void stubReply(String json) {
        TextBlock text = TextBlock.builder().text(json).citations(List.of()).build();
        Message message = mock(Message.class);
        when(message.content()).thenReturn(List.of(ContentBlock.ofText(text)));
        when(client.createMessage(any(MessageCreateParams.class))).thenReturn(message);
    }

    private static BriefingRegion regionWithLowCloud(String name, int low) {
        BriefingSlot slot = new BriefingSlot("Loc", LocalDateTime.of(2026, 4, 10, 18, 30), Verdict.GO,
                weather(low), BriefingSlot.TideInfo.NONE, List.of(), null);
        return new BriefingRegion(name, Verdict.GO, "Summary", List.of(), List.of(slot),
                10.0, 8.0, 3.5, 0, null, null);
    }

    private static List<BriefingDay> days(BriefingRegion... regions) {
        BriefingEventSummary es = new BriefingEventSummary(TargetType.SUNSET, List.of(regions), List.of());
        return List.of(new BriefingDay(LocalDate.of(2026, 4, 10), List.of(es)));
    }

    private static BriefingRegion first(List<BriefingDay> days) {
        return days.getFirst().eventSummaries().getFirst().regions().getFirst();
    }

    private void calls(int expected) {
        verify(client, times(expected)).createMessage(any(MessageCreateParams.class));
    }

    @Test
    @DisplayName("An identical second build makes no Haiku call and returns the same gloss")
    void identicalSecondBuild_makesNoCalls() {
        reuseService.generateGlosses(days(regionWithLowCloud("A", 10), regionWithLowCloud("B", 20)), 1L);
        calls(2);

        List<BriefingDay> second = reuseService.generateGlosses(
                days(regionWithLowCloud("A", 10), regionWithLowCloud("B", 20)), 2L);

        calls(2);
        assertThat(first(second).glossHeadline()).isEqualTo("High cirrus");
        assertThat(first(second).glossDetail()).isEqualTo("Canvas.");
    }

    @Test
    @DisplayName("A changed rollup for one region makes exactly one new call")
    void oneChangedRegion_makesOneCall() {
        reuseService.generateGlosses(days(regionWithLowCloud("A", 10), regionWithLowCloud("B", 20)), 1L);
        calls(2);

        reuseService.generateGlosses(days(regionWithLowCloud("A", 10), regionWithLowCloud("B", 55)), 2L);

        calls(3);
    }

    @Test
    @DisplayName("A different model invalidates every stored gloss")
    void changedModel_invalidates() {
        reuseService.generateGlosses(days(regionWithLowCloud("A", 10)), 1L);
        when(models.getActiveModel(RunType.BRIEFING_GLOSS)).thenReturn(EvaluationModel.SONNET_55);

        reuseService.generateGlosses(days(regionWithLowCloud("A", 10)), 2L);

        calls(2);
    }

    @Test
    @DisplayName("The reuse key covers the system prompt, the gloss version, the model and the input")
    void reuseKey_coversEveryPart() {
        String base = BriefingGlossService.reuseKey("msg", EvaluationModel.HAIKU);

        assertThat(BriefingGlossService.reuseKey("msg", EvaluationModel.HAIKU)).isEqualTo(base);
        assertThat(BriefingGlossService.reuseKey("msg2", EvaluationModel.HAIKU)).isNotEqualTo(base);
        assertThat(BriefingGlossService.reuseKey("msg", EvaluationModel.SONNET_55)).isNotEqualTo(base);
        assertThat(BriefingGlossService.reuseKey("msg", EvaluationModel.HAIKU, "other prompt",
                BriefingGlossService.GLOSS_VERSION)).isNotEqualTo(base);
        assertThat(BriefingGlossService.reuseKey("msg", EvaluationModel.HAIKU,
                BriefingGlossService.systemPrompt(), "other version")).isNotEqualTo(base);
        assertThat(BriefingGlossService.reuseKey("msg", EvaluationModel.HAIKU,
                BriefingGlossService.systemPrompt(), BriefingGlossService.GLOSS_VERSION)).isEqualTo(base);
    }

    @Test
    @DisplayName("A failed call is not stored, so the next build retries it")
    void failedCall_isRetriedNextBuild() {
        when(client.createMessage(any(MessageCreateParams.class))).thenThrow(new IllegalStateException("boom"));
        List<BriefingDay> failed = reuseService.generateGlosses(days(regionWithLowCloud("A", 10)), 1L);
        assertThat(first(failed).glossHeadline()).isNull();
        calls(1);

        stubReply(OK_JSON);
        List<BriefingDay> retried = reuseService.generateGlosses(days(regionWithLowCloud("A", 10)), 2L);

        calls(2);
        assertThat(first(retried).glossHeadline()).isEqualTo("High cirrus");
    }

    @Test
    @DisplayName("A gloss missing its detail is not stored, so the next build retries it")
    void incompleteGloss_isRetriedNextBuild() {
        stubReply("{\"headline\": \"High cirrus\"}");
        reuseService.generateGlosses(days(regionWithLowCloud("A", 10)), 1L);

        reuseService.generateGlosses(days(regionWithLowCloud("A", 10)), 2L);

        calls(2);
    }

    @Test
    @DisplayName("The stored map is replaced each build: a region that left the briefing is forgotten")
    void map_doesNotGrowAcrossBuilds() {
        reuseService.generateGlosses(days(regionWithLowCloud("A", 10), regionWithLowCloud("B", 20)), 1L);
        assertThat(reuseService.storedGlossCount()).isEqualTo(2);

        reuseService.generateGlosses(days(regionWithLowCloud("A", 10)), 2L);
        assertThat(reuseService.storedGlossCount()).isEqualTo(1);

        // B was dropped from the map, so it is a miss when it comes back.
        reuseService.generateGlosses(days(regionWithLowCloud("A", 10), regionWithLowCloud("B", 20)), 3L);
        calls(3);
        assertThat(reuseService.storedGlossCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("A new service (a restart) starts cold and glosses every region")
    void restart_startsCold() {
        reuseService.generateGlosses(days(regionWithLowCloud("A", 10)), 1L);
        BriefingGlossService restarted = new BriefingGlossService(client, new ObjectMapper(),
                mock(JobRunService.class), models, 0.5);

        restarted.generateGlosses(days(regionWithLowCloud("A", 10)), 2L);

        calls(2);
    }

    @Test
    @DisplayName("A STANDDOWN region is glossed and its gloss reused like any other")
    void standdownRegion_isGlossedAndReused() {
        BriefingSlot slot = new BriefingSlot("Loc", LocalDateTime.of(2026, 4, 10, 18, 30), Verdict.STANDDOWN,
                weather(90), BriefingSlot.TideInfo.NONE, List.of(), "Heavy rain");
        BriefingRegion standdown = new BriefingRegion("S", Verdict.STANDDOWN, "Summary", List.of(),
                List.of(slot), 10.0, 8.0, 3.5, 0, null, null);

        reuseService.generateGlosses(days(standdown), 1L);
        List<BriefingDay> second = reuseService.generateGlosses(days(standdown), 2L);

        calls(1);
        assertThat(first(second).glossHeadline()).isEqualTo("High cirrus");
    }
}
