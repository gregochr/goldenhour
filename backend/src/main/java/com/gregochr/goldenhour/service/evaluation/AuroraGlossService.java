package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.AuroraRegionSummary;
import com.gregochr.goldenhour.model.MoonTransitionData;
import com.gregochr.goldenhour.service.ModelSelectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates Claude-authored one-line glosses for GO aurora regions.
 *
 * <p>After the aurora summary builds per-region data, this service enriches each GO
 * region with a short (~8 word) explanation of the key aurora viewing factor.
 * STANDDOWN regions are skipped (gloss left null).
 *
 * <p>Calls are made in parallel using virtual threads with a concurrency cap. Each call
 * is individually error-handled — a failed gloss never blocks the briefing.
 */
@Service
public class AuroraGlossService {

    private static final Logger LOG = LoggerFactory.getLogger(AuroraGlossService.class);

    /** Maximum response tokens — headline + detail JSON needs more than a single phrase. */
    private static final int MAX_TOKENS = 256;

    /** Maximum concurrent Haiku calls. */
    private static final int MAX_CONCURRENCY = 10;

    static final String SYSTEM_PROMPT = """
            You are an aurora photography forecast assistant. Given space weather and \
            location data for an aurora-eligible region, respond with a JSON object \
            containing two fields:
            {"headline": "...", "detail": "..."}
            HEADLINE rules:
            - 7 words maximum. Count every word before responding.
            - Write a COMPLETE thought that makes sense on its own.
            - Do NOT start a sentence you cannot finish in 7 words.
            - If your first attempt is too long, rewrite from scratch — do not truncate a longer sentence.
            - No punctuation at the end.
            - Never end with a preposition or conjunction (for, and, but, with, of, to, at, by).
            Good: "Strong Kp — excellent aurora potential" (5 words), "Clear skies, Kp rising" (4 words).
            Bad: "Strong Kp with excellent aurora viewing conditions at" (ends with "at").
            DETAIL: 2-3 sentences expanding on the headline. Mention Kp level, cloud \
            conditions, Bortle class, and any moon transition timing. Keep it factual.
            CRITICAL RULE: If windowQuality is MOONLIT_ALL_WINDOW and \
            moonIlluminationPct > 60, BOTH headline and detail MUST be cautionary \
            about moonlight. If windowQuality is DARK_THEN_MOONLIT, mention the \
            moonRiseTime in the detail. If windowQuality is MOONLIT_THEN_DARK, \
            mention when dark skies begin.
            Respond with ONLY a JSON object. No markdown, no code fences, no preamble, \
            no trailing text. The response must start with { and end with }.
            Example: {"headline": "Strong Kp — excellent aurora potential", \
            "detail": "Kp 6.3 with clear skies at Bortle 2 locations. \
            Moon below horizon all window — ideal dark-sky conditions. \
            Solar wind speed elevated at 580 km/s."}""";

    private final AnthropicApiClient anthropicApiClient;
    private final ObjectMapper objectMapper;
    private final ModelSelectionService modelSelectionService;
    private final ParallelGlossExecutor<GlossWorkItem> glossExecutor =
            new ParallelGlossExecutor<>(MAX_CONCURRENCY, "Aurora gloss");

    /**
     * Constructs an {@code AuroraGlossService}.
     *
     * @param anthropicApiClient    resilient Anthropic API client
     * @param objectMapper          Jackson mapper for JSON building
     * @param modelSelectionService service for resolving the active Claude model
     */
    public AuroraGlossService(AnthropicApiClient anthropicApiClient,
            ObjectMapper objectMapper,
            ModelSelectionService modelSelectionService) {
        this.anthropicApiClient = anthropicApiClient;
        this.objectMapper = objectMapper;
        this.modelSelectionService = modelSelectionService;
    }

    /**
     * Enriches each GO region with a Claude-generated gloss.
     *
     * <p>Returns a new list of regions with gloss-populated entries. STANDDOWN regions
     * retain null gloss. Any failure is caught — the briefing always completes.
     *
     * @param regions    aurora region summaries (tonight only)
     * @param moon       moon transition data for tonight's window, or {@code null}
     * @param alertLevel current aurora alert level
     * @param kp         Kp index that triggered the alert, or {@code null}
     * @return enriched regions (new instances since records are immutable)
     */
    public List<AuroraRegionSummary> enrichGlosses(List<AuroraRegionSummary> regions,
            MoonTransitionData moon, AlertLevel alertLevel, Double kp) {
        try {
            return doEnrichGlosses(regions, moon, alertLevel, kp);
        } catch (Exception e) {
            LOG.warn("Aurora gloss generation failed globally — returning regions without glosses: {}",
                    e.getMessage());
            return regions;
        }
    }

    private List<AuroraRegionSummary> doEnrichGlosses(List<AuroraRegionSummary> regions,
            MoonTransitionData moon, AlertLevel alertLevel, Double kp) {
        EvaluationModel model = modelSelectionService.getActiveModel(RunType.AURORA_GLOSS);

        List<GlossWorkItem> workItems = collectWorkItems(regions);
        if (workItems.isEmpty()) {
            LOG.debug("No GO aurora regions to gloss");
            return regions;
        }

        glossExecutor.execute(workItems, item -> callGloss(item, model, moon, alertLevel, kp));

        return reassemble(regions, workItems);
    }

    /**
     * Collects one work item per GO region.
     */
    private List<GlossWorkItem> collectWorkItems(List<AuroraRegionSummary> regions) {
        List<GlossWorkItem> items = new ArrayList<>();
        for (int i = 0; i < regions.size(); i++) {
            AuroraRegionSummary region = regions.get(i);
            if ("GO".equals(region.verdict())) {
                items.add(new GlossWorkItem(i, region));
            }
        }
        return items;
    }

    /**
     * Makes a single Haiku call for one region and stores the result on the work item.
     */
    private void callGloss(GlossWorkItem item, EvaluationModel model,
            MoonTransitionData moon, AlertLevel alertLevel, Double kp) {
        try {
            String userMessage = buildUserMessage(item.region, moon, alertLevel, kp);

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
            LOG.warn("Aurora gloss failed for {}: {}", item.region.regionName(), e.getMessage());
        }
    }

    /**
     * Builds a compact JSON user message for a single aurora region gloss call.
     */
    String buildUserMessage(AuroraRegionSummary region, MoonTransitionData moon,
            AlertLevel alertLevel, Double kp) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("regionName", region.regionName());
            node.put("verdict", region.verdict());
            node.put("alertLevel", alertLevel != null ? alertLevel.name() : "UNKNOWN");
            if (kp != null) {
                node.put("kp", kp);
            }
            node.put("clearLocationCount", region.clearLocationCount());
            node.put("totalDarkSkyLocations", region.totalDarkSkyLocations());
            if (region.bestBortleClass() != null) {
                node.put("bestBortleClass", region.bestBortleClass());
            }
            if (moon != null) {
                node.put("moonIlluminationPct", Math.round(moon.illuminationPct()));
                node.put("moonPhase", moon.phase().name());
                node.put("windowQuality", moon.windowQuality().name());
                if (moon.moonRiseTime() != null) {
                    node.put("moonRiseTime", moon.moonRiseTime());
                }
                if (moon.moonSetTime() != null) {
                    node.put("moonSetTime", moon.moonSetTime());
                }
                node.put("moonUpAtStart", moon.moonUpAtStart());
                node.put("moonUpAtEnd", moon.moonUpAtEnd());
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build aurora gloss user message", e);
        }
    }

    /**
     * Parses a JSON gloss response into headline + detail. Falls back to truncating
     * the raw text as headline if JSON parsing fails.
     */
    private void parseGlossResponse(GlossWorkItem item, String raw) {
        try {
            String cleaned = PromptUtils.stripCodeFences(raw);
            JsonNode node = objectMapper.readTree(cleaned);
            if (node.has("headline")) {
                item.glossHeadline = BriefingGlossService.truncateToWords(
                        node.get("headline").asText(), 7);
            }
            if (node.has("detail")) {
                item.glossDetail = node.get("detail").asText();
            }
        } catch (Exception e) {
            LOG.debug("Aurora gloss JSON parse failed, falling back to truncation: {}",
                    e.getMessage());
            item.glossHeadline = BriefingGlossService.truncateToWords(raw, 7);
        }
    }

    /**
     * Reassembles regions with gloss-enriched entries (records are immutable).
     */
    private List<AuroraRegionSummary> reassemble(List<AuroraRegionSummary> regions,
            List<GlossWorkItem> workItems) {
        GlossWorkItem[] glossIndex = new GlossWorkItem[regions.size()];
        for (GlossWorkItem item : workItems) {
            glossIndex[item.regionIdx] = item;
        }

        List<AuroraRegionSummary> enriched = new ArrayList<>();
        for (int i = 0; i < regions.size(); i++) {
            AuroraRegionSummary r = regions.get(i);
            GlossWorkItem item = glossIndex[i];
            String headline = item != null ? item.glossHeadline : null;
            String detail = item != null ? item.glossDetail : null;
            enriched.add(new AuroraRegionSummary(
                    r.regionName(), r.verdict(), r.clearLocationCount(),
                    r.totalDarkSkyLocations(), r.bestBortleClass(), r.locations(),
                    r.regionTemperatureCelsius(), r.regionWindSpeedMs(),
                    r.regionWeatherCode(), headline, detail));
        }
        return enriched;
    }

    /**
     * Mutable work item linking a region to its index and result.
     */
    static class GlossWorkItem {
        final int regionIdx;
        final AuroraRegionSummary region;
        volatile String glossHeadline;
        volatile String glossDetail;

        GlossWorkItem(int regionIdx, AuroraRegionSummary region) {
            this.regionIdx = regionIdx;
            this.region = region;
        }
    }
}
