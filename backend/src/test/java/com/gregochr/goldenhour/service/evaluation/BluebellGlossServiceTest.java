package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BluebellGlossService}.
 */
@ExtendWith(MockitoExtension.class)
class BluebellGlossServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 4, 25);

    @Mock
    private AnthropicApiClient anthropicApiClient;

    @Mock
    private ModelSelectionService modelSelectionService;

    private BluebellGlossService glossService;

    @BeforeEach
    void setUp() {
        glossService = new BluebellGlossService(
                anthropicApiClient, new ObjectMapper(), modelSelectionService);
    }

    @Test
    @DisplayName("enriches BLUEBELL topics with gloss on regionGroups")
    void enrichGlosses_bluebellTopic_glossPopulated() {
        when(modelSelectionService.getActiveModel(RunType.BLUEBELL_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        mockClaudeResponse("{\"headline\": \"Misty dawn — ideal woodland light\"}");

        List<HotTopic> topics = List.of(bluebellTopic("Northumberland"));

        List<HotTopic> result = glossService.enrichGlosses(topics);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).expandedDetail().regionGroups().get(0).glossHeadline())
                .isEqualTo("Misty dawn — ideal woodland light");
    }

    @Test
    @DisplayName("skips non-BLUEBELL topics — no Claude calls")
    void enrichGlosses_nonBluebellTopic_skipped() {
        when(modelSelectionService.getActiveModel(RunType.BLUEBELL_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);

        HotTopic auroraTopic = new HotTopic(
                "AURORA", "Aurora possible", "Kp 5 tonight", DATE,
                1, null, List.of("Northumberland"), "desc", null);

        List<HotTopic> result = glossService.enrichGlosses(List.of(auroraTopic));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo("AURORA");
        verify(anthropicApiClient, never()).createMessage(any(MessageCreateParams.class));
    }

    @Test
    @DisplayName("gloss headline truncated to 8 words")
    void enrichGlosses_longHeadline_truncatedTo8Words() {
        when(modelSelectionService.getActiveModel(RunType.BLUEBELL_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        mockClaudeResponse("{\"headline\": \"one two three four five six seven eight nine ten\"}");

        List<HotTopic> result = glossService.enrichGlosses(
                List.of(bluebellTopic("Lake District")));

        String headline = result.get(0).expandedDetail()
                .regionGroups().get(0).glossHeadline();
        assertThat(headline.split("\\s+")).hasSizeLessThanOrEqualTo(8);
    }

    @Test
    @DisplayName("returns original list on global failure")
    void enrichGlosses_globalFailure_returnsOriginal() {
        when(modelSelectionService.getActiveModel(RunType.BLUEBELL_GLOSS))
                .thenThrow(new RuntimeException("DB down"));

        List<HotTopic> original = List.of(bluebellTopic("Northumberland"));
        List<HotTopic> result = glossService.enrichGlosses(original);

        assertThat(result).isSameAs(original);
    }

    @Test
    @DisplayName("per-region failure does not block other regions")
    void enrichGlosses_perRegionFailure_otherRegionsStillGlossed() {
        when(modelSelectionService.getActiveModel(RunType.BLUEBELL_GLOSS))
                .thenReturn(EvaluationModel.HAIKU);
        // First call throws, second succeeds
        Message successMsg = buildMockMessage("{\"headline\": \"Still air for woodland\"}");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenThrow(new RuntimeException("API error"))
                .thenReturn(successMsg);

        // Topic with 2 region groups
        HotTopic topic = bluebellTopicWithTwoRegions();

        List<HotTopic> result = glossService.enrichGlosses(List.of(topic));

        assertThat(result).hasSize(1);
        var groups = result.get(0).expandedDetail().regionGroups();
        // Both regions were called — at least one should have a gloss
        long glossedCount = groups.stream()
                .filter(g -> g.glossHeadline() != null).count();
        assertThat(glossedCount).as("at least one region should be glossed").isGreaterThanOrEqualTo(1);

        verify(anthropicApiClient, times(2)).createMessage(any(MessageCreateParams.class));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private HotTopic bluebellTopic(String regionName) {
        var locMetrics = new BluebellLocationMetrics(7, "WOODLAND", "Misty and still");
        var locEntry = new LocationEntry("Rannerdale Knotts", "Woodland", "Best",
                locMetrics, null);
        var regionGroup = new RegionGroup(regionName, null, null, null, List.of(locEntry));
        var detail = new ExpandedHotTopicDetail(
                List.of(regionGroup),
                new BluebellMetrics(7, "Good", 1), null);

        return new HotTopic("BLUEBELL", "Bluebell conditions", "Misty today",
                DATE, 3, "BLUEBELL", List.of(regionName), "desc", detail);
    }

    private HotTopic bluebellTopicWithTwoRegions() {
        var loc1 = new LocationEntry("Rannerdale", "Woodland", "Best",
                new BluebellLocationMetrics(8, "WOODLAND", "Misty"), null);
        var loc2 = new LocationEntry("Allen Banks", "Woodland", null,
                new BluebellLocationMetrics(6, "WOODLAND", "Light wind"), null);
        var group1 = new RegionGroup("Lake District", null, null, null, List.of(loc1));
        var group2 = new RegionGroup("Northumberland", null, null, null, List.of(loc2));
        var detail = new ExpandedHotTopicDetail(
                List.of(group1, group2),
                new BluebellMetrics(8, "Good", 2), null);

        return new HotTopic("BLUEBELL", "Bluebell conditions", "Good tomorrow",
                DATE, 1, "BLUEBELL",
                List.of("Lake District", "Northumberland"), "desc", detail);
    }

    private void mockClaudeResponse(String text) {
        Message response = buildMockMessage(text);
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(response);
    }

    private Message buildMockMessage(String text) {
        TextBlock textBlock = mock(TextBlock.class);
        when(textBlock.text()).thenReturn(text);
        ContentBlock block = mock(ContentBlock.class);
        when(block.isText()).thenReturn(true);
        when(block.asText()).thenReturn(textBlock);
        Message response = mock(Message.class);
        when(response.content()).thenReturn(List.of(block));
        return response;
    }
}
