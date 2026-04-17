package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.RegionGroup;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.service.ModelSelectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generates Claude-authored one-line glosses for bluebell region groups.
 *
 * <p>After the bluebell strategy builds per-region data, this service enriches each
 * region group with a short (~8 word) headline explaining the key photography factor.
 * Calls are made in parallel using virtual threads with a concurrency cap. Each call
 * is individually error-handled — a failed gloss never blocks the briefing.
 */
@Service
public class BluebellGlossService {

    private static final Logger LOG = LoggerFactory.getLogger(BluebellGlossService.class);

    /** Maximum response tokens for the headline-only JSON response. */
    private static final int MAX_TOKENS = 128;

    /** Maximum concurrent Haiku calls. */
    private static final int MAX_CONCURRENCY = 10;

    /** Maximum words in the gloss headline. */
    private static final int MAX_HEADLINE_WORDS = 8;

    static final String SYSTEM_PROMPT = """
            You are a bluebell photography forecast assistant. Given scoring data for a \
            region's bluebell locations, respond with a JSON object:
            {"headline": "..."}
            HEADLINE rules:
            - 8 words maximum. Count every word before responding.
            - Write a COMPLETE thought that makes sense on its own.
            - Focus on conditions: mist, light, wind, shelter, aspect.
            - Never end with a preposition or conjunction.
            - No punctuation at the end.
            Good: "Misty dawn — ideal woodland light" (5 words).
            Bad: "Great conditions for shooting bluebells at" (ends with "at").
            Respond with ONLY a JSON object. No markdown, no code fences, no preamble.""";

    private final AnthropicApiClient anthropicApiClient;
    private final ObjectMapper objectMapper;
    private final ModelSelectionService modelSelectionService;
    private final ParallelGlossExecutor<GlossWorkItem> glossExecutor =
            new ParallelGlossExecutor<>(MAX_CONCURRENCY, "Bluebell gloss");

    /**
     * Constructs a {@code BluebellGlossService}.
     *
     * @param anthropicApiClient    resilient Anthropic API client
     * @param objectMapper          Jackson mapper for JSON building
     * @param modelSelectionService service for resolving the active Claude model
     */
    public BluebellGlossService(AnthropicApiClient anthropicApiClient,
            ObjectMapper objectMapper,
            ModelSelectionService modelSelectionService) {
        this.anthropicApiClient = anthropicApiClient;
        this.objectMapper = objectMapper;
        this.modelSelectionService = modelSelectionService;
    }

    /**
     * Enriches BLUEBELL topics with Claude-generated glosses on each region group.
     *
     * <p>Returns a new list with gloss-enriched BLUEBELL topics. Non-BLUEBELL topics are
     * returned unmodified. Any failure is caught — the briefing always completes.
     *
     * @param topics the hot topics list from the aggregator
     * @return enriched topics (new instances for BLUEBELL since records are immutable)
     */
    public List<HotTopic> enrichGlosses(List<HotTopic> topics) {
        try {
            return doEnrichGlosses(topics);
        } catch (Exception e) {
            LOG.warn("Bluebell gloss generation failed globally — returning topics without glosses: {}",
                    e.getMessage());
            return topics;
        }
    }

    private List<HotTopic> doEnrichGlosses(List<HotTopic> topics) {
        EvaluationModel model = modelSelectionService.getActiveModel(RunType.BLUEBELL_GLOSS);

        List<GlossWorkItem> workItems = collectWorkItems(topics);
        if (workItems.isEmpty()) {
            LOG.debug("No BLUEBELL topics with region groups to gloss");
            return topics;
        }

        glossExecutor.execute(workItems, item -> callGloss(item, model));

        return reassemble(topics, workItems);
    }

    /**
     * Collects one work item per BLUEBELL region group that needs a gloss.
     */
    private List<GlossWorkItem> collectWorkItems(List<HotTopic> topics) {
        List<GlossWorkItem> items = new ArrayList<>();
        for (int topicIdx = 0; topicIdx < topics.size(); topicIdx++) {
            HotTopic topic = topics.get(topicIdx);
            if (!"BLUEBELL".equals(topic.type()) || topic.expandedDetail() == null
                    || topic.expandedDetail().regionGroups() == null) {
                continue;
            }
            List<RegionGroup> groups = topic.expandedDetail().regionGroups();
            for (int regionIdx = 0; regionIdx < groups.size(); regionIdx++) {
                items.add(new GlossWorkItem(topicIdx, regionIdx, groups.get(regionIdx)));
            }
        }
        return items;
    }

    /**
     * Makes a single Claude call for one region and stores the headline on the work item.
     */
    private void callGloss(GlossWorkItem item, EvaluationModel model) {
        try {
            String userMessage = buildUserMessage(item.regionGroup);

            Message response = anthropicApiClient.createMessage(
                    MessageCreateParams.builder()
                            .model(model.getModelId())
                            .maxTokens(MAX_TOKENS)
                            .systemOfTextBlockParams(List.of(
                                    TextBlockParam.builder().text(SYSTEM_PROMPT).build()))
                            .addUserMessage(userMessage)
                            .build());

            String raw = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(ContentBlock::asText)
                    .map(TextBlock::text)
                    .findFirst()
                    .orElse("");

            parseGlossResponse(item, raw.strip());
        } catch (Exception e) {
            LOG.warn("Bluebell gloss failed for {}: {}", item.regionGroup.regionName(),
                    e.getMessage());
        }
    }

    /**
     * Builds a compact JSON user message for a single bluebell region gloss call.
     */
    String buildUserMessage(RegionGroup regionGroup) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("regionName", regionGroup.regionName());

            if (regionGroup.locations() != null && !regionGroup.locations().isEmpty()) {
                int bestScore = regionGroup.locations().stream()
                        .filter(l -> l.bluebellLocationMetrics() != null)
                        .mapToInt(l -> l.bluebellLocationMetrics().score())
                        .max()
                        .orElse(0);
                double avgScore = regionGroup.locations().stream()
                        .filter(l -> l.bluebellLocationMetrics() != null)
                        .mapToInt(l -> l.bluebellLocationMetrics().score())
                        .average()
                        .orElse(0);

                node.put("bestScore", bestScore);
                node.put("avgScore", Math.round(avgScore * 10.0) / 10.0);
                node.put("locationCount", regionGroup.locations().size());

                // Derive exposure mix
                Set<String> exposures = regionGroup.locations().stream()
                        .filter(l -> l.bluebellLocationMetrics() != null
                                && l.bluebellLocationMetrics().exposure() != null)
                        .map(l -> l.bluebellLocationMetrics().exposure())
                        .collect(Collectors.toSet());
                String exposureMix = exposures.size() > 1 ? "MIXED"
                        : exposures.stream().findFirst().orElse("UNKNOWN");
                node.put("exposureMix", exposureMix);

                // Best location summary
                regionGroup.locations().stream()
                        .filter(l -> l.bluebellLocationMetrics() != null
                                && l.bluebellLocationMetrics().summary() != null)
                        .findFirst()
                        .ifPresent(l -> {
                            node.put("bestLocationSummary",
                                    l.bluebellLocationMetrics().summary());
                            node.put("bestLocationExposure",
                                    l.bluebellLocationMetrics().exposure());
                        });
            }

            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to build bluebell gloss user message", e);
        }
    }

    /**
     * Parses a JSON gloss response into a headline. Falls back to truncating
     * the raw text if JSON parsing fails.
     */
    private void parseGlossResponse(GlossWorkItem item, String raw) {
        try {
            String cleaned = PromptUtils.stripCodeFences(raw);
            JsonNode node = objectMapper.readTree(cleaned);
            if (node.has("headline")) {
                item.glossHeadline = BriefingGlossService.truncateToWords(
                        node.get("headline").asText(), MAX_HEADLINE_WORDS);
            }
        } catch (Exception e) {
            LOG.debug("Bluebell gloss JSON parse failed, falling back to truncation: {}",
                    e.getMessage());
            item.glossHeadline = BriefingGlossService.truncateToWords(
                    raw, MAX_HEADLINE_WORDS);
        }
    }

    /**
     * Reassembles topics with gloss-enriched region groups (records are immutable).
     */
    private List<HotTopic> reassemble(List<HotTopic> topics, List<GlossWorkItem> workItems) {
        // Index work items by topic
        List<HotTopic> result = new ArrayList<>(topics);

        // Group work items by topicIdx
        var byTopic = workItems.stream()
                .collect(java.util.stream.Collectors.groupingBy(w -> w.topicIdx));

        for (var entry : byTopic.entrySet()) {
            int topicIdx = entry.getKey();
            HotTopic original = topics.get(topicIdx);
            ExpandedHotTopicDetail detail = original.expandedDetail();
            if (detail == null || detail.regionGroups() == null) {
                continue;
            }

            List<RegionGroup> enrichedGroups = new ArrayList<>(detail.regionGroups());
            for (GlossWorkItem item : entry.getValue()) {
                if (item.glossHeadline != null) {
                    RegionGroup old = enrichedGroups.get(item.regionIdx);
                    enrichedGroups.set(item.regionIdx, new RegionGroup(
                            old.regionName(), item.glossHeadline,
                            old.glossDetail(), old.verdict(), old.locations()));
                }
            }

            ExpandedHotTopicDetail enrichedDetail = new ExpandedHotTopicDetail(
                    enrichedGroups, detail.bluebellMetrics(), detail.tideMetrics());

            result.set(topicIdx, new HotTopic(
                    original.type(), original.label(), original.detail(),
                    original.date(), original.priority(), original.filterAction(),
                    original.regions(), original.description(), enrichedDetail));
        }

        return result;
    }

    /**
     * Mutable work item linking a region group to its indices and result.
     */
    static class GlossWorkItem {
        final int topicIdx;
        final int regionIdx;
        final RegionGroup regionGroup;
        volatile String glossHeadline;

        GlossWorkItem(int topicIdx, int regionIdx, RegionGroup regionGroup) {
            this.topicIdx = topicIdx;
            this.regionIdx = regionIdx;
            this.regionGroup = regionGroup;
        }
    }
}
