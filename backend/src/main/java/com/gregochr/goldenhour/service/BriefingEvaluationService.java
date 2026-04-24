package com.gregochr.goldenhour.service;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.CachedEvaluationEntity;
import com.gregochr.goldenhour.entity.EvaluationDeltaLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingRefreshedEvent;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.TriageReason;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.repository.CachedEvaluationRepository;
import com.gregochr.goldenhour.repository.EvaluationDeltaLogRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.evaluation.CacheKeyFactory;
import com.gregochr.goldenhour.service.evaluation.RatingValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Orchestrates per-region Claude evaluations from the briefing drill-down and manages
 * an in-memory score cache keyed by region+date+targetType.
 *
 * <p>Results are streamed back via SSE as each location completes. The cache is
 * invalidated when the briefing refreshes (via {@link BriefingRefreshedEvent}).
 */
@Service
public class BriefingEvaluationService {

    private static final Logger LOG = LoggerFactory.getLogger(BriefingEvaluationService.class);

    private static final ZoneId UK_ZONE = ZoneId.of("Europe/London");
    private static final DateTimeFormatter UK_TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(UK_ZONE);

    private final LocationService locationService;
    private final BriefingService briefingService;
    private final ForecastService forecastService;
    private final ModelSelectionService modelSelectionService;
    private final JobRunService jobRunService;
    private final ForecastBatchRepository batchRepository;
    private final CachedEvaluationRepository cachedEvaluationRepository;
    private final EvaluationDeltaLogRepository deltaLogRepository;
    private final AnthropicClient anthropicClient;
    private final ObjectMapper objectMapper;
    private final FreshnessResolver freshnessResolver;
    private final ForecastCommandExecutor forecastCommandExecutor;

    /** Outer key: "regionName|date|targetType", value: cached entry with results + timestamp. */
    private final ConcurrentHashMap<String, CachedEvaluation> cache = new ConcurrentHashMap<>();

    /**
     * Cached evaluation results for a region/date/targetType.
     */
    record CachedEvaluation(
            ConcurrentHashMap<String, BriefingEvaluationResult> results,
            Instant evaluatedAt
    ) {
    }

    /**
     * Constructs a {@code BriefingEvaluationService}.
     *
     * @param locationService            service for retrieving enabled locations
     * @param briefingService            service for the cached briefing
     * @param forecastService            service for weather fetch, triage, and Claude evaluation
     * @param modelSelectionService      service for resolving the active Claude model
     * @param jobRunService              service for job run tracking
     * @param batchRepository            repository for looking up and cancelling outstanding batches
     * @param cachedEvaluationRepository repository for durable cache persistence
     * @param deltaLogRepository         repository for evaluation delta log entries
     * @param anthropicClient            raw SDK client for cancelling batch API jobs
     * @param objectMapper               Jackson mapper for JSON serialisation
     * @param freshnessResolver          resolves per-stability cache freshness thresholds
     * @param forecastCommandExecutor    provides the latest stability snapshot for delta logging
     */
    public BriefingEvaluationService(LocationService locationService,
            BriefingService briefingService,
            ForecastService forecastService,
            ModelSelectionService modelSelectionService,
            JobRunService jobRunService,
            ForecastBatchRepository batchRepository,
            CachedEvaluationRepository cachedEvaluationRepository,
            EvaluationDeltaLogRepository deltaLogRepository,
            AnthropicClient anthropicClient,
            ObjectMapper objectMapper,
            FreshnessResolver freshnessResolver,
            ForecastCommandExecutor forecastCommandExecutor) {
        this.locationService = locationService;
        this.briefingService = briefingService;
        this.forecastService = forecastService;
        this.modelSelectionService = modelSelectionService;
        this.jobRunService = jobRunService;
        this.batchRepository = batchRepository;
        this.cachedEvaluationRepository = cachedEvaluationRepository;
        this.deltaLogRepository = deltaLogRepository;
        this.anthropicClient = anthropicClient;
        this.objectMapper = objectMapper;
        this.freshnessResolver = freshnessResolver;
        this.forecastCommandExecutor = forecastCommandExecutor;
    }

    /**
     * Evaluates GO/MARGINAL locations for a region/date/targetType, streaming results via SSE.
     *
     * <p>If cached results exist, they are emitted as rapid-fire events (no Claude calls).
     * Otherwise, locations are evaluated sequentially with the configured SHORT_TERM model.
     *
     * @param regionName the region to evaluate
     * @param date       the forecast date
     * @param targetType SUNRISE or SUNSET
     * @param emitter    the SSE emitter to send events to
     */
    public void evaluateRegion(String regionName, LocalDate date, TargetType targetType,
            SseEmitter emitter) {
        String cacheKey = CacheKeyFactory.build(regionName, date, targetType);

        // Cancel any outstanding FORECAST batches — real-time evaluation takes priority
        cancelOutstandingForecastBatches();

        // Track client disconnect so we stop submitting Claude calls
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(e -> cancelled.set(true));

        // Determine the evaluable set from the briefing
        Set<String> evaluableNames = getEvaluableLocationNames(regionName, date, targetType);

        // Full cache hit — all evaluable locations are already scored
        CachedEvaluation cached = cache.get(cacheKey);
        if (cached != null && !cached.results().isEmpty()
                && cached.results().keySet().containsAll(evaluableNames)
                && !evaluableNames.isEmpty()) {
            LOG.info("Briefing evaluation cache hit for {}", cacheKey);
            emitCachedResults(cached, emitter, regionName, date, targetType);
            return;
        }

        // Find locations in this region
        List<LocationEntity> regionLocations = locationService.findAllEnabled().stream()
                .filter(loc -> loc.getRegion() != null && loc.getRegion().getName().equals(regionName))
                .filter(briefingService::isColourLocation)
                .toList();

        // Filter to GO/MARGINAL slots from the cached briefing; skip any already in cache
        List<LocationEntity> toEvaluate = regionLocations.stream()
                .filter(loc -> evaluableNames.contains(loc.getName()))
                .filter(loc -> cached == null || !cached.results().containsKey(loc.getName()))
                .toList();

        if (toEvaluate.isEmpty()) {
            LOG.info("No evaluable locations for {}", cacheKey);
            sendSafe(emitter, "evaluation-complete",
                    Map.of("completed", 0, "total", 0, "failed", 0,
                            "regionName", regionName, "date", date.toString(),
                            "targetType", targetType.name()));
            completeEmitter(emitter);
            return;
        }

        // Create a job run for cost tracking
        EvaluationModel model = modelSelectionService.getActiveModel(RunType.SHORT_TERM);
        JobRunEntity jobRun = jobRunService.startRun(RunType.SHORT_TERM, true, model);

        // Seed with any partial batch results so the final cache is complete
        ConcurrentHashMap<String, BriefingEvaluationResult> results = new ConcurrentHashMap<>();
        if (cached != null) {
            results.putAll(cached.results());
        }

        int total = toEvaluate.size();
        int completed = 0;
        int failed = 0;

        for (LocationEntity location : toEvaluate) {
            if (cancelled.get()) {
                LOG.info("Client disconnected — stopping evaluation for {} ({}/{} done)",
                        cacheKey, completed + failed, total);
                break;
            }

            try {
                BriefingEvaluationResult result = evaluateSingleLocation(
                        location, date, targetType, model, jobRun);
                results.put(location.getName(), result);
                completed++;
                // Write to cache after each location so results survive client disconnect
                cache.put(cacheKey, new CachedEvaluation(results, Instant.now()));
                sendSafe(emitter, "location-scored", result);
            } catch (Exception e) {
                failed++;
                LOG.warn("Briefing evaluation failed for {}: {}", location.getName(), e.getMessage());
                sendSafe(emitter, "evaluation-error",
                        Map.of("locationName", location.getName(), "error", e.getMessage()));
            }

            sendSafe(emitter, "progress",
                    Map.of("completed", completed + failed, "total", total, "failed", failed));
        }

        jobRunService.completeRun(jobRun, completed, failed, List.of(date));

        Instant evaluatedAt = Instant.now();
        if (!results.isEmpty()) {
            cache.put(cacheKey, new CachedEvaluation(results, evaluatedAt));
            persistToDb(cacheKey, results, "SSE");
        }

        sendSafe(emitter, "evaluation-complete",
                Map.of("completed", completed, "total", total, "failed", failed,
                        "regionName", regionName, "date", date.toString(),
                        "targetType", targetType.name(),
                        "evaluatedAt", UK_TIME.format(evaluatedAt)));
        completeEmitter(emitter);

        LOG.info("Briefing evaluation complete for {}: {}/{} succeeded, {} failed",
                cacheKey, completed, total, failed);
    }

    /**
     * Returns cached evaluation scores for the given region/date/targetType, or an empty map.
     *
     * @param regionName the region name
     * @param date       the forecast date
     * @param targetType SUNRISE or SUNSET
     * @return map of locationName to evaluation result
     */
    public Map<String, BriefingEvaluationResult> getCachedScores(String regionName,
            LocalDate date, TargetType targetType) {
        String cacheKey = CacheKeyFactory.build(regionName, date, targetType);
        CachedEvaluation cached = cache.get(cacheKey);
        return cached != null ? Collections.unmodifiableMap(cached.results()) : Map.of();
    }

    /**
     * Returns the UK-formatted evaluation time for cached results, or null if not cached.
     *
     * @param regionName the region name
     * @param date       the forecast date
     * @param targetType SUNRISE or SUNSET
     * @return formatted time string (e.g. "14:32") or null
     */
    public String getCachedEvaluatedAt(String regionName, LocalDate date, TargetType targetType) {
        String cacheKey = CacheKeyFactory.build(regionName, date, targetType);
        CachedEvaluation cached = cache.get(cacheKey);
        return cached != null ? UK_TIME.format(cached.evaluatedAt()) : null;
    }

    /**
     * Returns whether a non-empty cached evaluation exists for the given cache key.
     *
     * <p>Called by {@code ScheduledBatchEvaluationService} before building a batch request
     * to avoid submitting work that the SSE path has already completed.
     *
     * @param cacheKey the cache key in the format "regionName|date|targetType"
     * @return true if the cache has at least one result for this key
     */
    public boolean hasEvaluation(String cacheKey) {
        CachedEvaluation cached = cache.get(cacheKey);
        return cached != null && !cached.results().isEmpty();
    }

    /**
     * Returns whether a non-empty cached evaluation exists for the given cache key
     * and was written within the specified freshness window.
     *
     * <p>Called by {@code ScheduledBatchEvaluationService} to decide whether an overnight
     * batch should refresh a slot. Entries older than {@code maxAge} are treated as stale
     * so the batch re-evaluates them with fresh weather data.
     *
     * @param cacheKey the cache key in the format "regionName|date|targetType"
     * @param maxAge   maximum age for a cache entry to be considered fresh
     * @return true if the cache has at least one result for this key and it is within maxAge
     */
    public boolean hasFreshEvaluation(String cacheKey, Duration maxAge) {
        CachedEvaluation cached = cache.get(cacheKey);
        if (cached == null || cached.results().isEmpty()) {
            return false;
        }
        return cached.evaluatedAt().isAfter(Instant.now().minus(maxAge));
    }

    /**
     * Writes batch-evaluated results directly into the cache for a given region/date/targetType.
     *
     * <p>Called by {@code BatchResultProcessor} after successfully fetching completed batch results
     * from Anthropic. The cache key format matches the SSE path exactly so subsequent SSE requests
     * return from cache rather than re-invoking Claude.
     *
     * @param cacheKey the cache key in the format "regionName|date|targetType"
     * @param results  the evaluation results to store
     */
    public void writeFromBatch(String cacheKey,
            List<BriefingEvaluationResult> results) {
        CachedEvaluation prior = cache.get(cacheKey);
        ConcurrentHashMap<String, BriefingEvaluationResult> resultMap = new ConcurrentHashMap<>();
        results.forEach(r -> resultMap.put(r.locationName(), r));
        Instant now = Instant.now();
        cache.put(cacheKey, new CachedEvaluation(resultMap, now));
        persistToDb(cacheKey, resultMap, "BATCH");
        logEvaluationDeltas(cacheKey, prior, resultMap, now);
    }

    /**
     * Logs rating deltas to {@code evaluation_delta_log} for empirical freshness
     * threshold refinement. Only inserts rows when a prior cache entry existed.
     * Failures are logged at WARN and never break the cache write path.
     */
    private void logEvaluationDeltas(String cacheKey, CachedEvaluation prior,
            Map<String, BriefingEvaluationResult> newResults, Instant newEvaluatedAt) {
        if (prior == null || prior.results().isEmpty()) {
            return;
        }
        try {
            CacheKeyFactory.CacheKey parsed;
            try {
                parsed = CacheKeyFactory.parse(cacheKey);
            } catch (IllegalArgumentException e) {
                return;
            }
            LocalDate evalDate = parsed.date();
            String targetType = parsed.targetType().name();

            Map<String, ForecastStability> stabilityLookup = buildStabilityLookup();

            for (Map.Entry<String, BriefingEvaluationResult> entry : newResults.entrySet()) {
                String locationName = entry.getKey();
                BriefingEvaluationResult oldResult = prior.results().get(locationName);
                if (oldResult == null) {
                    continue;
                }
                BriefingEvaluationResult newResult = entry.getValue();
                ForecastStability stability = stabilityLookup.getOrDefault(
                        locationName, ForecastStability.UNSETTLED);
                Duration threshold = freshnessResolver.maxAgeFor(stability);
                Duration age = Duration.between(prior.evaluatedAt(), newEvaluatedAt);

                EvaluationDeltaLogEntity delta = new EvaluationDeltaLogEntity();
                delta.setCacheKey(cacheKey);
                delta.setLocationName(locationName);
                delta.setEvaluationDate(evalDate);
                delta.setTargetType(targetType);
                delta.setStabilityLevel(stability.name());
                delta.setOldEvaluatedAt(prior.evaluatedAt());
                delta.setNewEvaluatedAt(newEvaluatedAt);
                delta.setAgeHours(java.math.BigDecimal.valueOf(
                        age.toMinutes() / 60.0).setScale(2, java.math.RoundingMode.HALF_UP));
                delta.setOldRating(oldResult.rating());
                delta.setNewRating(newResult.rating());
                if (oldResult.rating() != null && newResult.rating() != null) {
                    delta.setRatingDelta(java.math.BigDecimal.valueOf(
                            Math.abs(newResult.rating() - oldResult.rating())));
                }
                delta.setThresholdUsedHours(java.math.BigDecimal.valueOf(threshold.toHours()));
                delta.setLoggedAt(newEvaluatedAt);
                deltaLogRepository.save(delta);
            }
        } catch (Exception e) {
            LOG.warn("Failed to log evaluation deltas for {}: {}", cacheKey, e.getMessage());
        }
    }

    /**
     * Builds a location-name → stability lookup from the latest snapshot.
     */
    private Map<String, ForecastStability> buildStabilityLookup() {
        StabilitySummaryResponse snapshot = forecastCommandExecutor.getLatestStabilitySummary();
        if (snapshot == null || snapshot.cells() == null) {
            return Map.of();
        }
        Map<String, ForecastStability> lookup = new java.util.HashMap<>();
        for (StabilitySummaryResponse.GridCellDetail cell : snapshot.cells()) {
            for (String locName : cell.locationNames()) {
                lookup.put(locName, cell.stability());
            }
        }
        return lookup;
    }

    /**
     * Cancels all outstanding FORECAST batches via the Anthropic Batch API.
     *
     * <p>Called at the start of {@link #evaluateRegion} so that a user-initiated real-time
     * SSE evaluation supersedes any in-flight overnight batch. Batch API cancel is best-effort:
     * if the batch has already transitioned to {@code ENDED} or the API call fails, the error
     * is logged and processing continues — it never blocks the real-time path.
     */
    private void cancelOutstandingForecastBatches() {
        List<ForecastBatchEntity> submitted =
                batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED);

        for (ForecastBatchEntity batch : submitted) {
            if (batch.getBatchType() != BatchType.FORECAST) {
                continue;
            }
            String batchId = batch.getAnthropicBatchId();
            try {
                anthropicClient.messages().batches().cancel(batchId);
                batch.setStatus(BatchStatus.CANCELLED);
                batch.setEndedAt(Instant.now());
                batch.setErrorMessage("Cancelled — superseded by real-time SSE evaluation");
                batchRepository.save(batch);
                LOG.info("Cancelled outstanding FORECAST batch {} ahead of real-time evaluation",
                        batchId);
            } catch (Exception e) {
                LOG.warn("Failed to cancel batch {} (may already be complete): {}",
                        batchId, e.getMessage());
            }
        }
    }

    /**
     * Clears all cached evaluation results. Available as an admin escape hatch.
     *
     * @return the number of entries cleared
     */
    @Transactional
    public int clearCache() {
        int size = cache.size();
        cache.clear();
        long dbDeleted = cachedEvaluationRepository.count();
        cachedEvaluationRepository.deleteAll();
        if (size > 0 || dbDeleted > 0) {
            LOG.info("Briefing evaluation cache cleared ({} in-memory, {} DB entries)",
                    size, dbDeleted);
        }
        return size;
    }

    /**
     * Rehydrates the in-memory evaluation cache from the database on startup.
     *
     * <p>Loads entries for today and future dates so that expensive evaluation results
     * survive backend restarts. Past dates are not loaded — they are stale.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void rehydrateCacheOnStartup() {
        LocalDate today = LocalDate.now(UK_ZONE);
        List<CachedEvaluationEntity> entries =
                cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(today);

        int loaded = 0;
        int clamped = 0;
        for (CachedEvaluationEntity entity : entries) {
            try {
                List<BriefingEvaluationResult> results = objectMapper.readValue(
                        entity.getResultsJson(),
                        new TypeReference<List<BriefingEvaluationResult>>() { });

                String regionName;
                try {
                    regionName = CacheKeyFactory.parse(entity.getCacheKey()).regionName();
                } catch (IllegalArgumentException e) {
                    regionName = null;
                }
                LocalDate evalDate = entity.getEvaluationDate();
                TargetType eventType = entity.getTargetType() != null
                        ? TargetType.valueOf(entity.getTargetType()) : null;

                ConcurrentHashMap<String, BriefingEvaluationResult> resultMap =
                        new ConcurrentHashMap<>();
                for (BriefingEvaluationResult r : results) {
                    Integer safe = RatingValidator.validateRating(r.rating(),
                            regionName, evalDate, eventType, r.locationName(), null);
                    if (safe == null && r.rating() != null) {
                        clamped++;
                        resultMap.put(r.locationName(), r.withRating(null));
                    } else {
                        resultMap.put(r.locationName(), r);
                    }
                }
                cache.put(entity.getCacheKey(),
                        new CachedEvaluation(resultMap, entity.getEvaluatedAt()));
                loaded++;
            } catch (Exception e) {
                LOG.warn("Failed to rehydrate cache entry {}: {}",
                        entity.getCacheKey(), e.getMessage());
            }
        }

        if (loaded > 0) {
            LOG.info("[EVAL HYDRATE] Loaded {} entries from cached_evaluation "
                    + "({} ratings clamped, dates >= {})", loaded, clamped, today);
        }
    }

    /**
     * Listens for briefing refresh events. The evaluation cache is intentionally
     * retained — batch and SSE scores are expensive and remain directionally useful
     * after a weather refresh. Entries are replaced when new results are written.
     *
     * @param event the briefing refreshed event
     */
    @EventListener
    public void onBriefingRefreshed(BriefingRefreshedEvent event) {
        LOG.info("Briefing refreshed — evaluation cache retained ({} entries)", cache.size());
    }

    /**
     * Persists the in-memory cache entry to the database.
     *
     * <p>Uses upsert semantics — an existing row for the same cache key is updated.
     * Persistence failures are logged but never break the live path.
     *
     * @param cacheKey  the cache key in "regionName|date|targetType" format
     * @param results   the evaluation results to persist
     * @param source    how this entry was produced: "BATCH" or "SSE"
     */
    private void persistToDb(String cacheKey,
            Map<String, BriefingEvaluationResult> results, String source) {
        try {
            CacheKeyFactory.CacheKey parsed = CacheKeyFactory.parse(cacheKey);
            String regionName = parsed.regionName();
            LocalDate date = parsed.date();
            String targetType = parsed.targetType().name();

            List<BriefingEvaluationResult> resultList = new ArrayList<>(results.values());
            String json = objectMapper.writeValueAsString(resultList);

            CachedEvaluationEntity entity = cachedEvaluationRepository
                    .findByCacheKey(cacheKey)
                    .orElseGet(() -> {
                        CachedEvaluationEntity e = new CachedEvaluationEntity();
                        e.setCacheKey(cacheKey);
                        e.setEvaluatedAt(Instant.now());
                        return e;
                    });

            entity.setRegionName(regionName);
            entity.setEvaluationDate(date);
            entity.setTargetType(targetType);
            entity.setResultsJson(json);
            entity.setSource(source);
            entity.setUpdatedAt(Instant.now());

            cachedEvaluationRepository.save(entity);
            LOG.info("{} results persisted to DB for key: {} ({} results)",
                    source, cacheKey, results.size());
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialise evaluation cache for {}: {}",
                    cacheKey, e.getMessage());
        } catch (Exception e) {
            LOG.warn("Failed to persist evaluation cache for {}: {}",
                    cacheKey, e.getMessage());
        }
    }

    private BriefingEvaluationResult evaluateSingleLocation(LocationEntity location,
            LocalDate date, TargetType targetType, EvaluationModel model, JobRunEntity jobRun) {
        ForecastPreEvalResult preEval = forecastService.fetchWeatherAndTriage(
                location, date, targetType, location.getTideType(), model, false, jobRun);

        if (preEval.triaged()) {
            TriageReason category = preEval.triageCategory() != null
                    ? preEval.triageCategory() : TriageReason.GENERIC;
            return new BriefingEvaluationResult(
                    location.getName(), null, null, null, null,
                    category, preEval.triageReason());
        }

        ForecastEvaluationEntity entity = forecastService.evaluateAndPersist(preEval, jobRun);
        String regionName = location.getRegion() != null
                ? location.getRegion().getName() : location.getName();
        Integer safeRating = RatingValidator.validateRating(
                entity.getRating(), regionName, date, targetType,
                location.getName(), model.name());
        return new BriefingEvaluationResult(
                location.getName(),
                safeRating,
                entity.getFierySkyPotential(),
                entity.getGoldenHourPotential(),
                entity.getSummary());
    }

    /**
     * Returns the set of location names with GO or MARGINAL verdict for the given cell.
     */
    private Set<String> getEvaluableLocationNames(String regionName, LocalDate date,
            TargetType targetType) {
        DailyBriefingResponse briefing = briefingService.getCachedBriefing();
        if (briefing == null) {
            return Set.of();
        }

        return briefing.days().stream()
                .filter(day -> day.date().equals(date))
                .flatMap(day -> day.eventSummaries().stream())
                .filter(es -> es.targetType() == targetType)
                .flatMap(es -> es.regions().stream())
                .filter(region -> region.regionName().equals(regionName))
                .flatMap(region -> region.slots().stream())
                .filter(slot -> slot.verdict() == Verdict.GO || slot.verdict() == Verdict.MARGINAL)
                .map(BriefingSlot::locationName)
                .collect(Collectors.toSet());
    }

    private void emitCachedResults(CachedEvaluation cached,
            SseEmitter emitter, String regionName, LocalDate date, TargetType targetType) {
        int total = cached.results().size();
        int i = 0;
        for (BriefingEvaluationResult result : cached.results().values()) {
            sendSafe(emitter, "location-scored", result);
            i++;
            sendSafe(emitter, "progress", Map.of("completed", i, "total", total, "failed", 0));
        }
        sendSafe(emitter, "evaluation-complete",
                Map.of("completed", total, "total", total, "failed", 0,
                        "regionName", regionName, "date", date.toString(),
                        "targetType", targetType.name(),
                        "evaluatedAt", UK_TIME.format(cached.evaluatedAt())));
        completeEmitter(emitter);
    }

    private void completeEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            LOG.debug("Emitter already completed: {}", e.getMessage());
        }
    }

    private void sendSafe(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            LOG.debug("SSE send failed for event '{}': {}", eventName, e.getMessage());
        }
    }
}
