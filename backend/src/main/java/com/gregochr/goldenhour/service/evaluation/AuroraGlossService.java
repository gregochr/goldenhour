package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.AuroraRegionSummary;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.solarutils.LunarPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** Maximum response tokens — an 8-word gloss needs far fewer. */
    private static final int MAX_TOKENS = 64;

    /** Maximum concurrent Haiku calls. */
    private static final int MAX_CONCURRENCY = 10;

    static final String SYSTEM_PROMPT = """
            You are an aurora photography forecast assistant. Given space weather and \
            location data for an aurora-eligible region, write a single plain-English \
            phrase explaining the key factor for aurora viewing conditions.
            STRICT LIMIT: 8 words maximum. Count every word. If your line exceeds \
            8 words, rewrite it shorter. Never exceed 8 words under any circumstances.
            CRITICAL RULE: If moonAboveHorizon is true and moonIlluminationPct > 60, \
            the gloss MUST be cautionary about moonlight washing out the aurora. \
            Use phrases like "Bright moon — aurora washed out", \
            "Strong moonlight limits visibility", "Gibbous moon competes with aurora".
            No quotes, no punctuation other than what the phrase needs. \
            Examples: "Strong Kp — excellent aurora potential", \
            "Clear skies at dark Bortle 2 site", \
            "Overcast — aurora obscured by cloud".""";

    private final AnthropicApiClient anthropicApiClient;
    private final ObjectMapper objectMapper;
    private final ModelSelectionService modelSelectionService;

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
     * @param moon       lunar position at tonight's midpoint, or {@code null}
     * @param alertLevel current aurora alert level
     * @param kp         Kp index that triggered the alert, or {@code null}
     * @return enriched regions (new instances since records are immutable)
     */
    public List<AuroraRegionSummary> enrichGlosses(List<AuroraRegionSummary> regions,
            LunarPosition moon, AlertLevel alertLevel, Double kp) {
        try {
            return doEnrichGlosses(regions, moon, alertLevel, kp);
        } catch (Exception e) {
            LOG.warn("Aurora gloss generation failed globally — returning regions without glosses: {}",
                    e.getMessage());
            return regions;
        }
    }

    private List<AuroraRegionSummary> doEnrichGlosses(List<AuroraRegionSummary> regions,
            LunarPosition moon, AlertLevel alertLevel, Double kp) {
        EvaluationModel model = modelSelectionService.getActiveModel(RunType.AURORA_GLOSS);

        List<GlossWorkItem> workItems = collectWorkItems(regions);
        if (workItems.isEmpty()) {
            LOG.debug("No GO aurora regions to gloss");
            return regions;
        }

        long startMs = System.currentTimeMillis();
        Semaphore semaphore = new Semaphore(MAX_CONCURRENCY);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        List<CompletableFuture<Void>> futures = workItems.stream()
                .map(item -> CompletableFuture.runAsync(() -> {
                    try {
                        semaphore.acquire();
                        try {
                            callGloss(item, model, moon, alertLevel, kp);
                            succeeded.incrementAndGet();
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failed.incrementAndGet();
                    }
                }))
                .toList();

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        long totalMs = System.currentTimeMillis() - startMs;
        LOG.info("Aurora gloss complete: {}/{} succeeded, {} failed ({}ms)",
                succeeded.get(), workItems.size(), failed.get(), totalMs);

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
            LunarPosition moon, AlertLevel alertLevel, Double kp) {
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

            item.gloss = raw.strip();
        } catch (Exception e) {
            LOG.warn("Aurora gloss failed for {}: {}", item.region.regionName(), e.getMessage());
        }
    }

    /**
     * Builds a compact JSON user message for a single aurora region gloss call.
     */
    String buildUserMessage(AuroraRegionSummary region, LunarPosition moon,
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
                node.put("moonIlluminationPct", Math.round(moon.illuminationPercent()));
                node.put("moonAboveHorizon", moon.isAboveHorizon());
                node.put("moonPhase", moon.phase().name());
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build aurora gloss user message", e);
        }
    }

    /**
     * Reassembles regions with gloss-enriched entries (records are immutable).
     */
    private List<AuroraRegionSummary> reassemble(List<AuroraRegionSummary> regions,
            List<GlossWorkItem> workItems) {
        String[] glossIndex = new String[regions.size()];
        for (GlossWorkItem item : workItems) {
            glossIndex[item.regionIdx] = item.gloss;
        }

        List<AuroraRegionSummary> enriched = new ArrayList<>();
        for (int i = 0; i < regions.size(); i++) {
            AuroraRegionSummary r = regions.get(i);
            enriched.add(new AuroraRegionSummary(
                    r.regionName(), r.verdict(), r.clearLocationCount(),
                    r.totalDarkSkyLocations(), r.bestBortleClass(), r.locations(),
                    r.regionTemperatureCelsius(), r.regionWindSpeedMs(),
                    r.regionWeatherCode(), glossIndex[i]));
        }
        return enriched;
    }

    /**
     * Mutable work item linking a region to its index and result.
     */
    static class GlossWorkItem {
        final int regionIdx;
        final AuroraRegionSummary region;
        volatile String gloss;

        GlossWorkItem(int regionIdx, AuroraRegionSummary region) {
            this.regionIdx = regionIdx;
            this.region = region;
        }
    }
}
