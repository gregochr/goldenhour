package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Makes a single Haiku call after briefing triage completes to produce Claude-generated
 * "best bet" photography recommendations.
 *
 * <p>Input is region-level rollup data only (not per-location slot data), keeping the prompt
 * small (~1–2 KB). Any failure is caught and returns an empty list — the briefing always
 * loads and falls back to the mechanical headline.
 */
@Component
public class BriefingBestBetAdvisor {

    private static final Logger LOG = LoggerFactory.getLogger(BriefingBestBetAdvisor.class);

    /** Claude model used for best-bet advisory (Haiku — fast, cost-effective). */
    private static final String MODEL = EvaluationModel.HAIKU.getModelId();

    /** Maximum response tokens for the best-bet JSON. */
    private static final int MAX_TOKENS = 1024;

    private static final String SYSTEM_PROMPT = """
            You are a photography forecast advisor for PhotoCast, helping landscape photographers
            decide when and where to go for the best light.

            Given triage data for upcoming solar events and aurora conditions across regions,
            identify the two best photographic opportunities in the next 3 days.

            **How to pick the two recommendations:**

            Pick 1 — THE BEST OVERALL. The single best opportunity across all regions and
            events, regardless of how far away it is. If the best light is 90 minutes away,
            say so. This is "if you could go anywhere."

            Pick 2 — THE BEST WITHIN AN HOUR. The best opportunity where
            nearestLocationDriveMinutes is 60 or less. This is the pragmatic option —\s
            "what's good that I can actually get to on a normal evening."

            If Pick 1 is already within an hour (nearestLocationDriveMinutes <= 60), then
            Pick 2 should be the next best region or event that is different from Pick 1.
            The user should always get two meaningfully different options.

            **Label them clearly:**
            - Pick 1 headline should reflect it's the best overall
            - Pick 2 headline should mention proximity when relevant — "close to home",
              "under an hour", "quick evening dash"

            **When evaluating quality, consider:**
            - GO regions with the most clear locations are generally best
            - Tide alignment is a strong differentiator. A GO region with tide-aligned
              coastal locations ranks above an equally clear inland-only region.
              Matching tides add foreground drama — mention the tide when it's a factor.
            - King tides at coastal locations are rare and highly valuable
            - Spring tides are notable but less rare than king tides
            - Aurora events (MODERATE or above with clear dark-sky locations) are rare and exciting
            - Lower wind speeds are better for long exposures and reflections
            - Comfort matters — extreme cold or high wind reduces the appeal
            - If multiple events are close in quality, prefer the sooner one
            - Mention drive time in the detail text — "25 minutes from home" or
              "90-minute drive but the king tide makes it worth it"
            - If everything is STANDDOWN, say so honestly. Don't oversell marginal conditions.
              Be human — tell the photographer to stay home, charge their batteries,
              maybe edit last weekend's shots. A bit of humour is fine.

            Respond with a JSON object:
            {
              "picks": [
                {
                  "rank": 1,
                  "headline": "One sentence, 15 words max, punchy — what to do",
                  "detail": "2 sentences max, 40 words max. Mention region, key conditions, drive time.",
                  "event": "tomorrow_sunset",
                  "region": "Northumberland",
                  "confidence": "high|medium|low"
                },
                {
                  "rank": 2,
                  "headline": "One sentence, 15 words max — the closer/alternative option",
                  "detail": "2 sentences max, 40 words max. Mention region, conditions, drive time.",
                  "event": "today_sunset",
                  "region": "The North York Moors",
                  "confidence": "high|medium|low"
                }
              ]
            }

            If conditions only support one good pick, return a single item in the array.
            If everything is STANDDOWN, return a single pick with event and region as null.
            Return only valid JSON — no code fences, no markdown.

            Never include raw data field names, codes, or technical identifiers in your response.
            Translate all data into natural language:
            - weatherCode values → "clear skies", "partly cloudy", "overcast", "light rain", "fog" etc.
            - windSpeedMs → describe as "calm", "light wind", "breezy", or convert to mph (multiply by 2.24)
            - Do not write "weatherCode 0" or "windSpeedMs 3.5" — write "clear skies" or "8mph wind"
            """;

    private final AnthropicApiClient anthropicApiClient;
    private final ObjectMapper objectMapper;
    private final JobRunService jobRunService;
    private final AuroraStateCache auroraStateCache;

    /**
     * Constructs a {@code BriefingBestBetAdvisor}.
     *
     * @param anthropicApiClient resilient Anthropic API client
     * @param objectMapper       Jackson mapper for JSON building and parsing
     * @param jobRunService      service for logging the API call in job run metrics
     * @param auroraStateCache   read-only access to the current aurora alert state
     */
    public BriefingBestBetAdvisor(AnthropicApiClient anthropicApiClient,
            ObjectMapper objectMapper, JobRunService jobRunService,
            AuroraStateCache auroraStateCache) {
        this.anthropicApiClient = anthropicApiClient;
        this.objectMapper = objectMapper;
        this.jobRunService = jobRunService;
        this.auroraStateCache = auroraStateCache;
    }

    /**
     * Produces Claude-generated best-bet picks from the post-triage region rollup data.
     *
     * <p>Any failure (network error, timeout, parse failure) returns an empty list so the
     * briefing always loads — the frontend falls back to the mechanical headline.
     *
     * @param days      the fully assembled briefing days (triage complete)
     * @param jobRunId  the current briefing job run ID for API call logging
     * @param driveMap  map of location name to drive duration minutes (may be empty)
     * @return list of best-bet picks (1–2 items normally; empty on failure)
     */
    public List<BestBet> advise(List<BriefingDay> days, Long jobRunId,
            Map<String, Integer> driveMap) {
        try {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            String rollupJson = buildRollupJson(days, now, driveMap);
            long startMs = System.currentTimeMillis();

            Message response = anthropicApiClient.createMessage(
                    MessageCreateParams.builder()
                            .model(MODEL)
                            .maxTokens(MAX_TOKENS)
                            .systemOfTextBlockParams(List.of(
                                    TextBlockParam.builder().text(SYSTEM_PROMPT).build()))
                            .addUserMessage(rollupJson)
                            .build());

            long durationMs = System.currentTimeMillis() - startMs;
            String raw = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(ContentBlock::asText)
                    .map(TextBlock::text)
                    .findFirst()
                    .orElse("");

            LOG.info("Best-bet advisor completed ({}ms)", durationMs);
            jobRunService.logApiCall(jobRunId, ServiceName.ANTHROPIC,
                    "POST", "briefing-best-bet", null,
                    durationMs, 200, raw, true, null,
                    EvaluationModel.HAIKU, null, null);

            return enrichWithDriveTimes(parseBestBets(raw), days, driveMap);
        } catch (Exception e) {
            LOG.warn("Best-bet advisor failed — returning empty picks (fallback to headline)", e);
            return List.of();
        }
    }

    /**
     * Enriches parsed picks with the nearest drive time for each pick's region,
     * looking up from the days hierarchy and driveMap.
     */
    private List<BestBet> enrichWithDriveTimes(List<BestBet> picks,
            List<BriefingDay> days, Map<String, Integer> driveMap) {
        if (driveMap.isEmpty()) {
            return picks;
        }
        return picks.stream().map(pick -> {
            if (pick.region() == null) {
                return pick;
            }
            Integer nearest = nearestDriveForRegion(pick.region(), days, driveMap);
            return new BestBet(pick.rank(), pick.headline(), pick.detail(),
                    pick.event(), pick.region(), pick.confidence(), nearest);
        }).toList();
    }

    /**
     * Returns the nearest drive time in minutes for any location in the named region,
     * or null if no drive data is available for that region.
     */
    private Integer nearestDriveForRegion(String regionName, List<BriefingDay> days,
            Map<String, Integer> driveMap) {
        return days.stream()
                .flatMap(d -> d.eventSummaries().stream())
                .flatMap(es -> es.regions().stream())
                .filter(r -> regionName.equals(r.regionName()))
                .flatMap(r -> r.slots().stream())
                .map(s -> driveMap.get(s.locationName()))
                .filter(Objects::nonNull)
                .min(Integer::compareTo)
                .orElse(null);
    }

    /**
     * Builds the region-level rollup JSON sent to Claude as the user message.
     * Past solar events are excluded. Aurora data is included if an active alert is present.
     *
     * @param days     the briefing days
     * @param now      current UTC time for past-event filtering
     * @param driveMap map of location name to drive duration minutes (may be empty)
     * @return compact JSON string
     * @throws JsonProcessingException if Jackson serialization fails
     */
    String buildRollupJson(List<BriefingDay> days, LocalDateTime now,
            Map<String, Integer> driveMap) throws JsonProcessingException {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("currentTime", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        ArrayNode eventsNode = root.putArray("events");

        for (BriefingDay day : days) {
            String dayLabel = day.date().equals(today) ? "today" : "tomorrow";
            for (BriefingEventSummary es : day.eventSummaries()) {
                if (day.date().equals(today) && isEventPast(es, now)) {
                    continue;
                }
                ObjectNode eventNode = eventsNode.addObject();
                eventNode.put("event", dayLabel + "_" + es.targetType().name().toLowerCase());
                eventNode.put("date", day.date().toString());
                String eventTime = getEventTimeStr(es);
                if (eventTime != null) {
                    eventNode.put("eventTime", eventTime);
                }
                ArrayNode regionsNode = eventNode.putArray("regions");
                for (BriefingRegion region : es.regions()) {
                    appendRegionNode(regionsNode, region, driveMap);
                }
            }
        }

        if (auroraStateCache.isActive()
                && auroraStateCache.getCurrentLevel() != null
                && auroraStateCache.getCurrentLevel().isAlertWorthy()) {
            appendAuroraEvent(eventsNode);
        }

        return objectMapper.writeValueAsString(root);
    }

    private void appendRegionNode(ArrayNode regionsNode, BriefingRegion region,
            Map<String, Integer> driveMap) {
        long goCount = region.slots().stream()
                .filter(s -> s.verdict() == Verdict.GO).count();
        long marginalCount = region.slots().stream()
                .filter(s -> s.verdict() == Verdict.MARGINAL).count();
        long standdownCount = region.slots().stream()
                .filter(s -> s.verdict() == Verdict.STANDDOWN).count();
        long tideAlignedCount = region.slots().stream()
                .filter(BriefingSlot::tideAligned).count();
        long coastalCount = region.slots().stream()
                .filter(s -> s.tideState() != null).count();
        List<String> kingTideLocations = region.slots().stream()
                .filter(BriefingSlot::isKingTide)
                .map(BriefingSlot::locationName)
                .toList();
        boolean hasSpringTide = region.slots().stream().anyMatch(BriefingSlot::isSpringTide);

        ObjectNode regionNode = regionsNode.addObject();
        regionNode.put("name", region.regionName());
        regionNode.put("verdict", region.verdict().name());
        regionNode.put("goCount", goCount);
        regionNode.put("marginalCount", marginalCount);
        regionNode.put("standdownCount", standdownCount);
        regionNode.put("totalLocations", region.slots().size());
        if (region.regionTemperatureCelsius() != null) {
            regionNode.put("temperatureCelsius", region.regionTemperatureCelsius());
        }
        if (region.regionApparentTemperatureCelsius() != null) {
            regionNode.put("apparentTemperatureCelsius", region.regionApparentTemperatureCelsius());
        }
        if (region.regionWindSpeedMs() != null) {
            regionNode.put("windSpeedMs", region.regionWindSpeedMs());
        }
        if (region.regionWeatherCode() != null) {
            regionNode.put("weatherCode", region.regionWeatherCode());
        }
        regionNode.put("tideAlignedCount", tideAlignedCount);
        regionNode.put("hasKingTide", !kingTideLocations.isEmpty());
        if (!kingTideLocations.isEmpty()) {
            ArrayNode kingTideLocs = regionNode.putArray("kingTideLocations");
            kingTideLocations.forEach(kingTideLocs::add);
        }
        regionNode.put("hasSpringTide", hasSpringTide);
        regionNode.put("coastalLocationCount", coastalCount);
        regionNode.put("inlandLocationCount", region.slots().size() - coastalCount);
        List<Integer> driveTimes = region.slots().stream()
                .map(s -> driveMap.get(s.locationName()))
                .filter(Objects::nonNull)
                .toList();
        if (!driveTimes.isEmpty()) {
            int avg = (int) Math.round(
                    driveTimes.stream().mapToInt(Integer::intValue).average().orElse(0));
            int nearest = driveTimes.stream().mapToInt(Integer::intValue).min().orElse(0);
            regionNode.put("averageDriveMinutes", avg);
            regionNode.put("nearestLocationDriveMinutes", nearest);
        }
    }

    private void appendAuroraEvent(ArrayNode eventsNode) {
        ObjectNode auroraNode = eventsNode.addObject();
        auroraNode.put("event", "aurora_tonight");
        auroraNode.put("alertLevel", auroraStateCache.getCurrentLevel().name());
        Double kp = auroraStateCache.getLastTriggerKp();
        if (kp != null) {
            auroraNode.put("kp", kp);
        }
        auroraNode.put("clearLocationCount", auroraStateCache.getCachedScores().size());
    }

    /**
     * Returns {@code true} if all solar event slots for this event summary have already passed.
     */
    private boolean isEventPast(BriefingEventSummary es, LocalDateTime now) {
        return es.regions().stream()
                .flatMap(r -> r.slots().stream())
                .filter(s -> s.solarEventTime() != null)
                .findFirst()
                .map(BriefingSlot::solarEventTime)
                .map(t -> t.isBefore(now))
                .orElse(false);
    }

    /**
     * Returns the HH:mm event time from the first slot with a non-null solarEventTime, or null.
     */
    private String getEventTimeStr(BriefingEventSummary es) {
        return es.regions().stream()
                .flatMap(r -> r.slots().stream())
                .filter(s -> s.solarEventTime() != null)
                .findFirst()
                .map(s -> s.solarEventTime().format(DateTimeFormatter.ofPattern("HH:mm")))
                .orElse(null);
    }

    /**
     * Parses the Claude JSON response into a list of {@link BestBet} records.
     *
     * @param raw the raw Claude response text
     * @return parsed picks, or empty list if parsing fails
     */
    List<BestBet> parseBestBets(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            String cleaned = raw.strip();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned
                        .replaceFirst("^```[a-zA-Z]*\\n?", "")
                        .replaceFirst("```\\s*$", "")
                        .strip();
            }
            JsonNode root = objectMapper.readTree(cleaned);
            JsonNode picksNode = root.get("picks");
            if (picksNode == null || !picksNode.isArray() || picksNode.isEmpty()) {
                LOG.warn("Best-bet response missing or empty 'picks' array");
                return List.of();
            }
            List<BestBet> picks = new ArrayList<>();
            for (JsonNode pick : picksNode) {
                int rank = pick.path("rank").asInt(1);
                String headline = pick.path("headline").asText(null);
                String detail = pick.path("detail").asText(null);
                String event = pick.path("event").isNull()
                        ? null : pick.path("event").asText(null);
                String region = pick.path("region").isNull()
                        ? null : pick.path("region").asText(null);
                String confidence = pick.path("confidence").asText("medium");
                picks.add(new BestBet(rank, headline, detail, event, region, confidence, null));
            }
            LOG.info("Best-bet advisor returned {} pick(s)", picks.size());
            return List.copyOf(picks);
        } catch (Exception e) {
            LOG.warn("Failed to parse best-bet response — returning empty", e);
            return List.of();
        }
    }
}
