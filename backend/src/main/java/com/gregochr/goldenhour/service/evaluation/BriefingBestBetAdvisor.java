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
import com.gregochr.goldenhour.model.Confidence;
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
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    /** All English day names, used for narrative date validation. */
    private static final List<String> ALL_DAY_NAMES = List.of(
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday");

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
                  "event": "<value from validEvents, e.g. 2026-03-30_sunset>",
                  "region": "<value from validRegions, e.g. Northumberland>",
                  "confidence": "high|medium|low"
                },
                {
                  "rank": 2,
                  "headline": "One sentence, 15 words max — the closer/alternative option",
                  "detail": "2 sentences max, 40 words max. Mention region, conditions, drive time.",
                  "event": "<value from validEvents>",
                  "region": "<value from validRegions>",
                  "confidence": "high|medium|low"
                }
              ]
            }

            If conditions only support one good pick, return a single item in the array.
            If everything is STANDDOWN, return a single pick with event and region as null.
            Return only valid JSON — no code fences, no markdown.

            CRITICAL CONSTRAINTS — violating any of these makes your response invalid:
            - Only recommend events present in the "validEvents" array. Never invent,
              extrapolate, or reference events not in the input.
            - Use the "dayName" field provided in each event — never calculate day of week.
            - Your "event" field in each pick MUST exactly match one of the "validEvents" values.
            - Your "region" field MUST exactly match one of the "validRegions" values.
            - Do not reference any date outside the "forecastWindow".

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
     * Carries the rollup JSON and derived validation sets out of {@link #buildRollupJson}.
     *
     * @param json          the compact JSON string sent to Claude as the user message
     * @param validEvents   all event identifiers present in the rollup (e.g. {@code "2026-03-30_sunset"})
     * @param validRegions  all region names present in the rollup
     * @param validDayNames day names (e.g. {@code "Monday"}) for all dates in the forecast window
     */
    record RollupResult(String json, Set<String> validEvents,
            Set<String> validRegions, Set<String> validDayNames) {
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
            RollupResult rollup = buildRollupJson(days, now, driveMap);
            long startMs = System.currentTimeMillis();

            Message response = anthropicApiClient.createMessage(
                    MessageCreateParams.builder()
                            .model(MODEL)
                            .maxTokens(MAX_TOKENS)
                            .systemOfTextBlockParams(List.of(
                                    TextBlockParam.builder().text(SYSTEM_PROMPT).build()))
                            .addUserMessage(rollup.json())
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

            List<BestBet> parsed = parseBestBets(raw);
            List<BestBet> validated = validateAndFilterPicks(
                    parsed, rollup.validEvents(), rollup.validRegions(), rollup.validDayNames());
            return enrichWithDriveTimes(validated, days, driveMap);
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
     * Validates picks against the known-good event IDs, region names, and day names,
     * discards any invalid picks, and re-ranks the survivors.
     *
     * <p>A pick is invalid if its {@code event} is not in {@code validEvents} (unless null),
     * its {@code region} is not in {@code validRegions} (unless null or aurora event), or
     * its narrative text references a day name outside the forecast window.
     * If all picks fail validation the list is empty and the caller falls back to the
     * mechanical headline.
     *
     * @param picks        parsed picks from Claude
     * @param validEvents  event IDs present in the rollup input
     * @param validRegions region names present in the rollup input
     * @param validDayNames day names present in the forecast window
     * @return validated, re-ranked list (may be empty)
     */
    List<BestBet> validateAndFilterPicks(List<BestBet> picks,
            Set<String> validEvents, Set<String> validRegions, Set<String> validDayNames) {
        List<BestBet> valid = new ArrayList<>();
        for (BestBet pick : picks) {
            if (isPickValid(pick, validEvents, validRegions, validDayNames)) {
                valid.add(pick);
            } else {
                LOG.warn("Best bet pick #{} failed validation — discarding", pick.rank());
            }
        }
        List<BestBet> reranked = new ArrayList<>();
        for (int i = 0; i < valid.size(); i++) {
            BestBet p = valid.get(i);
            reranked.add(new BestBet(i + 1, p.headline(), p.detail(),
                    p.event(), p.region(), p.confidence(), p.nearestDriveMinutes()));
        }
        if (valid.size() < picks.size()) {
            LOG.warn("Best bet validation: {}/{} picks passed", valid.size(), picks.size());
        }
        return List.copyOf(reranked);
    }

    private boolean isPickValid(BestBet pick, Set<String> validEvents,
            Set<String> validRegions, Set<String> validDayNames) {
        // Stay-home pick (both null) is always valid
        if (pick.event() == null && pick.region() == null) {
            return true;
        }
        if (pick.event() != null && !validEvents.contains(pick.event())) {
            LOG.warn("Best bet pick rejected: event '{}' not in validEvents", pick.event());
            return false;
        }
        // Aurora events may reference any region — skip region validation
        boolean isAurora = "aurora_tonight".equals(pick.event());
        if (!isAurora && pick.region() != null && !validRegions.contains(pick.region())) {
            LOG.warn("Best bet pick rejected: region '{}' not in validRegions", pick.region());
            return false;
        }
        String narrative = (pick.headline() == null ? "" : pick.headline())
                + " " + (pick.detail() == null ? "" : pick.detail());
        if (narrativeReferencesInvalidDayName(narrative, validDayNames)) {
            LOG.warn("Best bet pick #{} narrative references day outside forecast window", pick.rank());
            return false;
        }
        return true;
    }

    private boolean narrativeReferencesInvalidDayName(String text, Set<String> validDayNames) {
        if (validDayNames.isEmpty()) {
            return false;
        }
        for (String day : ALL_DAY_NAMES) {
            if (text.contains(day) && !validDayNames.contains(day)) {
                return true;
            }
        }
        return false;
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
     * Builds the region-level rollup JSON sent to Claude as the user message, plus the
     * validation sets derived from the same data.
     *
     * <p>Past solar events (today only) are excluded. Aurora data is appended when an
     * active alert is present. The returned {@link RollupResult} carries the compact JSON
     * and the sets of valid event IDs, region names, and day names needed for response
     * validation.
     *
     * @param days     the briefing days
     * @param now      current UTC time for past-event filtering
     * @param driveMap map of location name to drive duration minutes (may be empty)
     * @return rollup result containing the JSON and validation sets
     * @throws JsonProcessingException if Jackson serialization fails
     */
    RollupResult buildRollupJson(List<BriefingDay> days, LocalDateTime now,
            Map<String, Integer> driveMap) throws JsonProcessingException {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Set<String> validEvents = new LinkedHashSet<>();
        Set<String> validRegions = new LinkedHashSet<>();
        Set<String> validDayNames = new LinkedHashSet<>();
        Set<String> includedDates = new LinkedHashSet<>();

        ObjectNode root = objectMapper.createObjectNode();
        root.put("currentTime", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

        ArrayNode eventsNode = objectMapper.createArrayNode();
        for (BriefingDay day : days) {
            for (BriefingEventSummary es : day.eventSummaries()) {
                if (day.date().equals(today) && isEventPast(es, now)) {
                    continue;
                }
                String eventId = day.date().toString() + "_" + es.targetType().name().toLowerCase();
                String dayName = day.date().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                validEvents.add(eventId);
                validDayNames.add(dayName);
                includedDates.add(day.date().toString());

                ObjectNode eventNode = eventsNode.addObject();
                eventNode.put("event", eventId);
                eventNode.put("date", day.date().toString());
                eventNode.put("dayName", dayName);
                String eventTime = getEventTimeStr(es);
                if (eventTime != null) {
                    eventNode.put("eventTime", eventTime);
                }
                ArrayNode regionsNode = eventNode.putArray("regions");
                for (BriefingRegion region : es.regions()) {
                    appendRegionNode(regionsNode, region, driveMap);
                    validRegions.add(region.regionName());
                }
            }
        }

        if (auroraStateCache.isActive()
                && auroraStateCache.getCurrentLevel() != null
                && auroraStateCache.getCurrentLevel().isAlertWorthy()) {
            appendAuroraEvent(eventsNode);
            validEvents.add("aurora_tonight");
        }

        if (!includedDates.isEmpty()) {
            List<String> dateList = new ArrayList<>(includedDates);
            ObjectNode fwNode = root.putObject("forecastWindow");
            fwNode.put("startDate", dateList.get(0));
            fwNode.put("endDate", dateList.get(dateList.size() - 1));
            fwNode.put("dayCount", dateList.size());
            ArrayNode datesArray = fwNode.putArray("availableDates");
            dateList.forEach(datesArray::add);
        }

        ArrayNode veArray = root.putArray("validEvents");
        validEvents.forEach(veArray::add);
        ArrayNode vrArray = root.putArray("validRegions");
        validRegions.forEach(vrArray::add);
        root.set("events", eventsNode);

        return new RollupResult(objectMapper.writeValueAsString(root), validEvents, validRegions, validDayNames);
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
                .filter(s -> s.tide().tideAligned()).count();
        long coastalCount = region.slots().stream()
                .filter(s -> s.tide().tideState() != null).count();
        List<String> kingTideLocations = region.slots().stream()
                .filter(s -> s.tide().isKingTide())
                .map(BriefingSlot::locationName)
                .toList();
        boolean hasSpringTide = region.slots().stream()
                .anyMatch(s -> s.tide().isSpringTide());

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
                Confidence confidence = Confidence.fromString(
                        pick.path("confidence").asText("medium"));
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
