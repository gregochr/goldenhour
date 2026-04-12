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
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates Claude-authored one-line glosses for GO/MARGINAL briefing regions.
 *
 * <p>After the briefing triage builds the day → event → region hierarchy, this service
 * enriches each GO/MARGINAL region with a short (~10 word) explanation of the key reason
 * for its verdict. STANDDOWN regions are skipped (gloss left null).
 *
 * <p>Calls are made in parallel using virtual threads with a concurrency cap. Each call
 * is individually error-handled — a failed gloss never blocks the briefing.
 */
@Service
public class BriefingGlossService {

    private static final Logger LOG = LoggerFactory.getLogger(BriefingGlossService.class);

    /** Maximum response tokens — headline + detail JSON needs more than a single phrase. */
    private static final int MAX_TOKENS = 256;

    /** Maximum concurrent Haiku calls. */
    private static final int MAX_CONCURRENCY = 10;

    private static final String SYSTEM_PROMPT = """
            You are a photography forecast assistant. Given weather and tide data for a \
            region at a solar event, respond with a JSON object containing two fields:
            {"headline": "...", "detail": "..."}
            HEADLINE rules:
            - 7 words maximum. Count every word before responding.
            - Write a COMPLETE thought that makes sense on its own.
            - Do NOT start a sentence you cannot finish in 7 words.
            - If your first attempt is too long, rewrite from scratch — do not truncate a longer sentence.
            - No punctuation at the end.
            - Never end with a preposition or conjunction (for, and, but, with, of, to, at, by).
            Good: "Clear sky — no canvas for colour", "High cloud canvas, clear horizon", \
            "Clear skies limit colour potential".
            Bad: "Clear skies offer no cloud canvas for" (ends with "for"), \
            "High cloud with good canvas potential at" (ends with "at").
            DETAIL: 2-3 sentences expanding on the headline. Mention specific cloud layers, \
            percentages, trends, and any tide/coastal factors. Keep it factual and concise.
            CRITICAL RULE: If clearAllLayers is true, BOTH headline and detail MUST be \
            cautionary — clear skies mean no cloud canvas to catch colour. Never describe \
            clear-all-layers conditions as good, promising, or colourful.
            Respond with ONLY a JSON object. No markdown, no code fences, no preamble, \
            no trailing text. The response must start with { and end with }.
            Example: {"headline": "High cirrus canvas — colour potential", \
            "detail": "40% high cloud provides a canvas for colour at sunset. \
            Low cloud is minimal at 15%, keeping the horizon clear. \
            No tide alignment but conditions favour warm tones."}""";

    private final AnthropicApiClient anthropicApiClient;
    private final ObjectMapper objectMapper;
    private final JobRunService jobRunService;
    private final ModelSelectionService modelSelectionService;
    private final ParallelGlossExecutor<GlossWorkItem> glossExecutor =
            new ParallelGlossExecutor<>(MAX_CONCURRENCY, "Gloss");

    /**
     * Constructs a {@code BriefingGlossService}.
     *
     * @param anthropicApiClient    resilient Anthropic API client
     * @param objectMapper          Jackson mapper for JSON building
     * @param jobRunService         service for logging API calls
     * @param modelSelectionService service for resolving the active Claude model
     */
    public BriefingGlossService(AnthropicApiClient anthropicApiClient,
            ObjectMapper objectMapper, JobRunService jobRunService,
            ModelSelectionService modelSelectionService) {
        this.anthropicApiClient = anthropicApiClient;
        this.objectMapper = objectMapper;
        this.jobRunService = jobRunService;
        this.modelSelectionService = modelSelectionService;
    }

    /**
     * Enriches each GO/MARGINAL region in the briefing hierarchy with a Claude-generated gloss.
     *
     * <p>Returns a new list of days with gloss-populated regions. STANDDOWN regions retain
     * null gloss. Any failure (per-call or global) is caught — the briefing always completes.
     *
     * @param days     the fully assembled briefing days (triage complete)
     * @param jobRunId the current briefing job run ID for API call logging
     * @return enriched days (new instances since records are immutable)
     */
    public List<BriefingDay> generateGlosses(List<BriefingDay> days, Long jobRunId) {
        try {
            return doGenerateGlosses(days, jobRunId);
        } catch (Exception e) {
            LOG.warn("Gloss generation failed globally — returning days without glosses: {}",
                    e.getMessage());
            return days;
        }
    }

    private List<BriefingDay> doGenerateGlosses(List<BriefingDay> days, Long jobRunId) {
        EvaluationModel model = modelSelectionService.getActiveModel(RunType.BRIEFING_GLOSS);

        // Collect work items
        List<GlossWorkItem> workItems = collectWorkItems(days);
        if (workItems.isEmpty()) {
            LOG.debug("No GO/MARGINAL regions to gloss");
            return days;
        }

        glossExecutor.execute(workItems, item -> callGloss(item, model, jobRunId));

        return reassemble(days, workItems);
    }

    /**
     * Walks the hierarchy and collects one work item per GO/MARGINAL region.
     */
    private List<GlossWorkItem> collectWorkItems(List<BriefingDay> days) {
        List<GlossWorkItem> items = new ArrayList<>();
        for (int di = 0; di < days.size(); di++) {
            BriefingDay day = days.get(di);
            for (int ei = 0; ei < day.eventSummaries().size(); ei++) {
                BriefingEventSummary es = day.eventSummaries().get(ei);
                for (int ri = 0; ri < es.regions().size(); ri++) {
                    BriefingRegion region = es.regions().get(ri);
                    if (region.verdict() == Verdict.GO || region.verdict() == Verdict.MARGINAL) {
                        items.add(new GlossWorkItem(di, ei, ri, day, es, region));
                    }
                }
            }
        }
        return items;
    }

    /**
     * Makes a single Haiku call for one region and stores the result on the work item.
     */
    private void callGloss(GlossWorkItem item, EvaluationModel model, Long jobRunId) {
        long callStart = System.currentTimeMillis();
        String userMessage = null;
        try {
            userMessage = buildUserMessage(item);

            Message response = anthropicApiClient.createMessage(
                    MessageCreateParams.builder()
                            .model(model.getModelId())
                            .maxTokens(MAX_TOKENS)
                            .systemOfTextBlockParams(List.of(
                                    TextBlockParam.builder().text(SYSTEM_PROMPT).build()))
                            .addUserMessage(userMessage)
                            .build());

            long durationMs = System.currentTimeMillis() - callStart;
            String raw = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(ContentBlock::asText)
                    .map(TextBlock::text)
                    .findFirst()
                    .orElse("");

            parseGlossResponse(item, raw.strip());
            jobRunService.logApiCall(jobRunId, ServiceName.ANTHROPIC,
                    "POST", "briefing-gloss", null,
                    durationMs, 200, raw, true, null, model);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - callStart;
            LOG.warn("Gloss failed for {} {} {}: {}",
                    item.region.regionName(), item.day.date(), item.eventSummary.targetType(),
                    e.getMessage());
            jobRunService.logApiCall(jobRunId, ServiceName.ANTHROPIC,
                    "POST", "briefing-gloss", userMessage,
                    durationMs, null, null, false, e.getMessage(), model);
        }
    }

    /**
     * Builds a compact JSON user message for a single region×event gloss call.
     */
    String buildUserMessage(GlossWorkItem item) {
        BriefingRegion region = item.region;
        List<BriefingSlot> nonStanddown = region.slots().stream()
                .filter(s -> s.verdict() != Verdict.STANDDOWN)
                .toList();
        List<BriefingSlot> goSlots = region.slots().stream()
                .filter(s -> s.verdict() == Verdict.GO)
                .toList();
        List<BriefingSlot> medianSource = goSlots.isEmpty() ? nonStanddown : goSlots;

        int cloudLow = median(medianSource.stream()
                .mapToInt(s -> s.weather().lowCloudPercent()).sorted().toArray());
        int cloudMid = median(medianSource.stream()
                .mapToInt(s -> s.weather().midCloudPercent()).sorted().toArray());
        int cloudHigh = median(medianSource.stream()
                .mapToInt(s -> s.weather().highCloudPercent()).sorted().toArray());

        boolean clearAllLayers = region.slots().stream()
                .anyMatch(s -> s.flags().contains("Clear all layers"));
        boolean buildingTrend = region.slots().stream()
                .anyMatch(s -> s.flags().contains("Cloud building"));

        long tideAlignedCount = region.slots().stream()
                .filter(s -> s.tide().tideAligned()).count();
        boolean hasKingTide = region.slots().stream().anyMatch(s -> s.tide().isKingTide());
        boolean hasSpringTide = region.slots().stream().anyMatch(s -> s.tide().isSpringTide());
        long coastalCount = region.slots().stream()
                .filter(s -> s.tide().tideState() != null).count();

        long goCount = region.slots().stream().filter(s -> s.verdict() == Verdict.GO).count();
        long marginalCount = region.slots().stream()
                .filter(s -> s.verdict() == Verdict.MARGINAL).count();

        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("region", region.regionName());
            node.put("event", item.eventSummary.targetType().name().toLowerCase());
            node.put("date", item.day.date().toString());
            node.put("verdict", region.verdict().name());
            node.put("cloudLow", cloudLow);
            node.put("cloudMid", cloudMid);
            node.put("cloudHigh", cloudHigh);
            node.put("clearAllLayers", clearAllLayers);
            node.put("buildingTrend", buildingTrend);
            node.put("tideAlignedCount", tideAlignedCount);
            node.put("hasKingTide", hasKingTide);
            node.put("hasSpringTide", hasSpringTide);
            node.put("coastalLocationCount", coastalCount);
            node.put("goCount", goCount);
            node.put("marginalCount", marginalCount);
            node.put("totalLocations", region.slots().size());
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build gloss user message", e);
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
                item.glossHeadline = truncateToWords(node.get("headline").asText(), 7);
            }
            if (node.has("detail")) {
                item.glossDetail = node.get("detail").asText();
            }
        } catch (Exception e) {
            LOG.debug("Gloss JSON parse failed, falling back to truncation: {}", e.getMessage());
            item.glossHeadline = truncateToWords(raw, 7);
        }
    }

    /**
     * Reassembles the hierarchy with gloss-enriched regions (records are immutable).
     */
    private List<BriefingDay> reassemble(List<BriefingDay> days, List<GlossWorkItem> workItems) {
        // Index glosses by (dayIdx, eventIdx, regionIdx)
        GlossWorkItem[][][] glossIndex = new GlossWorkItem[days.size()][][];
        for (int di = 0; di < days.size(); di++) {
            BriefingDay day = days.get(di);
            glossIndex[di] = new GlossWorkItem[day.eventSummaries().size()][];
            for (int ei = 0; ei < day.eventSummaries().size(); ei++) {
                glossIndex[di][ei] =
                        new GlossWorkItem[day.eventSummaries().get(ei).regions().size()];
            }
        }
        for (GlossWorkItem item : workItems) {
            glossIndex[item.dayIdx][item.eventIdx][item.regionIdx] = item;
        }

        List<BriefingDay> enriched = new ArrayList<>();
        for (int di = 0; di < days.size(); di++) {
            BriefingDay day = days.get(di);
            List<BriefingEventSummary> newEvents = new ArrayList<>();
            for (int ei = 0; ei < day.eventSummaries().size(); ei++) {
                BriefingEventSummary es = day.eventSummaries().get(ei);
                List<BriefingRegion> newRegions = new ArrayList<>();
                for (int ri = 0; ri < es.regions().size(); ri++) {
                    BriefingRegion r = es.regions().get(ri);
                    GlossWorkItem item = glossIndex[di][ei][ri];
                    String headline = item != null ? item.glossHeadline : null;
                    String detail = item != null ? item.glossDetail : null;
                    newRegions.add(new BriefingRegion(
                            r.regionName(), r.verdict(), r.summary(), r.tideHighlights(),
                            r.slots(), r.regionTemperatureCelsius(),
                            r.regionApparentTemperatureCelsius(), r.regionWindSpeedMs(),
                            r.regionWeatherCode(), headline, detail));
                }
                newEvents.add(new BriefingEventSummary(es.targetType(), newRegions, es.unregioned()));
            }
            enriched.add(new BriefingDay(day.date(), newEvents));
        }
        return enriched;
    }

    /**
     * Returns the median of a sorted int array, or 0 if empty.
     */
    static int median(int[] sorted) {
        return PromptUtils.median(sorted);
    }

    /**
     * Truncates the given text to at most {@code maxWords} words.
     *
     * @param text     the text to truncate
     * @param maxWords maximum number of words
     * @return truncated text, or the original if already within the limit
     */
    static String truncateToWords(String text, int maxWords) {
        return PromptUtils.truncateToWords(text, maxWords);
    }

    /**
     * Mutable work item linking a region to its position in the hierarchy and its result.
     */
    static class GlossWorkItem {
        final int dayIdx;
        final int eventIdx;
        final int regionIdx;
        final BriefingDay day;
        final BriefingEventSummary eventSummary;
        final BriefingRegion region;
        volatile String glossHeadline;
        volatile String glossDetail;

        GlossWorkItem(int dayIdx, int eventIdx, int regionIdx,
                BriefingDay day, BriefingEventSummary eventSummary, BriefingRegion region) {
            this.dayIdx = dayIdx;
            this.eventIdx = eventIdx;
            this.regionIdx = regionIdx;
            this.day = day;
            this.eventSummary = eventSummary;
            this.region = region;
        }
    }
}
