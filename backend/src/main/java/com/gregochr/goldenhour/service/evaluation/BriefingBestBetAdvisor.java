package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.DiffersBy;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.Relationship;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingRatingStats;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.TokenUsage;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** Maximum response tokens for the best-bet JSON (standard calls). */
    private static final int MAX_TOKENS = 1024;

    /** Maximum response tokens for extended-thinking calls (thinking budget + response). */
    private static final int MAX_TOKENS_THINKING = 16000;

    /** Maximum number of solar events to include in the rollup (matches frontend grid). */
    private static final int MAX_VISIBLE_EVENTS = 6;

    /** All English day names, used for narrative date validation. */
    private static final List<String> ALL_DAY_NAMES = List.of(
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday");

    private static final String SYSTEM_PROMPT = """
            You are a photography forecast advisor for PhotoCast, helping landscape photographers
            decide when and where to go for the best light.

            Given triage data for the next 6 upcoming solar events and aurora conditions across
            regions, identify the two best photographic opportunities.

            **How to pick the two recommendations:**

            Pick 1 — THE BEST OVERALL. The single best opportunity across all regions and
            events. This is "if you could go anywhere."

            Pick 2 — ALSO GOOD. Selected using the tiered rule below.

            **Label them clearly:**
            - Pick 1 headline should reflect it's the best overall
            - Pick 2 headline should highlight what makes it a distinct alternative

            The structured fields (day, event type, time, region, drive distance) are displayed
            separately by the frontend — do not repeat them in the headline or detail.
            Your headline should focus on WHY this is the pick — the conditions, the special
            features, what makes it stand out. Not "Region X sunrise Saturday" — the frontend
            already shows that.
            Good headline: "Best overall light and tide conditions"
            Good headline: "Rare king tide combo with clear skies"
            Bad headline: "North Yorkshire Coast sunset Wednesday — best overall light and tide"
            Bad headline: "Tyne and Wear sunset tonight — moderate aurora alert"

            **When evaluating quality, consider:**
            - GO regions with the most clear locations are generally best
            - Tide alignment is a strong differentiator. A GO region with tide-aligned
              coastal locations ranks above an equally clear inland-only region.
              Matching tides add foreground drama — mention the tide when it's a factor.
            - Tide classifications: Tides now have BOTH a lunar type (King/Spring/Regular)
              AND a statistical size (Extra Extra High / Extra High / regular).
              These are independent dimensions.
            - King Tide (lunar) = New/Full Moon + Moon at perigee. Rare (~5-10 per year).
              When also Extra Extra High statistically, it's exceptional.
              When combined with storm surge (low pressure + onshore wind),
              these can produce exceptional foreground drama — mention this when
              "hasSurgeBoost" is true for a region.
            - Spring Tide (lunar) = New/Full Moon (without perigee requirement). Happens
              ~24 times per year. When also Extra High or Extra Extra High statistically,
              it's a strong day for coastal photography.
              Spring + significant surge is also worth highlighting.
            - Regular Tide (lunar) = any other lunar phase. Can still be Extra Extra High
              if weather or other factors push the range up (storm surge).
            - Consider the COMBINATION: King Tide + Extra Extra High is rare and dramatic.
              Spring Tide + Extra High is more common but still excellent.
              Extra Extra High alone (on a Regular Tide) suggests weather-driven effects.
            - Tide alignment matters: matched tides add foreground drama and composition
              opportunities. Always mention when tide is aligned with the event.
            - Aurora events appear as columns in the grid alongside sunrise and sunset, using
              date-based event IDs like "2026-04-01_aurora". The aurora data includes both
              darkSkyLocationCount (total eligible) and clearLocationCount (actually clear skies).
              When clearLocationCount is high relative to darkSkyLocationCount, this is a top-tier
              opportunity — rank alongside king tides. But when clearLocationCount is very low
              (e.g. under 10% of darkSkyLocationCount), the aurora is effectively a washout —
              do NOT recommend it as a pick. Cloud cover blocks aurora viewing.
              An aurora pick should reference the specific night and alert level.
            - When mentioning aurora in a pick for a different event, always state the night
              explicitly — write "tonight's aurora" or "aurora forecast for tomorrow night",
              never just "aurora alert" or "moderate aurora chance". The reader sees each pick
              as a self-contained card and needs to know whether the aurora coincides with the
              recommended outing or is a separate opportunity on a different night.
            - AURORA LANGUAGE RULES: When aurora conditions contribute to a pick (either as
              the primary reason or as supporting context), always use preparatory language —
              never imperative or urgent action language.
              Good: "Tonight's aurora forecast is exceptional — worth heading out after dark
              if skies stay clear"
              Good: "Conditions are lining up well for aurora tonight — charge your batteries
              and keep an eye on the banner"
              Good: "A strong aurora forecast alongside clear skies makes tonight worth watching"
              Good: "Good aurora potential tonight — dark sky sites in Northumberland are well placed"
              Never write: "Get out now", "Head out immediately", "Don't miss this",
              "Go tonight" (as a command), or any language implying the user must act at
              the moment of reading.
              Rationale: The best bet card is generated hours in advance and may be stale by
              the time the user reads it. The aurora banner handles real-time action prompts —
              the best bet card handles planning and preparation.
            **CLAUDE EVALUATION SCORES**

            Some regions may include pre-computed Claude evaluation scores from the per-location \
            drill-down. When present, these are MORE RELIABLE than the triage verdict counts \
            (goCount, marginalCount) because they reflect full atmospheric analysis, not just \
            threshold heuristics.

            - claudeRatedCount: how many locations were Claude-evaluated
            - claudeHighRatedCount: how many scored 4-5 stars (strong prospects)
            - claudeMediumRatedCount: how many scored exactly 3 stars (decent but not special)
            - claudeAverageRating: mean star rating across rated locations (1.0-5.0)

            When Claude scores are present:
            - Prefer regions with high claudeAverageRating (>3.5 is promising, >4.0 is excellent)
            - claudeHighRatedCount > 0 is a strong positive signal — real photographic potential
            - A region with goCount=5 but claudeAverageRating=2.0 is weaker than it looks
            - A MARGINAL region with claudeAverageRating=4.0 is better than the verdict suggests

            When Claude scores are absent, fall back to the triage verdicts as before.

            - Lower wind speeds are better for long exposures and reflections
            - Comfort matters — extreme cold or high wind reduces the appeal
            - If multiple events are close in quality, prefer the sooner one
            **Tide data in the rollup now includes:**
              - lunarKingTideCount: how many locations have a lunar King Tide this event
              - lunarSpringTideCount: how many locations have a lunar Spring Tide this event
              - extraExtraHighCount: how many locations are statistically extreme (top 5%)
              - extraHighCount: how many locations are statistically large (>125% avg)
              - tideAlignedCount: how many locations have tide aligned with photographer preference

            Use these fields to identify COMBINATIONS:
              - If lunarKingTideCount > 0 AND extraExtraHighCount > 0: rare, dramatic — Pick 1
              - If lunarSpringTideCount > 0 AND extraHighCount > 0: strong combo — competitive
              - If extraExtraHighCount > 0 but lunarKingTideCount = 0: weather-driven, mention caution

            - If everything is STANDDOWN, say so honestly. Don't oversell marginal conditions.
              Be human — tell the photographer to stay home, charge their batteries,
              maybe edit last weekend's shots. A bit of humour is fine.

            **ALSO GOOD SELECTION RULE**

            After selecting Pick 1 (Best Bet), select Pick 2 (Also Good) using this \
            tiered rule:

            TIER 1 — SAME-SLOT ALTERNATIVE
            If another region on the SAME date and SAME event as Pick 1 has a \
            claudeAverageRating that is:
              - within 0.5 of Pick 1's rating, AND
              - at least 3.5 absolute,
            emit that region as Pick 2. Set relationship = "SAME_SLOT".
            Use case: the user can't reach Pick 1's region and wants a backup \
            for the same outing.

            TIER 2 — DIFFERENT SLOT
            If no same-slot region clears the Tier 1 threshold, look across ALL \
            OTHER slots in the window (different date, different event, or both). \
            Choose the single best opportunity from those slots. It must have:
              - claudeAverageRating >= 3.5, AND
              - meaningful differentiation from Pick 1 (not just a second-best \
                region on a near-identical slot).
            Emit it as Pick 2 with relationship = "DIFFERENT_SLOT" and differsBy \
            listing which dimensions differ from Pick 1 (DATE, EVENT, REGION — \
            any combination). \
            Use case: Pick 1 is the headline, but there's another strong outing \
            on a different day or a different part of the day worth knowing about.

            In the Tier 2 headline/detail text, make the temporal distinction \
            obvious. Phrase it so the reader knows immediately this is a different \
            opportunity, not a backup for the same outing. Examples:
              - "A second strong window later in the week"
              - "Separate opportunity if skies hold"

            NO PICK 2
            If neither tier produces a candidate at or above the thresholds, do \
            NOT emit a Pick 2. Return picks as a single-element array. An honest \
            silence is better than a padded recommendation.

            Respond with a JSON object:
            {
              "picks": [
                {
                  "rank": 1,
                  "headline": "One sentence, 15 words max, punchy — what to do",
                  "detail": "2 sentences max, 40 words max. Key conditions and what makes it special.",
                  "event": "<value from validEvents, e.g. 2026-03-30_sunset>",
                  "region": "<value from validRegions, e.g. Northumberland>",
                  "confidence": "high|medium|low"
                },
                {
                  "rank": 2,
                  "headline": "One sentence, 15 words max — what makes this a distinct alternative",
                  "detail": "2 sentences max, 40 words max. Key conditions and what makes it special.",
                  "event": "<value from validEvents>",
                  "region": "<value from validRegions>",
                  "confidence": "high|medium|low",
                  "relationship": "SAME_SLOT|DIFFERENT_SLOT",
                  "differsBy": ["DATE", "EVENT", "REGION"]
                }
              ]
            }

            Pick 2 rules:
            - relationship is required on Pick 2. SAME_SLOT = Tier 1, DIFFERENT_SLOT = Tier 2.
            - differsBy lists which dimensions differ from Pick 1. Always present when \
              relationship = DIFFERENT_SLOT. Subset of ["DATE", "EVENT", "REGION"]. \
              Empty array or omitted when relationship = SAME_SLOT.
            - Do NOT include relationship or differsBy on Pick 1.
            - If neither tier produces a strong candidate, return a single-element array.
            If everything is STANDDOWN, return a single pick with event and region as null.
            Return ONLY a JSON object — no preamble, no explanation, no code fences,
            no markdown. Your entire response must begin with { and end with }.

            CRITICAL CONSTRAINTS — violating any of these makes your response invalid:
            - Only recommend events present in the "validEvents" array. Never invent,
              extrapolate, or reference events not in the input.
            - Use the "dayName" field provided in each event — never calculate day of week.
            - Your "event" field in each pick MUST exactly match one of the "validEvents" values.
            - Your "region" field MUST exactly match one of the "validRegions" values.
            - Do not reference any date outside the "forecastWindow".

            **FORECAST RELIABILITY**

            Each region may include a stability field:

            SETTLED — Conditions locked in. Recommend with confidence.

            TRANSITIONAL — Front timing uncertain. When recommending a TRANSITIONAL region, \
            qualify the recommendation:
            - "...conditions may change — check the forecast before leaving"
            - "...front arriving later in the evening — the sunset window looks clear but \
            monitor closely"
            A TRANSITIONAL region with exceptional conditions (king tide, rare alignment) \
            may still be the best bet — just flag the uncertainty.

            UNSETTLED — Active frontal weather. Avoid recommending UNSETTLED regions unless \
            every other region is also poor. If forced to recommend an UNSETTLED region, \
            be honest about it.

            Never include raw data field names, codes, or technical identifiers in your response.
            Translate all data into natural language:
            - weatherCode values → "clear skies", "partly cloudy", "overcast", "light rain", "fog" etc.
            - windSpeedMs → describe as "calm", "light wind", "breezy", or convert to mph (multiply by 2.24)
            - Do not write "weatherCode 0" or "windSpeedMs 3.5" — write "clear skies" or "8mph wind"
            """;

    private final AnthropicApiClient anthropicApiClient;
    private final ObjectMapper objectMapper;
    private final JobRunService jobRunService;
    private final ModelSelectionService modelSelectionService;
    private final AuroraStateCache auroraStateCache;
    private final StabilitySnapshotProvider stabilitySnapshotProvider;
    private final BriefingEvaluationService briefingEvaluationService;

    /**
     * Constructs a {@code BriefingBestBetAdvisor}.
     *
     * @param anthropicApiClient         resilient Anthropic API client
     * @param objectMapper               Jackson mapper for JSON building and parsing
     * @param jobRunService              service for logging the API call in job run metrics
     * @param modelSelectionService      service for resolving the active Claude model
     * @param auroraStateCache           read-only access to the current aurora alert state
     * @param stabilitySnapshotProvider  provides the latest stability summary for region rollup
     * @param briefingEvaluationService  cached Claude evaluation scores from drill-down
     */
    public BriefingBestBetAdvisor(AnthropicApiClient anthropicApiClient,
            ObjectMapper objectMapper, JobRunService jobRunService,
            ModelSelectionService modelSelectionService,
            AuroraStateCache auroraStateCache,
            StabilitySnapshotProvider stabilitySnapshotProvider,
            @Lazy BriefingEvaluationService briefingEvaluationService) {
        this.anthropicApiClient = anthropicApiClient;
        this.objectMapper = objectMapper;
        this.jobRunService = jobRunService;
        this.modelSelectionService = modelSelectionService;
        this.auroraStateCache = auroraStateCache;
        this.stabilitySnapshotProvider = stabilitySnapshotProvider;
        this.briefingEvaluationService = briefingEvaluationService;
    }

    /**
     * Returns the human-readable name of the currently configured model (e.g. "Opus").
     *
     * @return display name of the active briefing model
     */
    public String getModelDisplayName() {
        EvaluationModel model = modelSelectionService.getActiveModel(RunType.BRIEFING_BEST_BET);
        return model.name().charAt(0) + model.name().substring(1).toLowerCase();
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
     * @param driveMap  unused — retained for API compatibility (pass {@code Map.of()})
     * @return list of best-bet picks (1–2 items normally; empty on failure)
     */
    public List<BestBet> advise(List<BriefingDay> days, Long jobRunId,
            Map<String, Integer> driveMap) {
        try {
            EvaluationModel model = modelSelectionService.getActiveModel(RunType.BRIEFING_BEST_BET);
            boolean useExtendedThinking = modelSelectionService.isExtendedThinking(RunType.BRIEFING_BEST_BET)
                    && model != EvaluationModel.HAIKU;
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            RollupResult rollup = buildRollupJson(days, now);
            long startMs = System.currentTimeMillis();

            MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                    .model(model.getModelId())
                    .maxTokens(useExtendedThinking ? MAX_TOKENS_THINKING : MAX_TOKENS)
                    .systemOfTextBlockParams(List.of(
                            TextBlockParam.builder().text(SYSTEM_PROMPT).build()))
                    .addUserMessage(rollup.json());
            if (useExtendedThinking) {
                paramsBuilder.thinking(ThinkingConfigAdaptive.builder().build());
            }

            Message response = anthropicApiClient.createMessage(paramsBuilder.build());

            long durationMs = System.currentTimeMillis() - startMs;
            String raw = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(ContentBlock::asText)
                    .map(TextBlock::text)
                    .findFirst()
                    .orElse("");

            LOG.info("Best-bet advisor completed ({}ms, model={})", durationMs, model);
            jobRunService.logApiCall(jobRunId, ServiceName.ANTHROPIC,
                    "POST", "briefing-best-bet", null,
                    durationMs, 200, raw, true, null,
                    model, null, null);

            List<BestBet> parsed = parseBestBets(raw);
            List<BestBet> validated = validateAndFilterPicks(
                    parsed, rollup.validEvents(), rollup.validRegions(), rollup.validDayNames());
            return enrichWithEventData(validated, days);
        } catch (Exception e) {
            LOG.warn("Best-bet advisor failed — returning empty picks (fallback to headline)", e);
            return List.of();
        }
    }

    /**
     * Enriches picks with structured display fields (dayName, eventType, eventTime)
     * derived from the triage data hierarchy, not from Claude's output.
     */
    private List<BestBet> enrichWithEventData(List<BestBet> picks, List<BriefingDay> days) {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));
        return picks.stream().map(pick -> {
            if (pick.event() == null) {
                return pick;
            }
            if (pick.event().endsWith("_aurora")) {
                String[] parts = pick.event().split("_", 2);
                LocalDate date = LocalDate.parse(parts[0]);
                String dayName;
                if (date.equals(today)) {
                    dayName = "Today";
                } else if (date.equals(today.plusDays(1))) {
                    dayName = "Tomorrow";
                } else {
                    dayName = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                }
                return new BestBet(pick.rank(), pick.headline(), pick.detail(),
                        pick.event(), pick.region(), pick.confidence(), pick.nearestDriveMinutes(),
                        dayName, "aurora", "after dark",
                        pick.relationship(), pick.differsBy());
            }
            String[] parts = pick.event().split("_", 2);
            if (parts.length < 2) {
                return pick;
            }
            LocalDate date = LocalDate.parse(parts[0]);
            String eventType = parts[1];

            String dayName;
            if (date.equals(today)) {
                dayName = "Today";
            } else if (date.equals(today.plusDays(1))) {
                dayName = "Tomorrow";
            } else {
                dayName = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            }

            String eventTime = findEventTime(date, eventType, days);

            return new BestBet(pick.rank(), pick.headline(), pick.detail(),
                    pick.event(), pick.region(), pick.confidence(), pick.nearestDriveMinutes(),
                    dayName, eventType, eventTime,
                    pick.relationship(), pick.differsBy());
        }).toList();
    }

    /**
     * Looks up the UK-local event time from the triage data for the given date and event type.
     */
    private String findEventTime(LocalDate date, String eventType, List<BriefingDay> days) {
        TargetType targetType;
        try {
            targetType = TargetType.valueOf(eventType.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
        ZoneId ukZone = ZoneId.of("Europe/London");
        return days.stream()
                .filter(d -> d.date().equals(date))
                .flatMap(d -> d.eventSummaries().stream())
                .filter(es -> es.targetType() == targetType)
                .flatMap(es -> es.regions().stream())
                .flatMap(r -> r.slots().stream())
                .filter(s -> s.solarEventTime() != null)
                .findFirst()
                .map(s -> s.solarEventTime().atOffset(ZoneOffset.UTC)
                        .atZoneSameInstant(ukZone)
                        .format(DateTimeFormatter.ofPattern("HH:mm")))
                .orElse(null);
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
                    p.event(), p.region(), p.confidence(), p.nearestDriveMinutes(),
                    p.dayName(), p.eventType(), p.eventTime(),
                    p.relationship(), p.differsBy()));
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
        boolean isAurora = pick.event() != null && pick.event().endsWith("_aurora");
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
     * @return rollup result containing the JSON and validation sets
     * @throws JsonProcessingException if Jackson serialization fails
     */
    RollupResult buildRollupJson(List<BriefingDay> days, LocalDateTime now)
            throws JsonProcessingException {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));
        Set<String> validEvents = new LinkedHashSet<>();
        Set<String> validRegions = new LinkedHashSet<>();
        Set<String> validDayNames = new LinkedHashSet<>();
        Set<String> includedDates = new LinkedHashSet<>();

        ObjectNode root = objectMapper.createObjectNode();
        root.put("currentTime", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

        ArrayNode eventsNode = objectMapper.createArrayNode();
        int eventCount = 0;
        for (BriefingDay day : days) {
            for (BriefingEventSummary es : day.eventSummaries()) {
                if (day.date().equals(today) && isEventPast(es, now)) {
                    continue;
                }
                if (eventCount >= MAX_VISIBLE_EVENTS) {
                    break;
                }
                eventCount++;
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
                    appendRegionNode(regionsNode, region, day.date(), es.targetType());
                    validRegions.add(region.regionName());
                }
            }
            if (eventCount >= MAX_VISIBLE_EVENTS) {
                break;
            }
        }

        if (auroraStateCache.isActive()
                && auroraStateCache.getCurrentLevel() != null
                && auroraStateCache.getCurrentLevel().isAlertWorthy()) {
            String auroraEventId = today + "_aurora";
            appendAuroraEvent(eventsNode, auroraEventId);
            validEvents.add(auroraEventId);
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

        logCacheCoverage(days, validEvents);
        return new RollupResult(objectMapper.writeValueAsString(root), validEvents, validRegions, validDayNames);
    }

    /**
     * Logs a per-date summary of how many region/event slots had Claude evaluation
     * scores available versus verdict-only data.
     */
    private void logCacheCoverage(List<BriefingDay> days, Set<String> validEvents) {
        int totalSlots = 0;
        int slotsWithScores = 0;
        for (BriefingDay day : days) {
            for (BriefingEventSummary es : day.eventSummaries()) {
                String eventId = day.date().toString() + "_"
                        + es.targetType().name().toLowerCase();
                if (!validEvents.contains(eventId)) {
                    continue;
                }
                for (BriefingRegion region : es.regions()) {
                    totalSlots++;
                    Map<String, BriefingEvaluationResult> cached =
                            briefingEvaluationService.getCachedScores(
                                    region.regionName(), day.date(), es.targetType());
                    if (!cached.isEmpty()) {
                        slotsWithScores++;
                    }
                }
            }
        }
        if (totalSlots > 0) {
            LOG.info("Best-bet rollup: {}/{} region-event slots have Claude scores",
                    slotsWithScores, totalSlots);
        }
    }

    private void appendRegionNode(ArrayNode regionsNode, BriefingRegion region,
            LocalDate date, TargetType targetType) {
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

        // Lunar classifications (deterministic, from moon phase + perigee)
        long lunarKingTideCount = region.slots().stream()
                .filter(s -> s.tide().lunarTideType() != null
                        && s.tide().lunarTideType().name().equals("KING_TIDE"))
                .count();
        long lunarSpringTideCount = region.slots().stream()
                .filter(s -> s.tide().lunarTideType() != null
                        && s.tide().lunarTideType().name().equals("SPRING_TIDE"))
                .count();

        // Statistical sizes (empirical, from historical data)
        long extraExtraHighCount = region.slots().stream()
                .filter(s -> s.tide().statisticalSize() != null
                        && s.tide().statisticalSize().name().equals("EXTRA_EXTRA_HIGH"))
                .count();
        long extraHighCount = region.slots().stream()
                .filter(s -> s.tide().statisticalSize() != null
                        && s.tide().statisticalSize().name().equals("EXTRA_HIGH"))
                .count();

        // Legacy: statistical "king tide" detection (P95)
        List<String> kingTideLocations = region.slots().stream()
                .filter(s -> s.tide().isKingTide())
                .map(BriefingSlot::locationName)
                .toList();

        // Legacy: statistical "spring tide" detection (>125% avg)
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

        // Lunar classifications (new)
        regionNode.put("lunarKingTideCount", lunarKingTideCount);
        regionNode.put("lunarSpringTideCount", lunarSpringTideCount);

        // Statistical sizes (new)
        regionNode.put("extraExtraHighCount", extraExtraHighCount);
        regionNode.put("extraHighCount", extraHighCount);

        // Legacy fields (kept for backward compatibility)
        regionNode.put("hasKingTide", !kingTideLocations.isEmpty());
        if (!kingTideLocations.isEmpty()) {
            ArrayNode kingTideLocs = regionNode.putArray("kingTideLocations");
            kingTideLocations.forEach(kingTideLocs::add);
        }
        regionNode.put("hasSpringTide", hasSpringTide);
        regionNode.put("hasSurgeBoost", !kingTideLocations.isEmpty() || hasSpringTide);

        regionNode.put("coastalLocationCount", coastalCount);
        regionNode.put("inlandLocationCount", region.slots().size() - coastalCount);

        // Claude evaluation score distribution (from cached drill-down scores)
        appendClaudeScores(regionNode, region.regionName(), date, targetType);

        // Stability rollup: worst-case across grid cells containing this region's locations
        appendStabilityToRegion(regionNode, region);
    }

    /**
     * Looks up cached Claude evaluation scores for the given region/date/event
     * and appends score distribution fields to the region JSON node.
     *
     * <p>When no cached scores are available, the fields are omitted and the
     * prompt falls back to verdict-only data.
     */
    private void appendClaudeScores(ObjectNode regionNode, String regionName,
            LocalDate date, TargetType targetType) {
        Map<String, BriefingEvaluationResult> cached =
                briefingEvaluationService.getCachedScores(regionName, date, targetType);
        if (cached.isEmpty()) {
            return;
        }

        List<BriefingRatingStats.Entry> entries = cached.values().stream()
                .map(r -> new BriefingRatingStats.Entry(r.locationName(), r.rating()))
                .toList();
        BriefingRatingStats.Stats stats =
                BriefingRatingStats.compute(entries, regionName, date, targetType);
        if (stats.isEmpty()) {
            return;
        }

        regionNode.put("claudeRatedCount", stats.count());
        regionNode.put("claudeHighRatedCount", stats.highRated());
        regionNode.put("claudeMediumRatedCount", stats.mediumRated());
        regionNode.put("claudeAverageRating", stats.averageRating());
    }

    /**
     * Looks up the worst-case stability across all grid cells containing locations
     * in the given region. If stability data is unavailable, the field is omitted.
     */
    private void appendStabilityToRegion(ObjectNode regionNode, BriefingRegion region) {
        StabilitySummaryResponse summary = stabilitySnapshotProvider.getLatestStabilitySummary();
        if (summary == null || summary.cells().isEmpty()) {
            return;
        }

        Set<String> regionLocationNames = new LinkedHashSet<>();
        for (BriefingSlot slot : region.slots()) {
            regionLocationNames.add(slot.locationName());
        }

        ForecastStability worstStability = null;
        String worstReason = null;
        for (StabilitySummaryResponse.GridCellDetail cell : summary.cells()) {
            boolean hasMatch = cell.locationNames().stream()
                    .anyMatch(regionLocationNames::contains);
            if (hasMatch) {
                if (worstStability == null || isMoreUnstable(cell.stability(), worstStability)) {
                    worstStability = cell.stability();
                    worstReason = cell.reason();
                }
            }
        }

        if (worstStability != null) {
            regionNode.put("stability", worstStability.name());
            if (worstReason != null) {
                regionNode.put("stabilityReason", worstReason);
            }
        }
    }

    /**
     * Returns {@code true} if {@code candidate} is more unstable than {@code current}.
     */
    private static boolean isMoreUnstable(ForecastStability candidate, ForecastStability current) {
        return candidate.evaluationWindowDays() < current.evaluationWindowDays();
    }

    private void appendAuroraEvent(ArrayNode eventsNode, String eventId) {
        ObjectNode auroraNode = eventsNode.addObject();
        auroraNode.put("event", eventId);
        auroraNode.put("alertLevel", auroraStateCache.getCurrentLevel().name());
        Double kp = auroraStateCache.getLastTriggerKp();
        if (kp != null) {
            auroraNode.put("kp", kp);
        }
        auroraNode.put("darkSkyLocationCount", auroraStateCache.getDarkSkyLocationCount());
        Integer clearCount = auroraStateCache.getClearLocationCount();
        auroraNode.put("clearLocationCount", clearCount != null ? clearCount : 0);
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
     * Returns the UK-local HH:mm event time from the first slot with a non-null solarEventTime,
     * or null. Converts from UTC to Europe/London (handles GMT/BST automatically).
     */
    private String getEventTimeStr(BriefingEventSummary es) {
        ZoneId ukZone = ZoneId.of("Europe/London");
        return es.regions().stream()
                .flatMap(r -> r.slots().stream())
                .filter(s -> s.solarEventTime() != null)
                .findFirst()
                .map(s -> s.solarEventTime().atOffset(ZoneOffset.UTC)
                        .atZoneSameInstant(ukZone)
                        .format(DateTimeFormatter.ofPattern("HH:mm")))
                .orElse(null);
    }

    /**
     * Result of evaluating the briefing rollup with a single Claude model.
     *
     * @param model           the model used
     * @param rawResponse     raw text from Claude, or null on failure
     * @param parsedPicks     picks before validation (empty on failure)
     * @param validatedPicks  picks after validation (empty on failure)
     * @param durationMs      API call duration in milliseconds
     * @param tokenUsage      token counts from the API response
     * @param thinkingText    raw extended thinking chain text, or null for non-ET variants
     */
    public record ModelComparisonResult(EvaluationModel model, String rawResponse,
            List<BestBet> parsedPicks, List<BestBet> validatedPicks,
            long durationMs, TokenUsage tokenUsage, String thinkingText) {
    }

    /**
     * Aggregated result of a multi-model comparison run.
     *
     * @param rollupJson the JSON sent to all five variants
     * @param results    one result per variant (HAIKU, SONNET, SONNET_ET, OPUS, OPUS_ET)
     */
    public record ComparisonRun(String rollupJson, List<ModelComparisonResult> results) {
    }

    /**
     * Calls all five variants (Haiku, Sonnet, Sonnet+ET, Opus, Opus+ET) sequentially with
     * the same briefing rollup and returns the parsed, validated picks for each.
     *
     * <p>Variants run sequentially (not in parallel) to stay within the Claude bulkhead
     * concurrency cap. Per-variant failures are caught and returned as failed results with
     * null rawResponse, so partial success is possible.
     *
     * @param days     the fully assembled briefing days (triage complete)
     * @param driveMap unused — retained for API compatibility (pass {@code Map.of()})
     * @return comparison run containing the rollup JSON and all variant results
     * @throws com.fasterxml.jackson.core.JsonProcessingException if rollup JSON build fails
     */
    public ComparisonRun compareModels(List<BriefingDay> days,
            Map<String, Integer> driveMap) throws com.fasterxml.jackson.core.JsonProcessingException {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        RollupResult rollup = buildRollupJson(days, now);
        List<EvaluationModel> models = List.of(
                EvaluationModel.HAIKU, EvaluationModel.SONNET, EvaluationModel.SONNET_ET,
                EvaluationModel.OPUS, EvaluationModel.OPUS_ET);

        List<ModelComparisonResult> results = new ArrayList<>();
        for (EvaluationModel model : models) {
            results.add(callModel(model, rollup, days));
        }
        return new ComparisonRun(rollup.json(), results);
    }

    private ModelComparisonResult callModel(EvaluationModel model, RollupResult rollup,
            List<BriefingDay> days) {
        try {
            boolean extendedThinking = model.isExtendedThinking();
            long startMs = System.currentTimeMillis();

            MessageCreateParams.Builder builder = MessageCreateParams.builder()
                    .model(model.getModelId())
                    .maxTokens(extendedThinking ? MAX_TOKENS_THINKING : MAX_TOKENS)
                    .systemOfTextBlockParams(List.of(
                            TextBlockParam.builder().text(SYSTEM_PROMPT).build()))
                    .addUserMessage(rollup.json());

            if (extendedThinking) {
                builder.thinking(ThinkingConfigAdaptive.builder().build());
            }

            Message response = anthropicApiClient.createMessage(builder.build());
            long durationMs = System.currentTimeMillis() - startMs;

            // Text blocks only — thinking blocks are filtered out here
            String raw = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(ContentBlock::asText)
                    .map(TextBlock::text)
                    .findFirst()
                    .orElse("");

            // Extract thinking chain text (null for non-ET variants)
            String thinkingText = response.content().stream()
                    .filter(ContentBlock::isThinking)
                    .map(ContentBlock::asThinking)
                    .map(ThinkingBlock::thinking)
                    .filter(t -> t != null && !t.isBlank())
                    .findFirst()
                    .orElse(null);

            TokenUsage tokenUsage = new TokenUsage(
                    response.usage().inputTokens(),
                    response.usage().outputTokens(),
                    response.usage().cacheCreationInputTokens().orElse(0L),
                    response.usage().cacheReadInputTokens().orElse(0L));

            List<BestBet> parsed = parseBestBets(raw);
            List<BestBet> validated = validateAndFilterPicks(
                    parsed, rollup.validEvents(), rollup.validRegions(), rollup.validDayNames());
            List<BestBet> enriched = enrichWithEventData(validated, days);

            LOG.info("Model comparison {} completed ({}ms, {} picks, thinking={})",
                    model, durationMs, enriched.size(), thinkingText != null);
            return new ModelComparisonResult(model, raw, parsed, enriched, durationMs, tokenUsage, thinkingText);
        } catch (Exception e) {
            LOG.warn("Model comparison {} failed: {}", model, e.getMessage());
            return new ModelComparisonResult(model, null, List.of(), List.of(), 0, TokenUsage.EMPTY, null);
        }
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
            String cleaned = PromptUtils.stripCodeFences(raw);
            String extracted = PromptUtils.extractJsonObject(cleaned);
            JsonNode root = objectMapper.readTree(extracted);
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
                Relationship relationship = Relationship.fromString(
                        pick.path("relationship").asText(null));
                List<DiffersBy> differsBy = new ArrayList<>();
                JsonNode differsByNode = pick.get("differsBy");
                if (differsByNode != null && differsByNode.isArray()) {
                    for (JsonNode d : differsByNode) {
                        DiffersBy dim = DiffersBy.fromString(d.asText(null));
                        if (dim != null) {
                            differsBy.add(dim);
                        }
                    }
                }
                picks.add(new BestBet(rank, headline, detail, event, region, confidence,
                        null, null, null, null, relationship, differsBy));
            }
            LOG.info("Best-bet advisor returned {} pick(s)", picks.size());
            return List.copyOf(picks);
        } catch (Exception e) {
            String preview = raw.length() > 4000
                    ? raw.substring(0, 4000) + "...<truncated>"
                    : raw;
            LOG.warn("Failed to parse best-bet response — returning empty. Raw response was:\n{}",
                    preview, e);
            return List.of();
        }
    }
}
