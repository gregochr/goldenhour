package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.CachedEvaluationEntity;
import com.gregochr.goldenhour.entity.EvaluationDeltaLogEntity;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingRefreshedEvent;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.repository.CachedEvaluationRepository;
import com.gregochr.goldenhour.repository.EvaluationDeltaLogRepository;
import com.gregochr.goldenhour.service.evaluation.CacheKeyFactory;
import com.gregochr.goldenhour.service.evaluation.RatingValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the durable per-region evaluation cache keyed by region+date+targetType.
 *
 * <p>The cache is an in-memory {@link ConcurrentHashMap} backed by the {@code cached_evaluation}
 * table for cross-restart durability. Writes arrive exclusively from the batch pipeline via
 * {@link #writeFromBatch}; the legacy SSE write path was retired in Pass 3.3.3.
 *
 * <p>Reads serve the Plan and Map tabs via {@link EvaluationViewService}, the
 * {@code BriefingBestBetAdvisor}, and the {@code BriefingGlossService}. Freshness gates
 * for the batch collector are exposed through {@link #hasEvaluation} and
 * {@link #hasFreshEvaluation}.
 *
 * <p>The cache is intentionally retained across briefing refreshes — Claude scores are
 * expensive and remain directionally useful after a weather refresh; new batch results
 * replace prior entries on the same key.
 */
@Service
public class BriefingEvaluationService {

    private static final Logger LOG = LoggerFactory.getLogger(BriefingEvaluationService.class);

    private static final ZoneId UK_ZONE = ZoneId.of("Europe/London");

    /**
     * A drop in row count greater than this between consecutive heartbeats triggers a WARN.
     * No code path deletes individual rows, so any real drop is suspicious; the small
     * tolerance only absorbs benign timing noise.
     */
    private static final long ROW_COUNT_DROP_TOLERANCE = 1;

    private final CachedEvaluationRepository cachedEvaluationRepository;
    private final EvaluationDeltaLogRepository deltaLogRepository;
    private final ObjectMapper objectMapper;
    private final FreshnessResolver freshnessResolver;
    private final StabilitySnapshotProvider stabilitySnapshotProvider;

    /** Outer key: "regionName|date|targetType", value: cached entry with results + timestamp. */
    private final ConcurrentHashMap<String, CachedEvaluation> cache = new ConcurrentHashMap<>();

    /**
     * Last-seen cache-health snapshot, the baseline the heartbeat compares against. A simple
     * in-memory tripwire (not an audit store): replaced on every heartbeat and reset on an
     * intentional admin clear so the clear is not mistaken for a backwards jump.
     */
    private final AtomicReference<CacheHealth> lastHealth = new AtomicReference<>();

    /**
     * Cached evaluation results for a region/date/targetType.
     */
    record CachedEvaluation(
            ConcurrentHashMap<String, BriefingEvaluationResult> results,
            Instant evaluatedAt
    ) {
    }

    /**
     * Lightweight snapshot of {@code cached_evaluation} health for backwards-jump detection.
     *
     * @param rowCount       total rows in the table
     * @param maxEvaluatedAt newest {@code evaluated_at}, or {@code null} when empty
     */
    private record CacheHealth(long rowCount, Instant maxEvaluatedAt) {
    }

    /**
     * Constructs a {@code BriefingEvaluationService}.
     *
     * @param cachedEvaluationRepository repository for durable cache persistence
     * @param deltaLogRepository         repository for evaluation delta log entries
     * @param objectMapper               Jackson mapper for JSON serialisation
     * @param freshnessResolver          resolves per-stability cache freshness thresholds
     * @param stabilitySnapshotProvider  provides the latest stability snapshot for delta logging
     */
    public BriefingEvaluationService(CachedEvaluationRepository cachedEvaluationRepository,
            EvaluationDeltaLogRepository deltaLogRepository,
            ObjectMapper objectMapper,
            FreshnessResolver freshnessResolver,
            StabilitySnapshotProvider stabilitySnapshotProvider) {
        this.cachedEvaluationRepository = cachedEvaluationRepository;
        this.deltaLogRepository = deltaLogRepository;
        this.objectMapper = objectMapper;
        this.freshnessResolver = freshnessResolver;
        this.stabilitySnapshotProvider = stabilitySnapshotProvider;
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
     * Returns whether a non-empty cached evaluation exists for the given cache key.
     *
     * <p>Available for future schedulers (e.g. the planned intraday refresh) to gate work
     * on whether a region has any cached result at all.
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
     * <p>Called by {@code ForecastTaskCollector} to decide whether an overnight batch
     * should refresh a slot. Entries older than {@code maxAge} are treated as stale so
     * the batch re-evaluates them with fresh weather data.
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
     * Writes batch-evaluated results into the cache for a given region/date/targetType.
     *
     * <p>Called by the batch result handler after successfully fetching completed batch
     * results from Anthropic. Replaces any prior entry for the same cache key; the prior
     * entry, if any, drives delta-log emission for empirical freshness tuning.
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
        StabilitySummaryResponse snapshot = stabilitySnapshotProvider.getLatestStabilitySummary();
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
     * Clears all cached evaluation results. Available as an admin escape hatch.
     *
     * @return the number of in-memory entries cleared
     */
    @Transactional
    public int clearCache() {
        int size = cache.size();
        long dbDeleted = cachedEvaluationRepository.count();
        // Loud, attributable record of the only code path that can empty the table — so a
        // genuine admin wipe is unmistakable in the logs and a silent disappearance (no such
        // line) positively points at a data-layer cause instead.
        LOG.warn("cached_evaluation DELETE: deleteAll requested by {}, removing {} rows "
                + "({} in-memory)", currentPrincipal(), dbDeleted, size);
        cache.clear();
        cachedEvaluationRepository.deleteAll();
        // Reset the heartbeat baseline so the next health check treats this intentional clear
        // as the new normal rather than flagging it as an unexpected backwards jump.
        lastHealth.set(new CacheHealth(0, null));
        if (size > 0 || dbDeleted > 0) {
            LOG.info("Briefing evaluation cache cleared ({} in-memory, {} DB entries)",
                    size, dbDeleted);
        }
        return size;
    }

    /**
     * Resolves the current authenticated principal name for delete-audit logging, or
     * {@code "unknown"} when there is no security context (e.g. a scheduled or test caller).
     */
    private static String currentPrincipal() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return auth != null ? auth.getName() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
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
     * retained — batch scores are expensive and remain directionally useful after a
     * weather refresh. Entries are replaced when new results are written.
     *
     * @param event the briefing refreshed event
     */
    @EventListener
    public void onBriefingRefreshed(BriefingRefreshedEvent event) {
        LOG.info("Briefing refreshed — evaluation cache retained ({} entries)", cache.size());
        recordCacheHealthHeartbeat();
    }

    /**
     * Cache-health tripwire for the 2026-06-06 failure mode (rows written then disappearing).
     *
     * <p>Reads the current {@code cached_evaluation} row count, newest {@code evaluated_at},
     * and distinct-key count, logs them at INFO, and compares against the last heartbeat. If
     * the newest {@code evaluated_at} has moved <em>backwards</em>, or the row count has
     * dropped by more than {@link #ROW_COUNT_DROP_TOLERANCE} (with no intervening admin clear,
     * which resets the baseline), it logs a WARN — turning a silent, weeks-cold mystery into a
     * same-cycle alert.
     *
     * <p>Side-effect-free beyond updating the in-memory baseline: it never deletes or mutates
     * cache rows, and never throws into the briefing refresh path (failures are swallowed and
     * logged).
     */
    void recordCacheHealthHeartbeat() {
        try {
            long rows = cachedEvaluationRepository.count();
            Instant maxEvaluatedAt = cachedEvaluationRepository.findMaxEvaluatedAt();
            long distinctKeys = cachedEvaluationRepository.countDistinctCacheKeys();
            LOG.info("cached_evaluation health: rows={}, maxEvaluatedAt={}, distinctKeys={}",
                    rows, maxEvaluatedAt, distinctKeys);

            CacheHealth previous = lastHealth.getAndSet(new CacheHealth(rows, maxEvaluatedAt));
            if (previous == null) {
                return;
            }
            boolean wentBackwards = maxEvaluatedAt != null && previous.maxEvaluatedAt() != null
                    && maxEvaluatedAt.isBefore(previous.maxEvaluatedAt());
            boolean rowsDropped = rows < previous.rowCount() - ROW_COUNT_DROP_TOLERANCE;
            if (wentBackwards || rowsDropped) {
                LOG.warn("cached_evaluation went BACKWARDS: was rows={}, maxEvaluatedAt={}; "
                        + "now rows={}, maxEvaluatedAt={} — possible unexpected cache clear",
                        previous.rowCount(), previous.maxEvaluatedAt(), rows, maxEvaluatedAt);
            }
        } catch (Exception e) {
            LOG.warn("cached_evaluation health heartbeat failed: {}", e.getMessage());
        }
    }

    /**
     * Persists the in-memory cache entry to the database.
     *
     * <p>Uses upsert semantics — an existing row for the same cache key is updated.
     * Persistence failures are logged but never break the live path.
     *
     * @param cacheKey  the cache key in "regionName|date|targetType" format
     * @param results   the evaluation results to persist
     * @param source    how this entry was produced (currently always "BATCH" — the SSE
     *                  writer was removed in Pass 3.3.3; the column is retained for
     *                  backward-compatible audit and historical row inspection)
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
}
