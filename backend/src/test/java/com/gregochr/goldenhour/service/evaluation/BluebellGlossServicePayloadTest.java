package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.BluebellLocationMetrics;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.BluebellMetrics;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.LocationEntry;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.RegionGroup;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.service.ModelSelectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the user message {@link BluebellGlossService} sends Claude for a region and how the
 * headlines are folded back into the right topic and region slot.
 */
@ExtendWith(MockitoExtension.class)
class BluebellGlossServicePayloadTest {

    private static final LocalDate DATE = LocalDate.of(2026, 4, 25);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private AnthropicApiClient anthropicApiClient;

    @Mock
    private ModelSelectionService modelSelectionService;

    private BluebellGlossService glossService;

    @BeforeEach
    void setUp() {
        glossService = new BluebellGlossService(anthropicApiClient, MAPPER, modelSelectionService);
    }

    private static LocationEntry loc(String name, BluebellLocationMetrics metrics) {
        return new LocationEntry(name, "Woodland", null, metrics, null);
    }

    private static RegionGroup group(String name, LocationEntry... locations) {
        return new RegionGroup(name, null, "detail kept", "GO", List.of(locations));
    }

    private JsonNode userMessage(RegionGroup group) throws Exception {
        return MAPPER.readTree(glossService.buildUserMessage(group));
    }

    private static HotTopic bluebell(String type, RegionGroup... groups) {
        return new HotTopic(type, "Bluebell conditions", "Misty today", DATE, 3, "BLUEBELL",
                List.of("R"), "desc",
                new ExpandedHotTopicDetail(List.of(groups), new BluebellMetrics(4, "Good", 1), null));
    }

    private static String userContent(MessageCreateParams p) {
        return p.messages().get(0).content().asString();
    }

    @Test
    @DisplayName("user message carries region, best and average score, scale, location count")
    void userMessage_scoresAndCounts() throws Exception {
        JsonNode n = userMessage(group("Lake District",
                loc("A", new BluebellLocationMetrics(5, "WOODLAND", "Misty")),
                loc("B", new BluebellLocationMetrics(2, "WOODLAND", "Windy")),
                loc("C", new BluebellLocationMetrics(2, "WOODLAND", "Dull"))));

        assertThat(n.get("regionName").asText()).isEqualTo("Lake District");
        assertThat(n.get("bestScore").asInt()).isEqualTo(5);
        assertThat(n.get("avgScore").asDouble()).isEqualTo(3.0);
        assertThat(n.get("scoreScale").asText()).isEqualTo("1-5");
        assertThat(n.get("locationCount").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("average score is rounded to one decimal place")
    void userMessage_avgRoundedToOneDecimal() throws Exception {
        // (5 + 4 + 4) / 3 = 4.333...
        JsonNode n = userMessage(group("R",
                loc("A", new BluebellLocationMetrics(5, "WOODLAND", "s")),
                loc("B", new BluebellLocationMetrics(4, "WOODLAND", "s")),
                loc("C", new BluebellLocationMetrics(4, "WOODLAND", "s"))));

        assertThat(n.get("avgScore").asDouble()).isEqualTo(4.3);
    }

    @Test
    @DisplayName("locations without metrics are ignored by the scores but counted in locationCount")
    void userMessage_missingMetricsIgnoredForScores() throws Exception {
        JsonNode n = userMessage(group("R",
                loc("A", new BluebellLocationMetrics(3, "OPEN", "Clear")),
                loc("B", null)));

        assertThat(n.get("bestScore").asInt()).isEqualTo(3);
        assertThat(n.get("avgScore").asDouble()).isEqualTo(3.0);
        assertThat(n.get("locationCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("no scored location gives a best and average score of zero")
    void userMessage_noMetricsAtAll() throws Exception {
        JsonNode n = userMessage(group("R", loc("A", null)));

        assertThat(n.get("bestScore").asInt()).isZero();
        assertThat(n.get("avgScore").asDouble()).isZero();
        assertThat(n.get("exposureMix").asText()).isEqualTo("UNKNOWN");
        assertThat(n.has("bestLocationSummary")).isFalse();
        assertThat(n.has("bestLocationExposure")).isFalse();
    }

    @Test
    @DisplayName("exposure mix is the single shared exposure, MIXED when they differ, UNKNOWN when none")
    void userMessage_exposureMix() throws Exception {
        JsonNode same = userMessage(group("R",
                loc("A", new BluebellLocationMetrics(3, "WOODLAND", "s")),
                loc("B", new BluebellLocationMetrics(4, "WOODLAND", "s"))));
        JsonNode mixed = userMessage(group("R",
                loc("A", new BluebellLocationMetrics(3, "WOODLAND", "s")),
                loc("B", new BluebellLocationMetrics(4, "OPEN", "s"))));
        JsonNode none = userMessage(group("R",
                loc("A", new BluebellLocationMetrics(3, null, "s"))));

        assertThat(same.get("exposureMix").asText()).isEqualTo("WOODLAND");
        assertThat(mixed.get("exposureMix").asText()).isEqualTo("MIXED");
        assertThat(none.get("exposureMix").asText()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("best location summary and exposure come from the first location that has a summary")
    void userMessage_bestLocationSummary() throws Exception {
        JsonNode n = userMessage(group("R",
                loc("NoMetrics", null),
                loc("NoSummary", new BluebellLocationMetrics(5, "OPEN", null)),
                loc("First", new BluebellLocationMetrics(3, "WOODLAND", "Still and misty")),
                loc("Second", new BluebellLocationMetrics(4, "OPEN", "Other"))));

        assertThat(n.get("bestLocationSummary").asText()).isEqualTo("Still and misty");
        assertThat(n.get("bestLocationExposure").asText()).isEqualTo("WOODLAND");
    }

    @Test
    @DisplayName("a region with no locations sends only the region name")
    void userMessage_noLocations() throws Exception {
        JsonNode empty = userMessage(new RegionGroup("Empty", null, null, null, List.of()));
        JsonNode nullList = userMessage(new RegionGroup("Null", null, null, null, null));

        assertThat(empty.size()).isEqualTo(1);
        assertThat(empty.get("regionName").asText()).isEqualTo("Empty");
        assertThat(nullList.size()).isEqualTo(1);
        assertThat(nullList.get("regionName").asText()).isEqualTo("Null");
    }

    @Test
    @DisplayName("the request sent to Claude carries the region's user message and the system prompt")
    void request_carriesUserMessageAndSystemPrompt() {
        when(modelSelectionService.getActiveModel(RunType.BLUEBELL_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(ModelRequestAssertions.message(
                        List.of(ModelRequestAssertions.text("{\"headline\": \"Calm\"}")),
                        StopReason.END_TURN));
        RegionGroup g = group("Northumberland",
                loc("A", new BluebellLocationMetrics(4, "WOODLAND", "Misty")));

        glossService.enrichGlosses(List.of(bluebell("BLUEBELL", g)));

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());
        assertThat(userContent(captor.getValue())).isEqualTo(glossService.buildUserMessage(g));
        assertThat(captor.getValue().system().get().asTextBlockParams().get(0).text())
                .isEqualTo(BluebellGlossService.SYSTEM_PROMPT);
    }

    @Test
    @DisplayName("each headline lands on its own region of its own topic; other topics, groups and "
            + "fields are untouched")
    void reassemble_headlinesLandOnTheRightSlots() {
        when(modelSelectionService.getActiveModel(RunType.BLUEBELL_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenAnswer(inv -> {
                    String content = userContent(inv.getArgument(0));
                    String headline = content.contains("Alpha") ? "Alpha headline"
                            : content.contains("Beta") ? "Beta headline" : "Gamma headline";
                    return ModelRequestAssertions.message(List.of(ModelRequestAssertions.text(
                            "{\"headline\": \"" + headline + "\"}")), StopReason.END_TURN);
                });
        HotTopic aurora = new HotTopic("AURORA", "Aurora", "Kp 5", DATE, 1, null,
                List.of("North"), "desc", null);
        HotTopic first = bluebell("BLUEBELL", group("Alpha"), group("Beta"));
        HotTopic second = bluebell("BLUEBELL", group("Gamma"));

        List<HotTopic> result = glossService.enrichGlosses(List.of(aurora, first, second));

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isSameAs(aurora);
        List<RegionGroup> firstGroups = result.get(1).expandedDetail().regionGroups();
        assertThat(firstGroups).extracting(RegionGroup::regionName).containsExactly("Alpha", "Beta");
        assertThat(firstGroups).extracting(RegionGroup::glossHeadline)
                .containsExactly("Alpha headline", "Beta headline");
        assertThat(firstGroups).extracting(RegionGroup::glossDetail)
                .containsOnly("detail kept");
        assertThat(firstGroups).extracting(RegionGroup::verdict).containsOnly("GO");
        assertThat(result.get(2).expandedDetail().regionGroups().get(0).glossHeadline())
                .isEqualTo("Gamma headline");
        assertThat(result.get(1).expandedDetail().bluebellMetrics())
                .isEqualTo(first.expandedDetail().bluebellMetrics());
        assertThat(result.get(1).type()).isEqualTo("BLUEBELL");
        assertThat(result.get(1).label()).isEqualTo(first.label());
    }

    @Test
    @DisplayName("a region whose call fails keeps its null headline while its sibling is glossed")
    void reassemble_failedRegionStaysNull() {
        when(modelSelectionService.getActiveModel(RunType.BLUEBELL_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenAnswer(inv -> {
                    if (userContent(inv.getArgument(0)).contains("Broken")) {
                        throw new RuntimeException("boom");
                    }
                    return ModelRequestAssertions.message(List.of(ModelRequestAssertions.text(
                            "{\"headline\": \"Fine\"}")), StopReason.END_TURN);
                });

        List<HotTopic> result = glossService.enrichGlosses(
                List.of(bluebell("BLUEBELL", group("Broken"), group("Healthy"))));

        List<RegionGroup> groups = result.get(0).expandedDetail().regionGroups();
        assertThat(groups.get(0).glossHeadline()).isNull();
        assertThat(groups.get(1).glossHeadline()).isEqualTo("Fine");
    }

    @Test
    @DisplayName("a non-JSON reply falls back to the raw text truncated to 8 words")
    void parse_nonJsonFallsBackToTruncation() {
        when(modelSelectionService.getActiveModel(RunType.BLUEBELL_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(ModelRequestAssertions.message(List.of(ModelRequestAssertions.text(
                        "  one two three four five six seven eight nine ten  ")),
                        StopReason.END_TURN));

        List<HotTopic> result = glossService.enrichGlosses(
                List.of(bluebell("BLUEBELL", group("R"))));

        assertThat(result.get(0).expandedDetail().regionGroups().get(0).glossHeadline())
                .isEqualTo(BriefingGlossService.truncateToWords(
                        "one two three four five six seven eight nine ten", 8));
        assertThat(result.get(0).expandedDetail().regionGroups().get(0).glossHeadline())
                .startsWith("one two three four five six seven eight").doesNotContain("nine");
    }

    @Test
    @DisplayName("a JSON reply without a headline key leaves the region unglossed")
    void parse_jsonWithoutHeadline() {
        when(modelSelectionService.getActiveModel(RunType.BLUEBELL_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(ModelRequestAssertions.message(List.of(ModelRequestAssertions.text(
                        "{\"other\": \"x\"}")), StopReason.END_TURN));

        List<HotTopic> result = glossService.enrichGlosses(
                List.of(bluebell("BLUEBELL", group("R"))));

        assertThat(result.get(0).expandedDetail().regionGroups().get(0).glossHeadline()).isNull();
    }

    @Test
    @DisplayName("a headline wrapped in code fences is still parsed")
    void parse_codeFencedJson() {
        when(modelSelectionService.getActiveModel(RunType.BLUEBELL_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(ModelRequestAssertions.message(List.of(ModelRequestAssertions.text(
                        "```json\n{\"headline\": \"Still air\"}\n```")), StopReason.END_TURN));

        List<HotTopic> result = glossService.enrichGlosses(
                List.of(bluebell("BLUEBELL", group("R"))));

        assertThat(result.get(0).expandedDetail().regionGroups().get(0).glossHeadline())
                .isEqualTo("Still air");
    }

    @Test
    @DisplayName("BLUEBELL topics without expanded detail or region groups are not glossed")
    void collect_skipsBluebellWithoutGroups() {
        when(modelSelectionService.getActiveModel(RunType.BLUEBELL_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        HotTopic noDetail = new HotTopic("BLUEBELL", "t", "d", DATE, 1, "BLUEBELL",
                List.of("R"), "desc", null);
        HotTopic nullGroups = new HotTopic("BLUEBELL", "t", "d", DATE, 1, "BLUEBELL",
                List.of("R"), "desc", new ExpandedHotTopicDetail(null, null, null));
        List<HotTopic> topics = List.of(noDetail, nullGroups);

        List<HotTopic> result = glossService.enrichGlosses(topics);

        assertThat(result).isSameAs(topics);
        verify(anthropicApiClient, never()).createMessage(any(MessageCreateParams.class));
    }

    @Test
    @DisplayName("a non-BLUEBELL topic with region groups is not glossed, even beside a BLUEBELL one")
    void collect_typeMustBeBluebell() {
        when(modelSelectionService.getActiveModel(RunType.BLUEBELL_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(ModelRequestAssertions.message(List.of(ModelRequestAssertions.text(
                        "{\"headline\": \"Calm\"}")), StopReason.END_TURN));
        HotTopic other = bluebell("KING_TIDE", group("Other"));
        HotTopic real = bluebell("BLUEBELL", group("Real"));

        List<HotTopic> result = glossService.enrichGlosses(List.of(other, real));

        assertThat(result.get(0)).isSameAs(other);
        assertThat(result.get(1).expandedDetail().regionGroups().get(0).glossHeadline())
                .isEqualTo("Calm");
        verify(anthropicApiClient).createMessage(any(MessageCreateParams.class));
    }

    @Test
    @DisplayName("a refused reply is never turned into a headline, even if it carries text")
    void refusalWithText_isNotGlossed() {
        when(modelSelectionService.getActiveModel(RunType.BLUEBELL_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(ModelRequestAssertions.message(List.of(ModelRequestAssertions.text(
                        "{\"headline\": \"Should not appear\"}")), StopReason.REFUSAL));

        List<HotTopic> result = glossService.enrichGlosses(
                List.of(bluebell("BLUEBELL", group("R"))));

        assertThat(result.get(0).expandedDetail().regionGroups().get(0).glossHeadline()).isNull();
    }
}
