package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.StopReason;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BestBetResult;
import com.gregochr.goldenhour.model.BestBetStatus;
import com.gregochr.goldenhour.model.CandidateCoverage;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingRatingStats;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.TravelDayService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    /**
     * Default response-token ceiling for the best-bet JSON (standard, non-thinking calls)
     * when {@code photocast.best-bet.max-tokens} is not overridden.
     *
     * <p>Sized with deliberate headroom over the previous 1024 ceiling. A 1024-token cap
     * was observed truncating a verbose two-pick response mid-field at ~3656 chars
     * (1024 output tokens ≈ ~3.6 KB of JSON/prose), which produced structurally invalid
     * JSON and zero persisted picks. 4096 comfortably clears a full reasoning-plus-two-picks
     * response even when the model is verbose in the output channel.
     */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    /** Maximum response tokens for extended-thinking calls (thinking budget + response). */
    private static final int MAX_TOKENS_THINKING = 16000;

    /** Maximum number of solar events to include in the rollup (matches frontend grid). */
    private static final int MAX_VISIBLE_EVENTS = 6;

    /** Cloud-cover percent below which a scored aurora location counts as "clear" for ranking. */
    private static final int AURORA_CLEAR_CLOUD_PERCENT = 50;

    /** Darkness rank used when a region's aurora locations have no Bortle class (brightest/unknown). */
    private static final int AURORA_DARKNESS_UNKNOWN = 9;

    /**
     * Orders candidate aurora regions best-first: most clear locations, then highest mean star
     * rating, then darkest sky ({@code bortleClass}), then name for a deterministic tie-break.
     * Used with {@code min(...)} so the smallest under this order is the best region.
     */
    private static final Comparator<AuroraRegionSummary> AURORA_REGION_ORDER =
            Comparator.comparingInt(AuroraRegionSummary::clearCount).reversed()
                    .thenComparing(Comparator
                            .comparingDouble(AuroraRegionSummary::averageStars).reversed())
                    .thenComparingInt(AuroraRegionSummary::darknessRank)
                    .thenComparing(AuroraRegionSummary::name);

    private final AnthropicApiClient anthropicApiClient;
    private final ObjectMapper objectMapper;
    private final JobRunService jobRunService;
    private final ModelSelectionService modelSelectionService;
    private final AuroraStateCache auroraStateCache;
    private final StabilitySnapshotProvider stabilitySnapshotProvider;
    private final BriefingEvaluationService briefingEvaluationService;
    private final TravelDayService travelDayService;

    /**
     * Response-token ceiling for the best-bet JSON on standard (non-thinking) calls.
     * Configurable without a redeploy via {@code photocast.best-bet.max-tokens}; defaults
     * to {@link #DEFAULT_MAX_TOKENS}.
     */
    private final int maxTokens;

    private final java.time.Clock clock;

    /** UK civil-date zone for "today" derivation. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

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
     * @param travelDayService           excludes travel-day events from the candidate rollup
     * @param maxTokens                  response-token ceiling for standard best-bet calls
     * @param clock                      UTC clock supplying "now" and (via London) "today"
     *                                   ({@code photocast.best-bet.max-tokens})
     */
    public BriefingBestBetAdvisor(AnthropicApiClient anthropicApiClient,
            ObjectMapper objectMapper, JobRunService jobRunService,
            ModelSelectionService modelSelectionService,
            AuroraStateCache auroraStateCache,
            StabilitySnapshotProvider stabilitySnapshotProvider,
            @Lazy BriefingEvaluationService briefingEvaluationService,
            TravelDayService travelDayService,
            @Value("${photocast.best-bet.max-tokens:" + DEFAULT_MAX_TOKENS + "}") int maxTokens,
            java.time.Clock clock) {
        this.anthropicApiClient = anthropicApiClient;
        this.objectMapper = objectMapper;
        this.jobRunService = jobRunService;
        this.modelSelectionService = modelSelectionService;
        this.auroraStateCache = auroraStateCache;
        this.stabilitySnapshotProvider = stabilitySnapshotProvider;
        this.briefingEvaluationService = briefingEvaluationService;
        this.travelDayService = travelDayService;
        this.maxTokens = maxTokens;
        this.clock = clock;
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
     * Returns the advisor's current live system prompt, so the replay harness can run a captured
     * or synthetic rollup through the production prompt as the "before" side of a before/after
     * comparison against a candidate prompt. Reading it here is cleaner than the reflection the
     * prompt-regression test uses.
     *
     * @return the current {@code SYSTEM_PROMPT}
     */
    public String currentSystemPrompt() {
        return BestBetPromptText.systemPrompt();
    }

    /**
     * Carries the rollup JSON and derived validation sets out of {@link #buildRollupJson}.
     *
     * @param json          the compact JSON string sent to Claude as the user message
     * @param validEvents   all event identifiers present in the rollup (e.g. {@code "2026-03-30_sunset"})
     * @param validRegions  all region names present in the rollup
     * @param validDayNames day names (e.g. {@code "Monday"}) for all dates in the forecast window
     * @param coverageByKey Claude-evaluation coverage per {@code event|region} key — the same
     *                      data the prompt sees, surfaced for the deterministic coverage gate
     */
    record RollupResult(String json, Set<String> validEvents,
            Set<String> validRegions, Set<String> validDayNames,
            Map<String, CandidateCoverage> coverageByKey) {
    }

    /**
     * Produces Claude-generated best-bet picks from the post-triage region rollup data,
     * carrying an explicit {@link BestBetStatus} so callers can tell an honest empty result
     * apart from a failure.
     *
     * <p>The status REPORTS which internal path was taken; it never changes the selection or
     * ranking. {@link BestBetStatus#SUCCESS_WITH_PICKS} when usable picks survived (including
     * picks salvaged from a truncated response); {@link BestBetStatus#SUCCESS_NO_PICKS} when
     * the advisor honestly returned an empty pick set; {@link BestBetStatus#FAILED} on an
     * exception, an unparseable response with nothing salvageable, or when every parsed pick
     * was rejected by validation (nothing usable came back). The briefing always loads
     * regardless — the frontend switches on the status.
     *
     * @param days      the fully assembled briefing days (triage complete)
     * @param jobRunId  the current briefing job run ID for API call logging
     * @param driveMap  unused — retained for API compatibility (pass {@code Map.of()})
     * @return the advisor outcome (status + picks)
     */
    public BestBetResult advise(List<BriefingDay> days, Long jobRunId,
            Map<String, Integer> driveMap) {
        try {
            EvaluationModel model = modelSelectionService.getActiveModel(RunType.BRIEFING_BEST_BET);
            boolean useExtendedThinking = modelSelectionService.isExtendedThinking(RunType.BRIEFING_BEST_BET)
                    && model != EvaluationModel.HAIKU;
            LocalDateTime now = LocalDateTime.now(clock);
            RollupResult rollup = buildRollupJson(days, now);
            long startMs = System.currentTimeMillis();

            MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                    .model(model.getModelId())
                    .maxTokens(useExtendedThinking ? MAX_TOKENS_THINKING : maxTokens)
                    .systemOfTextBlockParams(List.of(
                            TextBlockParam.builder().text(BestBetPromptText.systemPrompt()).build()))
                    .addUserMessage(rollup.json());
            if (useExtendedThinking) {
                paramsBuilder.thinking(ThinkingConfigAdaptive.builder().build());
            }

            Message response = anthropicApiClient.createMessage(paramsBuilder.build());

            long durationMs = System.currentTimeMillis() - startMs;
            String raw = extractFirstText(response);
            Optional<StopReason> stopReason = response.stopReason();

            LOG.info("Best-bet advisor completed ({}ms, model={}, stopReason={})",
                    durationMs, model, stopReason.map(Object::toString).orElse("unknown"));
            // Capture the exact rollup input (request body) alongside the response so the
            // advisor replay harness can re-feed any live cycle's input through a swapped
            // prompt for before/after validation — previously this was logged as null.
            jobRunService.logApiCall(jobRunId, ServiceName.ANTHROPIC,
                    "POST", "briefing-best-bet", rollup.json(),
                    durationMs, 200, raw, true, null,
                    model, null, null);

            BestBetResult parsed = classifyAndParse(raw);
            logResponseDisposition(stopReason, parsed.picks().size(), raw.length(), jobRunId);
            if (parsed.status() != BestBetStatus.SUCCESS_WITH_PICKS) {
                // SUCCESS_NO_PICKS (honest decline) or FAILED — nothing to validate/enrich.
                return parsed;
            }
            List<BestBet> validated = validateAndFilterPicks(
                    parsed.picks(), rollup.validEvents(), rollup.validRegions(), rollup.validDayNames());
            if (validated.isEmpty()) {
                // Picks parsed but none named a valid event/region — nothing usable came back.
                LOG.warn("Best-bet advisor parsed picks but all failed validation — FAILED "
                        + "(jobRunId={})", jobRunId);
                return BestBetResult.failed();
            }
            List<BestBet> evidenced = dropUnevaluatedPicks(validated, rollup.coverageByKey());
            if (evidenced.isEmpty()) {
                // Valid picks came back, but none carry any Claude colour rating. A weather GO
                // count alone is not evidence of a good sky, so we decline rather than dress up a
                // guess as a recommendation — this is the honest "nothing evaluated" state.
                LOG.info("Best-bet advisor: no pick carries a colour evaluation — declining with "
                        + "no-picks (jobRunId={})", jobRunId);
                return BestBetResult.noPicks();
            }
            List<BestBet> covered = applyCoverageAwareRanking(evidenced, rollup.coverageByKey());
            List<BestBet> enriched = enrichWithEventData(covered, days);
            if (enriched.isEmpty()) {
                // Picks parsed but none survived enrichment — nothing usable came back.
                LOG.warn("Best-bet advisor parsed picks but none survived enrichment — FAILED "
                        + "(jobRunId={})", jobRunId);
                return BestBetResult.failed();
            }
            return BestBetResult.withPicks(enriched);
        } catch (Exception e) {
            LOG.warn("Best-bet advisor failed — returning FAILED status (fallback to headline)", e);
            return BestBetResult.failed();
        }
    }

    /**
     * Emits a single classifiable disposition log so a truncated advisor response can never
     * again hide as an honest empty-pick result — the camouflage that let this bug persist.
     *
     * <p>Distinguishes the three cases the forensic dig had to separate by hand:
     * <ul>
     *   <li><b>(a) honest zero</b> — the model returned valid JSON with no picks; logged at INFO.</li>
     *   <li><b>(b) truncation</b> — the response stopped on the token limit and nothing survived;
     *       logged at WARN with the correlating {@code jobRunId} (the api_call_log key) and the
     *       remediation hint.</li>
     *   <li><b>(c) salvage</b> — the response was token-limited but valid leading pick(s) were
     *       recovered; logged at WARN noting how many survived.</li>
     * </ul>
     *
     * @param stopReason    the SDK-reported stop reason (max_tokens drives truncation detection)
     * @param pickCount     number of picks parsed (after any salvage)
     * @param responseChars length of the raw response text
     * @param jobRunId      the briefing job run id — correlates to the {@code api_call_log} row
     */
    private void logResponseDisposition(Optional<StopReason> stopReason, int pickCount,
            int responseChars, Long jobRunId) {
        boolean tokenLimited = stopReason
                .filter(sr -> sr.equals(StopReason.MAX_TOKENS)).isPresent();
        if (tokenLimited) {
            if (pickCount > 0) {
                LOG.warn("[BEST-BET TRUNCATION] Advisor response stopped on the token limit "
                        + "(stopReason=max_tokens, responseChars={}, jobRunId={}, maxTokens={}) "
                        + "but {} valid pick(s) were salvaged. Raise photocast.best-bet.max-tokens "
                        + "if this recurs.", responseChars, jobRunId, maxTokens, pickCount);
            } else {
                LOG.warn("[BEST-BET TRUNCATION] Advisor response stopped on the token limit "
                        + "(stopReason=max_tokens, responseChars={}, jobRunId={}, maxTokens={}) "
                        + "and no picks could be salvaged — the Planner falls back to the "
                        + "mechanical headline. Raise photocast.best-bet.max-tokens.",
                        responseChars, jobRunId, maxTokens);
            }
            return;
        }
        if (pickCount == 0) {
            LOG.info("[BEST-BET] Advisor returned no picks — honest decline "
                    + "(stopReason={}, responseChars={}, jobRunId={})",
                    stopReason.map(Object::toString).orElse("unknown"), responseChars, jobRunId);
        }
    }

    /**
     * Enriches picks with structured display fields (dayName, eventType, eventTime)
     * derived from the triage data hierarchy, not from Claude's output.
     */
    private List<BestBet> enrichWithEventData(List<BestBet> picks, List<BriefingDay> days) {
        LocalDate today = LocalDate.now(clock.withZone(LONDON));
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
        return BestBetPickValidator.validateAndFilterPicks(
                picks, validEvents, validRegions, validDayNames);
    }

    /**
     * Enforces the headline coverage floor: a region cannot hold rank 1 on cheap
     * GO-count merit when only a couple of its locations were actually
     * Claude-evaluated and a better-covered alternative pick is available.
     *
     * <p>Extends the principle behind {@code BriefingHonestyFilter} (which rewrites
     * regions with <em>zero</em> Claude scores on the read path) to the
     * <em>insufficient</em>-coverage case at the crowning decision: the headline
     * must clear {@link BestBetRanker#MIN_HEADLINE_CLAUDE_COVERAGE} when a pick that does clear
     * it exists. The gate is deliberately comparative — it demotes a thin headline
     * only by promoting a genuinely better-evidenced pick. When no pick clears the
     * floor the order is left untouched (thin coverage is then the best evidence
     * available; the targeted force-evaluation path is what raises headline
     * contenders above the floor in the first place).
     *
     * <p>Stay-home picks and aurora picks are exempt — a stay-home pick crowns
     * nothing and aurora has its own clear-sky gate in the prompt.
     *
     * <p>When a promotion happens the new headline's relationship/differsBy are
     * cleared (rank 1 carries neither) and the trailing picks' relationship fields
     * are recomputed relative to the new headline so they stay coherent.
     *
     * @param picks    validated picks in Claude's ranked order
     * @param coverage per-{@code event|region} Claude coverage from the rollup
     * @return the picks, possibly reordered so a covered pick holds the headline
     */
    List<BestBet> applyCoverageAwareRanking(List<BestBet> picks,
            Map<String, CandidateCoverage> coverage) {
        return BestBetRanker.applyCoverageAwareRanking(picks, coverage);
    }

    /**
     * Drops picks with zero Claude colour coverage. A best bet's entire premise is Claude's colour
     * evaluation; a region/event with no colour rating at all — only a weather GO count — is not
     * evidence of a good sky, so recommending it (even hedged) is dishonest. Stay-home and aurora
     * picks are exempt. Survivors are renumbered so the highest remaining
     * pick holds rank 1; an empty result signals "no colour-backed recommendation available", which
     * the caller maps to {@code SUCCESS_NO_PICKS} (an honest decline), never {@code FAILED}.
     *
     * @param picks    validated picks in ranked order
     * @param coverage per-{@code event|region} Claude coverage from the rollup
     * @return the picks that carry colour evidence, renumbered; possibly empty
     */
    List<BestBet> dropUnevaluatedPicks(List<BestBet> picks,
            Map<String, CandidateCoverage> coverage) {
        return BestBetRanker.dropUnevaluatedPicks(picks, coverage);
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
        LocalDate today = LocalDate.now(clock.withZone(LONDON));
        Set<String> validEvents = new LinkedHashSet<>();
        Set<String> validRegions = new LinkedHashSet<>();
        Set<String> validDayNames = new LinkedHashSet<>();
        Set<String> includedDates = new LinkedHashSet<>();
        Map<String, CandidateCoverage> coverageByKey = new HashMap<>();

        ObjectNode root = objectMapper.createObjectNode();
        root.put("currentTime", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

        ArrayNode eventsNode = objectMapper.createArrayNode();
        int eventCount = 0;
        for (BriefingDay day : days) {
            // Travel-day gate: the operator is away on this date, so its events can never be a
            // best bet. Excluding them here keeps invalid candidates out of the prompt entirely —
            // an all-travel window yields an empty rollup and an honest "stay home" no-picks result.
            if (travelDayService.isTravelDay(day.date())) {
                continue;
            }
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
                int daysAhead = (int) ChronoUnit.DAYS.between(today, day.date());
                ArrayNode regionsNode = eventNode.putArray("regions");
                for (BriefingRegion region : es.regions()) {
                    CandidateCoverage coverage = appendRegionNode(
                            regionsNode, region, day.date(), es.targetType(), daysAhead);
                    validRegions.add(region.regionName());
                    coverageByKey.put(BestBetRanker.coverageKey(eventId, region.regionName()), coverage);
                }
            }
            if (eventCount >= MAX_VISIBLE_EVENTS) {
                break;
            }
        }

        if (auroraStateCache.isActive()
                && auroraStateCache.getCurrentLevel() != null
                && auroraStateCache.getCurrentLevel().isAlertWorthy()
                && !travelDayService.isTravelDay(today)) {
            String auroraEventId = today + "_aurora";
            String auroraRegion = appendAuroraEvent(eventsNode, auroraEventId);
            validEvents.add(auroraEventId);
            // The data-derived aurora region (when one exists) becomes a valid region for the
            // night so a pick referencing it passes validation, and an improvised one does not.
            if (auroraRegion != null) {
                validRegions.add(auroraRegion);
            }
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
        return new RollupResult(objectMapper.writeValueAsString(root), validEvents,
                validRegions, validDayNames, coverageByKey);
    }

    /**
     * Looks up cached Claude scores for a region/event and reduces them to
     * {@link BriefingRatingStats.Stats}, or {@code null} when none are cached or
     * none are valid. Shared by the rollup JSON builder and the coverage extractor
     * so both read identical figures.
     */
    private BriefingRatingStats.Stats computeRegionStats(String regionName, LocalDate date,
            TargetType targetType) {
        Map<String, BriefingEvaluationResult> cached =
                briefingEvaluationService.getCachedScores(regionName, date, targetType);
        if (cached.isEmpty()) {
            return null;
        }
        List<BriefingRatingStats.Entry> entries = cached.values().stream()
                .map(r -> new BriefingRatingStats.Entry(r.locationName(), r.rating()))
                .toList();
        BriefingRatingStats.Stats stats =
                BriefingRatingStats.compute(entries, regionName, date, targetType);
        return stats.isEmpty() ? null : stats;
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

    private CandidateCoverage appendRegionNode(ArrayNode regionsNode, BriefingRegion region,
            LocalDate date, TargetType targetType, int daysAhead) {
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

        // Perishability flag for the advisor's within-band scarcity preference. Model-only:
        // it never feeds the deterministic coverage gate, so scarcity can never override the
        // quality floors. Derived from the lunar tide counts already in the rollup.
        String tideScarcity = deriveTideScarcity(lunarKingTideCount, lunarSpringTideCount);
        if (tideScarcity != null) {
            regionNode.put("scarcity", tideScarcity);
        }

        // Claude evaluation score distribution (from cached drill-down scores).
        // Computed once and reused for the coverage gate so the cache is hit a
        // single time per region (matching the pre-coverage call count).
        BriefingRatingStats.Stats stats =
                computeRegionStats(region.regionName(), date, targetType);
        appendClaudeScores(regionNode, stats);

        // Stability rollup: worst-case across grid cells containing this region's locations
        appendStabilityToRegion(regionNode, region);

        return stats == null
                ? new CandidateCoverage(0, daysAhead, 0.0)
                : new CandidateCoverage(stats.count(), daysAhead, stats.averageRating());
    }

    /**
     * Appends the Claude evaluation score distribution to the region JSON node.
     *
     * <p>When {@code stats} is {@code null} (no cached scores) the fields are
     * omitted and the prompt falls back to verdict-only data.
     */
    private void appendClaudeScores(ObjectNode regionNode, BriefingRatingStats.Stats stats) {
        if (stats == null) {
            return;
        }
        regionNode.put("claudeRatedCount", stats.count());
        regionNode.put("claudeHighRatedCount", stats.highRated());
        regionNode.put("claudeMediumRatedCount", stats.mediumRated());
        regionNode.put("claudeAverageRating", stats.averageRating());
    }

    /**
     * Labels a region's perishable tide opportunity for the advisor's within-band scarcity
     * preference: {@code "KING_TIDE"} (rarest — a handful per year) takes precedence over
     * {@code "SPRING_TIDE"} (perishable — passes in a day or two). Returns {@code null} when the
     * region has neither, i.e. no scarcity signal.
     *
     * @param lunarKingTideCount   locations with a lunar King Tide this event
     * @param lunarSpringTideCount locations with a lunar Spring Tide this event
     * @return the scarcity label, or {@code null} when none applies
     */
    private static String deriveTideScarcity(long lunarKingTideCount, long lunarSpringTideCount) {
        if (lunarKingTideCount > 0) {
            return "KING_TIDE";
        }
        if (lunarSpringTideCount > 0) {
            return "SPRING_TIDE";
        }
        return null;
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
     *
     * <p>Relies on the declared enum order in {@link ForecastStability}: SETTLED
     * (ordinal 0) is the most stable, UNSETTLED (ordinal 2) is the least —
     * a higher ordinal means a more unstable cell. Previously this used
     * {@code evaluationWindowDays} as a side-channel ordering primitive; that
     * field is now a display-only depth hint and must not be relied on for
     * any policy decision.
     */
    private static boolean isMoreUnstable(ForecastStability candidate, ForecastStability current) {
        return candidate.ordinal() > current.ordinal();
    }

    private String appendAuroraEvent(ArrayNode eventsNode, String eventId) {
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
        String region = bestAuroraRegion();
        if (region != null) {
            auroraNode.put("region", region);
        }
        return region;
    }

    /**
     * Derives the best dark-sky region for tonight's aurora from the cached aurora scores,
     * so the advisor recommends a real region instead of improvising one (the observed
     * "Northumberland is typically the premier dark-sky region" gap-filling). Each cached
     * {@link AuroraForecastScore} carries its full {@link LocationEntity}, so the scored
     * locations already know their region; this groups them and ranks by clear-location count,
     * then mean star rating, then darkness ({@code bortleClass}).
     *
     * <p>Returns {@code null} when no cached score carries a region — the caller then degrades
     * to region-agnostic phrasing. There is deliberately no config-default fallback: inventing
     * a default would re-introduce improvisation in disguise.
     *
     * @return the best dark-sky region name for the aurora, or {@code null} when none is derivable
     */
    private String bestAuroraRegion() {
        List<AuroraForecastScore> scores = auroraStateCache.getCachedScores();
        if (scores == null || scores.isEmpty()) {
            return null;
        }
        Map<String, List<AuroraForecastScore>> byRegion = new LinkedHashMap<>();
        for (AuroraForecastScore score : scores) {
            String name = regionNameOf(score);
            if (name != null) {
                byRegion.computeIfAbsent(name, k -> new ArrayList<>()).add(score);
            }
        }
        return byRegion.entrySet().stream()
                .map(e -> summariseAuroraRegion(e.getKey(), e.getValue()))
                .min(AURORA_REGION_ORDER)
                .map(AuroraRegionSummary::name)
                .orElse(null);
    }

    /**
     * Returns the region name of a scored aurora location, or {@code null} when the score,
     * its location, or its region (or the region's name) is missing/blank.
     */
    private static String regionNameOf(AuroraForecastScore score) {
        if (score == null || score.location() == null || score.location().getRegion() == null) {
            return null;
        }
        String name = score.location().getRegion().getName();
        return (name == null || name.isBlank()) ? null : name;
    }

    /**
     * Reduces a region's cached aurora scores to the figures the ranking compares: how many
     * locations are clear, the mean star rating, and the darkest Bortle class present.
     */
    private AuroraRegionSummary summariseAuroraRegion(String name, List<AuroraForecastScore> scores) {
        int clearCount = (int) scores.stream()
                .filter(s -> s.cloudPercent() < AURORA_CLEAR_CLOUD_PERCENT).count();
        double averageStars = scores.stream()
                .mapToInt(AuroraForecastScore::stars).average().orElse(0.0);
        int darkness = scores.stream()
                .map(s -> s.location().getBortleClass())
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .min().orElse(AURORA_DARKNESS_UNKNOWN);
        return new AuroraRegionSummary(name, clearCount, averageStars, darkness);
    }

    /**
     * Per-region aurora figures for ranking the best dark-sky region.
     *
     * @param name         region name
     * @param clearCount   number of scored locations below the clear-sky cloud threshold
     * @param averageStars mean aurora star rating across the region's scored locations
     * @param darknessRank darkest Bortle class present (1 = darkest; higher = brighter)
     */
    private record AuroraRegionSummary(String name, int clearCount,
            double averageStars, int darknessRank) {
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
        LocalDateTime now = LocalDateTime.now(clock);
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
                    .maxTokens(extendedThinking ? MAX_TOKENS_THINKING : maxTokens)
                    .systemOfTextBlockParams(List.of(
                            TextBlockParam.builder().text(BestBetPromptText.systemPrompt()).build()))
                    .addUserMessage(rollup.json());

            if (extendedThinking) {
                builder.thinking(ThinkingConfigAdaptive.builder().build());
            }

            Message response = anthropicApiClient.createMessage(builder.build());
            long durationMs = System.currentTimeMillis() - startMs;

            // Text blocks only — thinking blocks are filtered out here
            String raw = extractFirstText(response);

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
            List<BestBet> covered = applyCoverageAwareRanking(validated, rollup.coverageByKey());
            List<BestBet> enriched = enrichWithEventData(covered, days);

            LOG.info("Model comparison {} completed ({}ms, {} picks, thinking={})",
                    model, durationMs, enriched.size(), thinkingText != null);
            return new ModelComparisonResult(model, raw, parsed, enriched, durationMs, tokenUsage, thinkingText);
        } catch (Exception e) {
            LOG.warn("Model comparison {} failed: {}", model, e.getMessage());
            return new ModelComparisonResult(model, null, List.of(), List.of(), 0, TokenUsage.EMPTY, null);
        }
    }

    /**
     * Extracts the first text block from a Claude response, or {@code ""} when none is present.
     * Shared by {@link #advise}, {@link #callModel} and {@link #replayWithPrompt} so every path
     * reads the response identically.
     *
     * @param response the Claude message
     * @return the first text block's content, or an empty string
     */
    private static String extractFirstText(Message response) {
        return response.content().stream()
                .filter(ContentBlock::isText)
                .map(ContentBlock::asText)
                .map(TextBlock::text)
                .findFirst()
                .orElse("");
    }

    /**
     * Validation sets reconstructed from a stored rollup JSON for {@link #replayWithPrompt}.
     * A faithful inverse of what {@link #buildRollupJson} emits, so a replayed response passes
     * through the same gates production uses.
     *
     * @param validEvents   event identifiers present in the stored rollup
     * @param validRegions  region names present in the stored rollup
     * @param validDayNames day names present across the stored rollup's events
     * @param coverageByKey per-{@code event|region} Claude coverage parsed from the rollup
     */
    private record ReconstructedRollup(Set<String> validEvents, Set<String> validRegions,
            Set<String> validDayNames, Map<String, CandidateCoverage> coverageByKey) {
    }

    /**
     * Replays the advisor against a pre-captured or synthetic rollup JSON using an explicitly
     * supplied system prompt, bypassing {@link #buildRollupJson}. This is the before/after
     * validation primitive for advisor prompt changes: feed one stored rollup through two prompt
     * variants and diff the selected picks, or feed a synthetic rollup to assert a contract
     * (e.g. an all-STANDDOWN rollup must yield the stay-home pick) without waiting for a live
     * cycle.
     *
     * <p>The captured input lives in {@code api_call_log.request_body} (see {@link #advise}).
     * The validation sets are reconstructed from the rollup JSON itself, so the parsed picks
     * pass through the same {@link #validateAndFilterPicks} and {@link #applyCoverageAwareRanking}
     * gates production uses — the returned picks are what production would select. Display
     * enrichment ({@link #enrichWithEventData}) is skipped: it needs live {@code BriefingDay}
     * objects and changes neither selection nor ranking.
     *
     * @param rollupJson   the exact user-message rollup JSON (as captured in api_call_log)
     * @param systemPrompt the system prompt to evaluate with (pass {@link BestBetPromptText#systemPrompt()} for
     *                     the baseline before-state)
     * @param model        the model to call
     * @return the classified outcome (status + validated, ranked picks)
     * @throws JsonProcessingException if the rollup JSON cannot be parsed
     */
    public BestBetResult replayWithPrompt(String rollupJson, String systemPrompt, EvaluationModel model)
            throws JsonProcessingException {
        ReconstructedRollup sets = reconstructRollup(rollupJson);

        boolean extendedThinking = model.isExtendedThinking();
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model.getModelId())
                .maxTokens(extendedThinking ? MAX_TOKENS_THINKING : maxTokens)
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder().text(systemPrompt).build()))
                .addUserMessage(rollupJson);
        if (extendedThinking) {
            builder.thinking(ThinkingConfigAdaptive.builder().build());
        }

        Message response = anthropicApiClient.createMessage(builder.build());
        String raw = extractFirstText(response);

        BestBetResult parsed = classifyAndParse(raw);
        if (parsed.status() != BestBetStatus.SUCCESS_WITH_PICKS) {
            return parsed;
        }
        List<BestBet> validated = validateAndFilterPicks(
                parsed.picks(), sets.validEvents(), sets.validRegions(), sets.validDayNames());
        List<BestBet> covered = applyCoverageAwareRanking(validated, sets.coverageByKey());
        if (covered.isEmpty()) {
            return BestBetResult.failed();
        }
        return BestBetResult.withPicks(covered);
    }

    /**
     * Reconstructs the validation sets from a stored rollup JSON for {@link #replayWithPrompt}.
     *
     * <p>{@code daysAhead} in the reconstructed {@link CandidateCoverage} is fixed at 0 because
     * the coverage gate reads only {@code claudeRatedCount} — the horizon is informational and
     * a historical replay has no meaningful "today" to compute it against.
     *
     * @param rollupJson the stored rollup JSON
     * @return the reconstructed validation sets
     * @throws JsonProcessingException if the rollup JSON cannot be parsed
     */
    private ReconstructedRollup reconstructRollup(String rollupJson) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(rollupJson);
        Set<String> validEvents = new LinkedHashSet<>();
        Set<String> validRegions = new LinkedHashSet<>();
        Set<String> validDayNames = new LinkedHashSet<>();
        Map<String, CandidateCoverage> coverageByKey = new HashMap<>();

        JsonNode veNode = root.get("validEvents");
        if (veNode != null && veNode.isArray()) {
            veNode.forEach(n -> validEvents.add(n.asText()));
        }
        JsonNode vrNode = root.get("validRegions");
        if (vrNode != null && vrNode.isArray()) {
            vrNode.forEach(n -> validRegions.add(n.asText()));
        }
        JsonNode eventsNode = root.get("events");
        if (eventsNode != null && eventsNode.isArray()) {
            for (JsonNode event : eventsNode) {
                String eventId = event.path("event").asText(null);
                if (event.has("dayName")) {
                    validDayNames.add(event.get("dayName").asText());
                }
                JsonNode regions = event.get("regions");
                if (eventId == null || regions == null || !regions.isArray()) {
                    continue;
                }
                for (JsonNode region : regions) {
                    String name = region.path("name").asText(null);
                    if (name == null) {
                        continue;
                    }
                    int ratedCount = region.path("claudeRatedCount").asInt(0);
                    double avgRating = region.path("claudeAverageRating").asDouble(0.0);
                    coverageByKey.put(BestBetRanker.coverageKey(eventId, name),
                            new CandidateCoverage(ratedCount, 0, avgRating));
                }
            }
        }
        return new ReconstructedRollup(validEvents, validRegions, validDayNames, coverageByKey);
    }

    /**
     * Parses the Claude JSON response into a list of {@link BestBet} records.
     *
     * <p>Thin convenience wrapper over {@link #classifyAndParse(String)} that discards the
     * outcome status. Retained for callers that only need the picks (e.g. the model-comparison
     * utility); {@link #advise} uses {@code classifyAndParse} directly so it can report the
     * status.
     *
     * @param raw the raw Claude response text
     * @return parsed picks, or empty list if parsing fails
     */
    List<BestBet> parseBestBets(String raw) {
        return BestBetResponseParser.parseBestBets(raw, objectMapper);
    }

    /**
     * Parses the Claude JSON response and classifies the outcome into a {@link BestBetResult}.
     *
     * <p>Mapping:
     * <ul>
     *   <li>blank/missing content, a missing or non-array {@code picks} field, or an
     *       unparseable response from which nothing could be salvaged → {@code FAILED};</li>
     *   <li>a valid but empty {@code picks} array → {@code SUCCESS_NO_PICKS} (honest decline);</li>
     *   <li>one or more parsed picks, including picks salvaged from a truncated response →
     *       {@code SUCCESS_WITH_PICKS}.</li>
     * </ul>
     *
     * @param raw the raw Claude response text
     * @return the parse outcome (status + picks)
     */
    BestBetResult classifyAndParse(String raw) {
        return BestBetResponseParser.classifyAndParse(raw, objectMapper);
    }
}
