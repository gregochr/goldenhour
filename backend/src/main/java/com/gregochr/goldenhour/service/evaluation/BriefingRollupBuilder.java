package com.gregochr.goldenhour.service.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.CandidateCoverage;
import com.gregochr.goldenhour.model.RollupResult;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingRatingStats;
import com.gregochr.goldenhour.service.PlanRenderLimits;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import com.gregochr.goldenhour.service.TravelDayService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the region-level rollup JSON sent to Claude as the best-bet user message, and
 * reconstructs the validation sets from a stored rollup for the replay harness.
 *
 * <p>Instance-scoped seam extracted from {@code BriefingBestBetAdvisor}. It assembles the
 * compact JSON from the triage {@link BriefingDay} data, folds in cached PhotoCast evaluation
 * scores, tide classifications, stability, and (when an alert is active) the aurora event.
 * Logs under the {@link BriefingBestBetAdvisor} category so rollup diagnostics stay grouped
 * with the advisor's own output.
 */
public final class BriefingRollupBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(BriefingBestBetAdvisor.class);

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TravelDayService travelDayService;
    private final BriefingEvaluationService briefingEvaluationService;
    private final StabilitySnapshotProvider stabilitySnapshotProvider;
    private final AuroraStateCache auroraStateCache;
    private final AuroraRegionSelector auroraRegionSelector;

    /**
     * Constructs a {@code BriefingRollupBuilder}.
     *
     * @param objectMapper              Jackson mapper for JSON building and parsing
     * @param clock                     UTC clock supplying "now" and (via London) "today"
     * @param travelDayService          excludes travel-day events from the candidate rollup
     * @param briefingEvaluationService cached Claude evaluation scores from drill-down
     * @param stabilitySnapshotProvider provides the latest stability summary for region rollup
     * @param auroraStateCache          read-only access to the current aurora alert state
     * @param auroraRegionSelector      derives the best dark-sky region for the aurora event
     */
    public BriefingRollupBuilder(ObjectMapper objectMapper, Clock clock,
            TravelDayService travelDayService,
            BriefingEvaluationService briefingEvaluationService,
            StabilitySnapshotProvider stabilitySnapshotProvider,
            AuroraStateCache auroraStateCache,
            AuroraRegionSelector auroraRegionSelector) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.travelDayService = travelDayService;
        this.briefingEvaluationService = briefingEvaluationService;
        this.stabilitySnapshotProvider = stabilitySnapshotProvider;
        this.auroraStateCache = auroraStateCache;
        this.auroraRegionSelector = auroraRegionSelector;
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
    public RollupResult buildRollupJson(List<BriefingDay> days, LocalDateTime now)
            throws JsonProcessingException {
        LocalDate today = ForecastHorizon.today(clock);
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
                if (eventCount >= PlanRenderLimits.MAX_VISIBLE_EVENTS) {
                    break;
                }
                eventCount++;
                // Locale.ROOT, not the default locale: this id is matched key-for-key by
                // BriefingHonestyFilter's Best Bet withdrawal. Under a Turkish default locale
                // SUNRISE lowercases with a dotless i, the keys stop matching, and the
                // withdrawal silently no-ops for sunrise picks while still working for sunset.
                String eventId = day.date() + "_"
                        + es.targetType().name().toLowerCase(java.util.Locale.ROOT);
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
            if (eventCount >= PlanRenderLimits.MAX_VISIBLE_EVENTS) {
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
     * Reduces a region's enriched slot ratings to the two {@link BriefingRatingStats.Stats} the
     * prompt node needs, or {@code null} for either when nothing valid is rated.
     *
     * <p><b>Two rollups over one slot list, because the node asks two different questions</b> —
     * the same split {@code BriefingGlossService} makes, deliberately identical so the two prompts
     * built in one briefing cycle cannot describe the same region differently.
     *
     * <p>The COUNTS are <em>coverage</em>: how many of this region's locations were evaluated. A
     * rated wood counts, because it genuinely was evaluated — by the woodland prompt — and
     * {@code BestBetPromptText} defines the field that way and reasons from it that way ("prefer a
     * nearer, better-evaluated region"). They stay in step with the sibling {@code totalLocations}
     * and with {@code BriefingRegion.scoredLocationCount}, so every ratio Claude can form from the
     * node is over one population.
     *
     * <p>The AVERAGE is the <em>sky's</em>, over the region's voting slots
     * ({@link BriefingSlot#votingSlots} — non-canopy, falling back to all of them for an all-canopy
     * region). A woodland verdict runs on inverted polarity: a canopy GO means heavy cloud and
     * mist. This is the figure the Also-Good decision is taken on: {@code BestBetPromptText} states
     * the 3.0 absolute floor for Pick 2 and <em>Claude</em> applies it to this number — there is no
     * Java predicate behind it ({@code AlsoGoodFloor.MIN_ABSOLUTE} is a diagnostic mirror that, by
     * its own javadoc, "never gates selection or ranking"). A 5★ bluebell wood averaged in here
     * would therefore put a region over that floor for a sky that never reached it. It is
     * omitted rather than zeroed when no slot votes: a fabricated 0.0 is a claim about the sky that
     * nothing measured.
     *
     * <p><b>The canopy flag comes from the slots; the ratings stay on the cache.</b> The cache is
     * keyed by location NAME and carries no canopy flag, so the region's own slots are the only
     * place it lives. {@link BriefingSlot#votingSlots} is still the owner of the rule — this method
     * calls it and treats whatever it did <em>not</em> return as canopy, so the all-canopy fallback
     * cannot be dropped here independently.
     *
     * <p>⚠️ <b>Pre-existing residual: the join is fail-open.</b> The cache is retained across
     * refreshes and outlives the enabled roster, so a location since disabled, renamed or moved to
     * another region still answers under a name no slot claims — and an unmatched name counts as
     * sky. For a wood that is the very defect above, surviving in one narrow case. Reading
     * {@code slot.claudeRating()} instead would close it, and {@code BriefingGlossService} does
     * exactly that one class away, but the cache read here is a pinned contract
     * ({@code BriefingBestBetAdvisorTest.cacheLookupUsesExactParameters}) and not every caller of
     * {@code advise} hands over enriched days. Left as-is deliberately; it is a roster-hygiene bug
     * older than this filter, and it inflates the counts today with or without it.
     *
     * @param region     the enriched region
     * @param date       the target date, for stats logging
     * @param targetType the solar event, for stats logging
     * @return the coverage and sky-average stats, each null when it has nothing valid
     */
    private RegionStats computeRegionStats(BriefingRegion region, LocalDate date,
            TargetType targetType) {
        Map<String, BriefingEvaluationResult> cached =
                briefingEvaluationService.getCachedScores(region.regionName(), date, targetType);
        if (cached.isEmpty()) {
            return new RegionStats(null, null);
        }
        List<BriefingRatingStats.Entry> all = cached.values().stream()
                .map(r -> new BriefingRatingStats.Entry(r.locationName(), r.rating()))
                .toList();
        Set<String> canopyNames = canopyNames(region.slots());
        List<BriefingRatingStats.Entry> voting = all.stream()
                .filter(e -> !canopyNames.contains(e.locationName()))
                .toList();
        return new RegionStats(
                statsOver(all, region, date, targetType),
                statsOver(voting, region, date, targetType));
    }

    /**
     * The names of the slots that do NOT vote, derived from {@link BriefingSlot#votingSlots} so
     * the all-canopy fallback cannot be dropped here independently of the rule's owner.
     *
     * @param slots the region's slots
     * @return the non-voting (canopy) location names; empty for an all-canopy region
     */
    private static Set<String> canopyNames(List<BriefingSlot> slots) {
        Set<String> voting = BriefingSlot.votingSlots(slots).stream()
                .map(BriefingSlot::locationName)
                .collect(java.util.stream.Collectors.toSet());
        return slots.stream()
                .map(BriefingSlot::locationName)
                .filter(name -> !voting.contains(name))
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Reduces the given rating entries to stats, or {@code null} when none are valid.
     *
     * @param entries    the entries to reduce
     * @param region     the owning region, for stats logging
     * @param date       the target date, for stats logging
     * @param targetType the solar event, for stats logging
     * @return the stats, or null when empty
     */
    private BriefingRatingStats.Stats statsOver(List<BriefingRatingStats.Entry> entries,
            BriefingRegion region, LocalDate date, TargetType targetType) {
        BriefingRatingStats.Stats stats =
                BriefingRatingStats.compute(entries, region.regionName(), date, targetType);
        return stats.isEmpty() ? null : stats;
    }

    /**
     * A region's two rating rollups: coverage over every slot, and the sky average over the
     * voting slots. Either may be null when it has nothing valid to report.
     *
     * @param coverage the count rollup over all slots, or null
     * @param sky      the average rollup over voting slots, or null
     */
    private record RegionStats(BriefingRatingStats.Stats coverage, BriefingRatingStats.Stats sky) {
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
                        + es.targetType().name().toLowerCase(java.util.Locale.ROOT);
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
                .filter(s -> s.tide().heightAboveP95())
                .map(BriefingSlot::locationName)
                .toList();

        // Legacy: statistical "spring tide" detection (>125% avg)
        boolean hasSpringTide = region.slots().stream()
                .anyMatch(s -> s.tide().heightAboveSpringThreshold());

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

        // Claude evaluation score distribution, off the already-enriched slots. Computed once
        // and reused for the coverage gate so the two can never disagree. Note this no longer
        // shares a source with logCacheCoverage below, which still counts raw cache entries: the
        // two can differ for a cached score whose location has left the enabled roster, and the
        // log line is the one that over-reports there.
        RegionStats stats = computeRegionStats(region, date, targetType);
        appendClaudeScores(regionNode, stats);

        // Stability rollup: worst-case across grid cells containing this region's locations
        appendStabilityToRegion(regionNode, region);

        // Built from the same two numbers the JSON node carries, in the same order, so
        // reconstructRollup's replay of a stored rollup reproduces this exactly.
        return new CandidateCoverage(
                stats.coverage() == null ? 0 : stats.coverage().count(),
                daysAhead,
                stats.sky() == null ? 0.0 : stats.sky().averageRating());
    }

    /**
     * Appends the Claude evaluation score distribution to the region JSON node.
     *
     * <p>When nothing is rated the whole block is omitted and the prompt falls back to
     * verdict-only data. When something is rated but nothing that votes is — a region whose only
     * scored location is a wood — the counts appear without an average, which says exactly what
     * happened: evaluated, but not for sky colour.
     *
     * @param regionNode the region node to append to
     * @param stats      the region's coverage and sky rollups
     */
    private void appendClaudeScores(ObjectNode regionNode, RegionStats stats) {
        if (stats.coverage() == null) {
            return;
        }
        regionNode.put("claudeRatedCount", stats.coverage().count());
        regionNode.put("claudeHighRatedCount", stats.coverage().highRated());
        regionNode.put("claudeMediumRatedCount", stats.coverage().mediumRated());
        if (stats.sky() != null) {
            regionNode.put("claudeAverageRating", stats.sky().averageRating());
        }
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
        String region = auroraRegionSelector.bestAuroraRegion();
        if (region != null) {
            auroraNode.put("region", region);
        }
        return region;
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
     * Validation sets reconstructed from a stored rollup JSON for the advisor replay harness.
     * A faithful inverse of what {@link #buildRollupJson} emits, so a replayed response passes
     * through the same gates production uses.
     *
     * @param validEvents   event identifiers present in the stored rollup
     * @param validRegions  region names present in the stored rollup
     * @param validDayNames day names present across the stored rollup's events
     * @param coverageByKey per-{@code event|region} Claude coverage parsed from the rollup
     */
    public record ReconstructedRollup(Set<String> validEvents, Set<String> validRegions,
            Set<String> validDayNames, Map<String, CandidateCoverage> coverageByKey) {
    }

    /**
     * Reconstructs the validation sets from a stored rollup JSON for the advisor replay harness.
     *
     * <p>{@code daysAhead} in the reconstructed {@link CandidateCoverage} is fixed at 0 because
     * the coverage gate reads only {@code claudeRatedCount} — the horizon is informational and
     * a historical replay has no meaningful "today" to compute it against.
     *
     * @param rollupJson the stored rollup JSON
     * @return the reconstructed validation sets
     * @throws JsonProcessingException if the rollup JSON cannot be parsed
     */
    public ReconstructedRollup reconstructRollup(String rollupJson) throws JsonProcessingException {
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
}
