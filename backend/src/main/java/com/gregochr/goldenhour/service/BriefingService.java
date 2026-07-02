package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.DailyBriefingCacheEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AuroraTonightSummary;
import com.gregochr.goldenhour.model.AuroraTomorrowSummary;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BestBetResult;
import com.gregochr.goldenhour.model.BestBetStatus;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingRefreshedEvent;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.repository.DailyBriefingCacheRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.service.evaluation.BluebellGlossService;
import com.gregochr.goldenhour.service.evaluation.BriefingBestBetAdvisor;
import com.gregochr.goldenhour.service.evaluation.BriefingGlossService;
import com.gregochr.goldenhour.util.GeoUtils;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Orchestrates the daily briefing: fetches live Open-Meteo weather and existing DB tide data
 * for all enabled colour locations, rolls results up by region per solar event, and caches
 * the result for the frontend to serve instantly.
 *
 * <p>No Claude calls. No directional cloud sampling. No cloud approach analysis.
 * Cost: ~2 free Open-Meteo API calls per location per refresh.
 */
@Service
public class BriefingService {

    private static final Logger LOG = LoggerFactory.getLogger(BriefingService.class);

    private final LocationService locationService;
    private final OpenMeteoClient openMeteoClient;
    private final JobRunService jobRunService;
    private final DailyBriefingCacheRepository briefingCacheRepository;
    private final LocationRepository locationRepository;
    private final ObjectMapper objectMapper;
    private final BriefingHeadlineGenerator headlineGenerator;
    private final BriefingBestBetAdvisor bestBetAdvisor;
    private final BriefingGlossService glossService;
    private final BluebellGlossService bluebellGlossService;
    private final BriefingAuroraSummaryBuilder auroraSummaryBuilder;
    private final BriefingHierarchyBuilder hierarchyBuilder;
    private final BriefingSlotBuilder slotBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final HotTopicAggregator hotTopicAggregator;
    private final BriefingEvaluationService briefingEvaluationService;
    private final EvaluationViewService evaluationViewService;
    private final com.gregochr.goldenhour.service.pipeline.BestBetFallbackService bestBetFallbackService;
    private final SeasonalWindow bluebellSeason;
    private final NlcClarityService nlcClarityService;
    private final java.time.Clock clock;

    /** UK civil-date zone for "today" derivation. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    /** Horizon offset distance in metres — geometric horizon for low cloud at ~1 km altitude. */
    private static final double HORIZON_OFFSET_METRES = 113_000.0;

    /** Sunrise bearing (due east). */
    private static final double SUNRISE_BEARING = 90.0;

    /** Sunset bearing (due west). */
    private static final double SUNSET_BEARING = 270.0;

    private final AtomicReference<DailyBriefingResponse> cache = new AtomicReference<>();
    private final AtomicReference<DailyBriefingResponse> lastKnownGood = new AtomicReference<>();

    @Autowired(required = false)
    private CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * Coverage fraction below which the honesty filter flags a region as
     * lightly evaluated on the API read path. A region with a positive but
     * below-threshold {@code scoredLocationCount / rosterSize} is framed as
     * covering only the evaluated spots rather than the whole roster. Tunable
     * without redeploy; {@code 0.0} disables the tier.
     */
    @Value("${photocast.briefing.min-coverage-ratio:0.5}")
    private double minCoverageRatio;

    /**
     * Constructs a {@code BriefingService}.
     *
     * @param locationService         service for retrieving enabled locations
     * @param openMeteoClient         resilient Open-Meteo API client
     * @param jobRunService           service for job run tracking
     * @param briefingCacheRepository repository for persisting the briefing across restarts
     * @param locationRepository      repository for persisting grid coordinates on locations
     * @param objectMapper            Jackson mapper for JSON serialization
     * @param headlineGenerator       generator for the briefing headline
     * @param bestBetAdvisor          Claude Haiku advisor producing ranked best-bet picks
     * @param glossService            Claude gloss service for per-region commentary
     * @param bluebellGlossService    Claude gloss service for bluebell region commentary
     * @param auroraSummaryBuilder    builder for aurora tonight/tomorrow summaries
     * @param hierarchyBuilder        builder for the day/event/region hierarchy
     * @param slotBuilder                builder for individual briefing slots
     * @param eventPublisher             Spring event publisher for cache invalidation
     * @param hotTopicAggregator         aggregator for seasonal and special-interest hot topics
     * @param briefingEvaluationService  cached Claude evaluation scores (lazy to break cycle)
     * @param evaluationViewService      merged evaluation view service (lazy to break cycle)
     * @param bestBetFallbackService     serves the fail-safe stale best-bet fallback on FAILED
     * @param bluebellSeason             the configured bluebell season window
     * @param nlcClarityService          caches which nights have a clear dark-sky NLC chance
     * @param clock                      UTC clock supplying "now" and (via London) "today"
     */
    public BriefingService(LocationService locationService,
            OpenMeteoClient openMeteoClient,
            JobRunService jobRunService, DailyBriefingCacheRepository briefingCacheRepository,
            LocationRepository locationRepository,
            ObjectMapper objectMapper,
            BriefingHeadlineGenerator headlineGenerator, BriefingBestBetAdvisor bestBetAdvisor,
            BriefingGlossService glossService,
            BluebellGlossService bluebellGlossService,
            BriefingAuroraSummaryBuilder auroraSummaryBuilder,
            BriefingHierarchyBuilder hierarchyBuilder,
            BriefingSlotBuilder slotBuilder,
            ApplicationEventPublisher eventPublisher,
            HotTopicAggregator hotTopicAggregator,
            @Lazy BriefingEvaluationService briefingEvaluationService,
            @Lazy EvaluationViewService evaluationViewService,
            com.gregochr.goldenhour.service.pipeline.BestBetFallbackService bestBetFallbackService,
            SeasonalWindow bluebellSeason,
            NlcClarityService nlcClarityService,
            java.time.Clock clock) {
        this.locationService = locationService;
        this.openMeteoClient = openMeteoClient;
        this.jobRunService = jobRunService;
        this.briefingCacheRepository = briefingCacheRepository;
        this.locationRepository = locationRepository;
        this.objectMapper = objectMapper;
        this.headlineGenerator = headlineGenerator;
        this.bestBetAdvisor = bestBetAdvisor;
        this.glossService = glossService;
        this.bluebellGlossService = bluebellGlossService;
        this.auroraSummaryBuilder = auroraSummaryBuilder;
        this.hierarchyBuilder = hierarchyBuilder;
        this.slotBuilder = slotBuilder;
        this.eventPublisher = eventPublisher;
        this.hotTopicAggregator = hotTopicAggregator;
        this.briefingEvaluationService = briefingEvaluationService;
        this.evaluationViewService = evaluationViewService;
        this.bestBetFallbackService = bestBetFallbackService;
        this.bluebellSeason = bluebellSeason;
        this.nlcClarityService = nlcClarityService;
        this.clock = clock;
    }

    /**
     * Loads the last persisted briefing from the database into the in-memory cache on startup.
     *
     * <p>This ensures the briefing is available immediately after a restart without waiting
     * for the next scheduled cron run.
     */
    @PostConstruct
    void loadPersistedBriefing() {
        briefingCacheRepository.findById(1).ifPresent(entity -> {
            try {
                DailyBriefingResponse persisted = objectMapper.readValue(
                        entity.getPayload(), DailyBriefingResponse.class);
                cache.set(persisted);
                lastKnownGood.set(persisted);
                LOG.info("Loaded persisted briefing from DB (generated {})", entity.getGeneratedAt());
            } catch (Exception e) {
                LOG.warn("Could not deserialize persisted briefing — will regenerate on next scheduled run", e);
            }
        });
    }

    /**
     * Returns the cached daily briefing with live aurora state overlaid.
     *
     * <p>When the aurora FSM is idle, {@code buildAuroraTonight()} returns null instantly
     * with zero overhead. When active, the 5-minute cache in the builder keeps API calls
     * minimal.
     *
     * @return the most recent briefing response with live aurora, or null
     */
    public DailyBriefingResponse getCachedBriefing() {
        DailyBriefingResponse cached = cache.get();
        if (cached == null) {
            return null;
        }
        try {
            AuroraTonightSummary liveTonight = auroraSummaryBuilder.buildAuroraTonightCached();
            AuroraTomorrowSummary liveTomorrow = auroraSummaryBuilder.buildAuroraTomorrowCached();

            // Overlay live hot topics so simulation toggles take effect immediately
            // without requiring a full briefing refresh.
            LocalDate today = LocalDate.now(clock.withZone(LONDON));
            List<HotTopic> rawTopics = hotTopicAggregator.getHotTopics(today, today.plusDays(3));
            List<HotTopic> liveTopics = rawTopics == null ? List.of() : rawTopics;

            if (Objects.equals(cached.auroraTonight(), liveTonight)
                    && Objects.equals(cached.auroraTomorrow(), liveTomorrow)
                    && Objects.equals(cached.hotTopics(), liveTopics)) {
                return cached;
            }
            return new DailyBriefingResponse(
                    cached.generatedAt(), cached.headline(), cached.days(), cached.bestBets(),
                    liveTonight, liveTomorrow, cached.stale(), cached.partialFailure(),
                    cached.failedLocationCount(), cached.bestBetModel(),
                    liveTopics, cached.seasonalFeatures());
        } catch (Exception e) {
            LOG.warn("Aurora overlay failed — returning briefing without live aurora: {}",
                    e.getMessage());
            return cached;
        }
    }

    /**
     * API-facing variant of {@link #getCachedBriefing()} that applies the Gate 2
     * honesty filter: any region whose {@code scoredLocationCount == 0} has its
     * user-facing display fields (verdict pill, summary line, gloss prose,
     * per-location slot list) rewritten so the response cannot advertise a
     * positive verdict for a region in which zero locations were ever
     * evaluated.
     *
     * <p>Internal callers (the batch task collector, the SSE drill-down service,
     * the model-comparison test harness) continue to call {@link
     * #getCachedBriefing()} so they see the untransformed triage data they
     * depend on. See {@link BriefingHonestyFilter} for the rationale.
     *
     * @return the cached briefing with honesty filter applied, or {@code null}
     *         if no briefing has been built yet
     */
    public DailyBriefingResponse getCachedBriefingForApi() {
        return applyBestBetFallback(
                BriefingHonestyFilter.apply(getCachedBriefing(), minCoverageRatio));
    }

    /**
     * Fail-safe best-bet fallback applied at serve time: when the served response's best-bet
     * outcome is {@link BestBetStatus#FAILED} and a fresh-enough prior successful pick exists,
     * substitute those picks (the frontend renders them with a stale chip, since the status
     * stays {@code FAILED}). Re-evaluated on every request so freshness (event-not-passed, within
     * the age ceiling) is always current — the fallback is never baked into the persisted cache.
     *
     * <p>No-op unless the status is {@code FAILED}: an honest {@code SUCCESS_NO_PICKS} keeps its
     * empty state, and a {@code FAILED} with no fresh-enough prior pick falls through to the
     * honest empty state rather than resurrecting a stale or passed pick.
     *
     * @param response the honesty-filtered response (may be null)
     * @return the response, possibly decorated with fallback picks
     */
    private DailyBriefingResponse applyBestBetFallback(DailyBriefingResponse response) {
        if (response == null || response.bestBetStatus() != BestBetStatus.FAILED) {
            return response;
        }
        List<BestBet> fallback = bestBetFallbackService.findFreshFallback();
        if (fallback.isEmpty()) {
            return response;
        }
        return new DailyBriefingResponse(
                response.generatedAt(), response.headline(), response.days(),
                fallback, response.auroraTonight(), response.auroraTomorrow(),
                response.stale(), response.partialFailure(), response.failedLocationCount(),
                response.bestBetModel(), response.hotTopics(), response.seasonalFeatures(),
                response.bestBetStatus());
    }

    /**
     * Returns the cached briefing days without overlaying live state.
     *
     * <p>Used by hot topic strategies to scan triage data (e.g. tide
     * classifications) without triggering a recursive hot topic re-detection.
     *
     * @return cached briefing days, or null if no briefing has been generated yet
     */
    public List<BriefingDay> getCachedDays() {
        DailyBriefingResponse cached = cache.get();
        return cached != null ? cached.days() : null;
    }

    /**
     * Refreshes the daily briefing by fetching live weather data for all enabled colour
     * locations across today and tomorrow, rolling up by region per solar event.
     *
     * <p>Logs a {@link RunType#BRIEFING} job run for metrics tracking.
     */
    public void refreshBriefing() {
        LOG.info("Daily briefing refresh started");
        long briefingStart = System.currentTimeMillis();
        JobRunEntity jobRun = jobRunService.startRun(RunType.BRIEFING, false, null);

        List<LocationEntity> colourLocations = locationService.findAllEnabled().stream()
                .filter(this::isColourLocation)
                .toList();

        if (colourLocations.isEmpty()) {
            LOG.info("No enabled colour locations — skipping briefing");
            jobRunService.completeRun(jobRun, 0, 0);
            return;
        }

        LocalDate today = LocalDate.now(clock.withZone(LONDON));
        List<LocalDate> dates = List.of(today, today.plusDays(1), today.plusDays(2), today.plusDays(3));

        int succeeded = 0;
        int failed = 0;

        // Fetch weather sequentially — the @RateLimiter on fetchForecast() throttles naturally
        // at 8 calls/second, so sequential fetching scales to any number of locations without
        // exhausting the rate limiter.
        List<BriefingSlotBuilder.LocationWeather> locationWeathers =
                fetchWeatherSequential(colourLocations, jobRun);

        // Fetch horizon cloud data (one batch call for all unique horizon grid cells)
        HorizonCloudData horizonData = fetchHorizonCloud(colourLocations, jobRun);

        // Build slots for each location × date × event type (filtered by solar event preference)
        List<BriefingSlot> allSlots = new ArrayList<>();
        for (BriefingSlotBuilder.LocationWeather lw : locationWeathers) {
            if (lw.forecast() == null) {
                failed++;
                continue;
            }
            succeeded++;
            for (LocalDate date : dates) {
                for (TargetType eventType : List.of(TargetType.SUNRISE, TargetType.SUNSET)) {
                    if (!lw.location().supportsTargetType(eventType)) {
                        continue;
                    }
                    OpenMeteoForecastResponse horizonForecast =
                            horizonData.getForLocation(lw.location().getId(), eventType);
                    BriefingSlot slot = slotBuilder.buildSlot(lw, date, eventType,
                            horizonForecast);
                    if (slot != null) {
                        allSlots.add(slot);
                    }
                }
            }
        }

        // Group into days → event summaries → regions
        List<BriefingDay> days = hierarchyBuilder.buildDays(allSlots, colourLocations, dates);

        // Enrich slots with cached Claude evaluation scores (from prior batch runs)
        days = enrichWithCachedScores(days);

        // Enrich GO/MARGINAL regions with Claude-generated one-line gloss
        if (succeeded > 0) {
            days = glossService.generateGlosses(days, jobRun.getId());
        }

        String headline = headlineGenerator.generateHeadline(days);
        // The advisor reports an explicit outcome status so the served response can tell an
        // honest empty result apart from a failure (and the latter can trigger the fallback).
        // With zero successful locations the advisor is not run — that is an honest no-data
        // empty, not a failure.
        BestBetResult bestBetResult = succeeded > 0
                ? bestBetAdvisor.advise(days, jobRun.getId(), Map.of())
                : BestBetResult.noPicks();
        List<BestBet> bestBets = bestBetResult.picks();
        BestBetStatus bestBetStatus = bestBetResult.status();
        AuroraTonightSummary auroraTonight = auroraSummaryBuilder.buildAuroraTonight();
        AuroraTomorrowSummary auroraTomorrow = auroraSummaryBuilder.buildAuroraTomorrow();

        boolean partialFailure = failed > 0;
        int total = succeeded + failed;
        boolean aboveThreshold = total == 0 || (succeeded * 100 / total) >= 50;

        long totalMs = System.currentTimeMillis() - briefingStart;
        String circuit = circuitState();

        // Refresh the NLC clarity cache — samples the northern-horizon transect at dark-sky
        // locations for each in-season night, so the NLC hot topic gates on real clear-northern-sky
        // nights. Runs its own cloud-only fetch (one deduped batch), independent of colour weather.
        try {
            nlcClarityService.refresh(dates);
        } catch (Exception e) {
            LOG.warn("NLC clarity refresh failed — NLC topic may be suppressed: {}", e.getMessage());
        }

        List<HotTopic> hotTopics = hotTopicAggregator.getHotTopics(today, today.plusDays(3));
        hotTopics = bluebellGlossService.enrichGlosses(hotTopics);
        List<String> seasonalFeatures = bluebellSeason.isActive(today)
                ? List.of("BLUEBELL") : List.of();

        if (aboveThreshold) {
            DailyBriefingResponse response = new DailyBriefingResponse(
                    LocalDateTime.now(clock), headline, days, bestBets,
                    auroraTonight, auroraTomorrow, false, partialFailure, failed,
                    bestBetAdvisor.getModelDisplayName(), hotTopics, seasonalFeatures,
                    bestBetStatus);
            cache.set(response);
            lastKnownGood.set(response);
            persistBriefing(response);
            eventPublisher.publishEvent(new BriefingRefreshedEvent(this));
            jobRunService.completeRun(jobRun, succeeded, failed, dates);
            LOG.info("Briefing complete: {}/{} succeeded, {} failed, stale=false, circuit={}, duration={}ms",
                    succeeded, total, failed, circuit, totalMs);
        } else {
            DailyBriefingResponse lkg = lastKnownGood.get();
            if (lkg != null) {
                DailyBriefingResponse staleResponse = new DailyBriefingResponse(
                        lkg.generatedAt(), lkg.headline(), lkg.days(), lkg.bestBets(),
                        auroraTonight, auroraTomorrow, true, true, failed,
                        lkg.bestBetModel(), hotTopics, seasonalFeatures,
                        lkg.bestBetStatus());
                cache.set(staleResponse);
                LOG.warn("Briefing complete: {}/{} succeeded, {} failed — below 50% threshold, "
                        + "serving stale=true (LKG from {}), circuit={}, duration={}ms",
                        succeeded, total, failed, lkg.generatedAt(), circuit, totalMs);
            } else {
                DailyBriefingResponse response = new DailyBriefingResponse(
                        LocalDateTime.now(clock), headline, days, bestBets,
                        auroraTonight, auroraTomorrow, false, partialFailure, failed,
                        bestBetAdvisor.getModelDisplayName(), hotTopics, seasonalFeatures,
                        bestBetStatus);
                cache.set(response);
                LOG.warn("Briefing complete: {}/{} succeeded, {} failed — below threshold, "
                        + "no LKG; using partial, circuit={}, duration={}ms",
                        succeeded, total, failed, circuit, totalMs);
            }
            jobRunService.completeRun(jobRun, succeeded, failed, dates);
        }
    }

    private String circuitState() {
        if (circuitBreakerRegistry == null) {
            return "UNKNOWN";
        }
        try {
            return circuitBreakerRegistry.circuitBreaker("open-meteo-briefing").getState().name();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /**
     * Serializes the briefing response and upserts the single-row DB cache (id = 1).
     * Failures are logged as warnings and do not affect the in-memory cache or job run metrics.
     *
     * @param response the briefing response to persist
     */
    private void persistBriefing(DailyBriefingResponse response) {
        try {
            DailyBriefingCacheEntity entity = new DailyBriefingCacheEntity();
            entity.setId(1);
            entity.setGeneratedAt(response.generatedAt());
            entity.setPayload(objectMapper.writeValueAsString(response));
            briefingCacheRepository.save(entity);
            LOG.debug("Persisted briefing to DB (generated {})", response.generatedAt());
        } catch (Exception e) {
            LOG.warn("Could not persist briefing to DB — in-memory cache still updated", e);
        }
    }

    /**
     * Determines whether a location is a colour photography location (not pure wildlife).
     *
     * @param location the location to check
     * @return true if the location has at least one colour type (LANDSCAPE, SEASCAPE, WATERFALL)
     */
    boolean isColourLocation(LocationEntity location) {
        if (location.getLocationType().isEmpty()) {
            return true;
        }
        return location.getLocationType().stream()
                .anyMatch(t -> t != LocationType.WILDLIFE);
    }

    /**
     * Walks the day/event/region hierarchy and populates each slot's Claude fields from the
     * evaluation cache. Returns a rebuilt hierarchy with enriched slots; the original is unchanged.
     */
    private List<BriefingDay> enrichWithCachedScores(List<BriefingDay> days) {
        List<BriefingDay> enrichedDays = new ArrayList<>(days.size());
        for (BriefingDay day : days) {
            List<BriefingEventSummary> enrichedEvents = new ArrayList<>();
            for (BriefingEventSummary es : day.eventSummaries()) {
                List<BriefingRegion> enrichedRegions = new ArrayList<>();
                for (BriefingRegion region : es.regions()) {
                    Map<String, BriefingEvaluationResult> cached =
                            evaluationViewService.getScoresForEnrichment(
                                    region.regionName(), day.date(), es.targetType());
                    List<BriefingSlot> enrichedSlots = region.slots().stream()
                            .map(slot -> enrichSlot(slot, cached))
                            .toList();
                    List<BriefingRatingStats.Entry> ratingEntries = enrichedSlots.stream()
                            .map(s -> new BriefingRatingStats.Entry(
                                    s.locationName(), s.claudeRating()))
                            .toList();
                    BriefingRatingStats.Stats stats = BriefingRatingStats.compute(
                            ratingEntries, region.regionName(), day.date(), es.targetType());
                    if (stats.isEmpty()) {
                        LOG.info("[ZERO COVERAGE] region={} date={} target={} "
                                        + "briefingVerdict={} scoredCount=0 "
                                        + "(honesty filter will rewrite at API read time — "
                                        + "post-Gate-2 this should only fire on batch "
                                        + "failures or all-hard-constrained regions)",
                                region.regionName(), day.date(), es.targetType(),
                                region.verdict());
                    }
                    enrichedRegions.add(new BriefingRegion(
                            region.regionName(), region.verdict(), region.summary(),
                            region.tideHighlights(), enrichedSlots,
                            region.regionTemperatureCelsius(),
                            region.regionApparentTemperatureCelsius(),
                            region.regionWindSpeedMs(), region.regionWeatherCode(),
                            region.glossHeadline(), region.glossDetail(),
                            BriefingRatingStats.resolveRegionDisplayVerdict(
                                    stats, region.verdict()),
                            stats.count()));
                }
                enrichedEvents.add(new BriefingEventSummary(
                        es.targetType(), enrichedRegions, es.unregioned()));
            }
            enrichedDays.add(new BriefingDay(day.date(), enrichedEvents));
        }
        return enrichedDays;
    }

    /**
     * Enriches a single slot with cached Claude scores if available.
     */
    private BriefingSlot enrichSlot(BriefingSlot slot,
            Map<String, BriefingEvaluationResult> cached) {
        BriefingEvaluationResult eval = cached.get(slot.locationName());
        if (eval != null && eval.rating() != null) {
            return slot.withClaudeScores(eval.rating(), eval.fierySkyPotential(),
                    eval.goldenHourPotential(), eval.summary(), eval.headline());
        }
        return slot;
    }

    /**
     * Fetches Open-Meteo forecast data for all locations sequentially, deduplicating by grid cell.
     *
     * <p>Open-Meteo snaps coordinates to the nearest ~2 km grid point, so locations sharing
     * a grid cell get identical weather data. This method groups locations by their known grid
     * cell (or treats ungrouped locations individually), fetches once per distinct group, and
     * fans the result out to all members. Grid coordinates discovered from the response are
     * persisted back to the location entity for future deduplication.
     *
     * <p>Sequential fetching lets the {@code @RateLimiter} on
     * {@link OpenMeteoClient#fetchForecast} throttle calls naturally at 8/s with no
     * queuing pressure.
     *
     * @param locations the locations to fetch weather for
     * @param jobRun    the job run for API call tracking
     * @return list of location-weather pairs (forecast may be null on failure)
     */
    private List<BriefingSlotBuilder.LocationWeather> fetchWeatherSequential(
            List<LocationEntity> locations, JobRunEntity jobRun) {

        // Group locations by grid cell key — ungrouped locations get a unique key
        Map<String, List<LocationEntity>> groups = new LinkedHashMap<>();
        for (LocationEntity loc : locations) {
            String key = loc.hasGridCell()
                    ? loc.gridCellKey()
                    : "ungrouped-" + loc.getId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(loc);
        }

        LOG.info("Briefing weather fetch: {} locations → {} grid cells",
                locations.size(), groups.size());

        // Collect one representative coordinate per grid cell group
        List<String> groupKeys = new ArrayList<>(groups.keySet());
        List<double[]> coords = new ArrayList<>();
        for (String key : groupKeys) {
            LocationEntity rep = groups.get(key).getFirst();
            coords.add(new double[]{rep.getLat(), rep.getLon()});
        }

        List<BriefingSlotBuilder.LocationWeather> results = new ArrayList<>();
        long startMs = System.currentTimeMillis();
        try {
            List<OpenMeteoForecastResponse> responses =
                    openMeteoClient.fetchForecastBriefingBatch(coords);
            long durationMs = System.currentTimeMillis() - startMs;
            long populated = responses.stream().filter(r -> r != null).count();
            LOG.info("Briefing weather fetch complete: {}/{} forecasts returned ({}ms)",
                    populated, coords.size(), durationMs);
            jobRunService.logApiCall(jobRun.getId(),
                    com.gregochr.goldenhour.entity.ServiceName.OPEN_METEO_FORECAST,
                    "GET", "briefing-forecast-batch(" + coords.size() + ")", null,
                    durationMs, 200, null, true, null);

            for (int i = 0; i < groupKeys.size(); i++) {
                List<LocationEntity> group = groups.get(groupKeys.get(i));
                OpenMeteoForecastResponse forecast = responses.get(i);

                if (forecast != null) {
                    captureGridCoordinates(forecast, group);
                }

                for (LocationEntity loc : group) {
                    results.add(new BriefingSlotBuilder.LocationWeather(loc, forecast));
                }
            }
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            LOG.warn("Briefing weather batch fetch failed: {}", e.getMessage());
            jobRunService.logApiCall(jobRun.getId(),
                    com.gregochr.goldenhour.entity.ServiceName.OPEN_METEO_FORECAST,
                    "GET", "briefing-forecast-batch(" + coords.size() + ")", null,
                    durationMs, null, null, false, e.getMessage());
            for (LocationEntity loc : locations) {
                results.add(new BriefingSlotBuilder.LocationWeather(loc, null));
            }
        }
        return results;
    }

    /**
     * Captures snapped grid coordinates from the Open-Meteo response and persists them
     * to any location in the group that doesn't yet have grid cell coordinates.
     *
     * @param forecast the Open-Meteo response containing snapped lat/lon
     * @param group    the locations sharing this grid cell
     */
    private void captureGridCoordinates(OpenMeteoForecastResponse forecast,
            List<LocationEntity> group) {
        if (forecast.getLatitude() == null || forecast.getLongitude() == null) {
            return;
        }
        List<LocationEntity> toSave = new ArrayList<>();
        for (LocationEntity loc : group) {
            if (!loc.hasGridCell()) {
                loc.setGridLat(forecast.getLatitude());
                loc.setGridLng(forecast.getLongitude());
                toSave.add(loc);
            }
        }
        if (!toSave.isEmpty()) {
            try {
                locationRepository.saveAll(toSave);
                LOG.debug("Captured grid cell {},{} for {} location(s)",
                        forecast.getLatitude(), forecast.getLongitude(), toSave.size());
            } catch (Exception e) {
                LOG.warn("Failed to persist grid coordinates: {}", e.getMessage());
            }
        }
    }

    /**
     * Fetches cloud-only data at the solar horizon point for each location+event combination.
     *
     * <p>Computes horizon points at 113 km east (sunrise) and west (sunset) for each location,
     * de-duplicates by Open-Meteo grid cell (nearest 0.25°), then makes a single batch fetch
     * for all unique grid cells. With ~50 locations × 2 events → ~100 raw points → typically
     * 30–40 unique grid cells after de-duplication.
     *
     * @param locations the colour locations to compute horizon points for
     * @param jobRun    the job run for API call tracking
     * @return horizon cloud data lookup
     */
    private HorizonCloudData fetchHorizonCloud(List<LocationEntity> locations,
            JobRunEntity jobRun) {

        // Collect phase: compute horizon points and de-duplicate by grid key
        Map<String, double[]> uniqueCoords = new LinkedHashMap<>();
        Map<Long, Map<TargetType, String>> locationKeys = new HashMap<>();

        for (LocationEntity loc : locations) {
            Map<TargetType, String> eventKeys = new HashMap<>();

            double[] sunrisePoint = GeoUtils.offsetPoint(
                    loc.getLat(), loc.getLon(), SUNRISE_BEARING, HORIZON_OFFSET_METRES);
            String sunriseKey = horizonGridKey(sunrisePoint);
            uniqueCoords.putIfAbsent(sunriseKey, sunrisePoint);
            eventKeys.put(TargetType.SUNRISE, sunriseKey);

            double[] sunsetPoint = GeoUtils.offsetPoint(
                    loc.getLat(), loc.getLon(), SUNSET_BEARING, HORIZON_OFFSET_METRES);
            String sunsetKey = horizonGridKey(sunsetPoint);
            uniqueCoords.putIfAbsent(sunsetKey, sunsetPoint);
            eventKeys.put(TargetType.SUNSET, sunsetKey);

            locationKeys.put(loc.getId(), eventKeys);
        }

        // Single batch fetch for all unique horizon grid cells
        List<String> keys = new ArrayList<>(uniqueCoords.keySet());
        List<double[]> coords = keys.stream().map(uniqueCoords::get).toList();

        Map<String, OpenMeteoForecastResponse> responseMap = new HashMap<>();
        long startMs = System.currentTimeMillis();
        try {
            List<OpenMeteoForecastResponse> responses =
                    openMeteoClient.fetchCloudOnlyBatch(coords);
            long durationMs = System.currentTimeMillis() - startMs;
            long populated = responses.stream().filter(r -> r != null).count();
            LOG.info("Horizon cloud fetch: {}/{} grid cells returned ({}ms)",
                    populated, coords.size(), durationMs);
            jobRunService.logApiCall(jobRun.getId(),
                    com.gregochr.goldenhour.entity.ServiceName.OPEN_METEO_FORECAST,
                    "GET", "horizon-cloud-batch(" + coords.size() + ")", null,
                    durationMs, 200, null, true, null);
            for (int i = 0; i < keys.size(); i++) {
                responseMap.put(keys.get(i), responses.get(i));
            }
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            LOG.warn("Horizon cloud batch fetch failed — continuing without horizon data: {}",
                    e.getMessage());
            jobRunService.logApiCall(jobRun.getId(),
                    com.gregochr.goldenhour.entity.ServiceName.OPEN_METEO_FORECAST,
                    "GET", "horizon-cloud-batch(" + coords.size() + ")", null,
                    durationMs, null, null, false, e.getMessage());
        }

        return new HorizonCloudData(locationKeys, responseMap);
    }

    /**
     * Rounds a coordinate to the nearest Open-Meteo grid cell (0.25° resolution).
     *
     * @param point [lat, lon] in decimal degrees
     * @return grid cell key string
     */
    static String horizonGridKey(double[] point) {
        return String.format("%.2f,%.2f",
                Math.round(point[0] * 4) / 4.0,
                Math.round(point[1] * 4) / 4.0);
    }

    /**
     * Lookup container for horizon cloud forecast data, keyed by location ID and event type.
     */
    record HorizonCloudData(Map<Long, Map<TargetType, String>> locationKeys,
            Map<String, OpenMeteoForecastResponse> responseMap) {

        /**
         * Returns the horizon forecast for a given location and event type.
         *
         * @param locationId the location ID
         * @param eventType  SUNRISE or SUNSET
         * @return the horizon forecast response, or null if unavailable
         */
        OpenMeteoForecastResponse getForLocation(Long locationId, TargetType eventType) {
            Map<TargetType, String> keys = locationKeys.get(locationId);
            if (keys == null) {
                return null;
            }
            String key = keys.get(eventType);
            return key != null ? responseMap.get(key) : null;
        }
    }
}
