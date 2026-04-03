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
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingRefreshedEvent;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.repository.DailyBriefingCacheRepository;
import com.gregochr.goldenhour.service.evaluation.BriefingBestBetAdvisor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
    private final ObjectMapper objectMapper;
    private final BriefingHeadlineGenerator headlineGenerator;
    private final BriefingBestBetAdvisor bestBetAdvisor;
    private final BriefingAuroraSummaryBuilder auroraSummaryBuilder;
    private final BriefingHierarchyBuilder hierarchyBuilder;
    private final BriefingSlotBuilder slotBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final AtomicReference<DailyBriefingResponse> cache = new AtomicReference<>();
    private final AtomicReference<DailyBriefingResponse> lastKnownGood = new AtomicReference<>();

    /**
     * Constructs a {@code BriefingService}.
     *
     * @param locationService         service for retrieving enabled locations
     * @param openMeteoClient         resilient Open-Meteo API client
     * @param jobRunService           service for job run tracking
     * @param briefingCacheRepository repository for persisting the briefing across restarts
     * @param objectMapper            Jackson mapper for JSON serialization
     * @param headlineGenerator       generator for the briefing headline
     * @param bestBetAdvisor          Claude Haiku advisor producing ranked best-bet picks
     * @param auroraSummaryBuilder    builder for aurora tonight/tomorrow summaries
     * @param hierarchyBuilder        builder for the day/event/region hierarchy
     * @param slotBuilder             builder for individual briefing slots
     * @param eventPublisher          Spring event publisher for cache invalidation
     */
    public BriefingService(LocationService locationService,
            OpenMeteoClient openMeteoClient,
            JobRunService jobRunService, DailyBriefingCacheRepository briefingCacheRepository,
            ObjectMapper objectMapper,
            BriefingHeadlineGenerator headlineGenerator, BriefingBestBetAdvisor bestBetAdvisor,
            BriefingAuroraSummaryBuilder auroraSummaryBuilder,
            BriefingHierarchyBuilder hierarchyBuilder,
            BriefingSlotBuilder slotBuilder,
            ApplicationEventPublisher eventPublisher) {
        this.locationService = locationService;
        this.openMeteoClient = openMeteoClient;
        this.jobRunService = jobRunService;
        this.briefingCacheRepository = briefingCacheRepository;
        this.objectMapper = objectMapper;
        this.headlineGenerator = headlineGenerator;
        this.bestBetAdvisor = bestBetAdvisor;
        this.auroraSummaryBuilder = auroraSummaryBuilder;
        this.hierarchyBuilder = hierarchyBuilder;
        this.slotBuilder = slotBuilder;
        this.eventPublisher = eventPublisher;
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
            AuroraTonightSummary liveTonight = auroraSummaryBuilder.buildAuroraTonight();
            AuroraTomorrowSummary liveTomorrow = auroraSummaryBuilder.buildAuroraTomorrow();
            if (Objects.equals(cached.auroraTonight(), liveTonight)
                    && Objects.equals(cached.auroraTomorrow(), liveTomorrow)) {
                return cached;
            }
            return new DailyBriefingResponse(
                    cached.generatedAt(), cached.headline(), cached.days(), cached.bestBets(),
                    liveTonight, liveTomorrow, cached.stale(), cached.partialFailure(),
                    cached.failedLocationCount(), cached.bestBetModel());
        } catch (Exception e) {
            LOG.warn("Aurora overlay failed — returning briefing without live aurora: {}",
                    e.getMessage());
            return cached;
        }
    }

    /**
     * Refreshes the daily briefing by fetching live weather data for all enabled colour
     * locations across today and tomorrow, rolling up by region per solar event.
     *
     * <p>Logs a {@link RunType#BRIEFING} job run for metrics tracking.
     */
    public void refreshBriefing() {
        LOG.info("Daily briefing refresh started");
        JobRunEntity jobRun = jobRunService.startRun(RunType.BRIEFING, false, null);

        List<LocationEntity> colourLocations = locationService.findAllEnabled().stream()
                .filter(this::isColourLocation)
                .toList();

        if (colourLocations.isEmpty()) {
            LOG.info("No enabled colour locations — skipping briefing");
            jobRunService.completeRun(jobRun, 0, 0);
            return;
        }

        LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));
        List<LocalDate> dates = List.of(today, today.plusDays(1), today.plusDays(2), today.plusDays(3));

        int succeeded = 0;
        int failed = 0;

        // Fetch weather sequentially — the @RateLimiter on fetchForecast() throttles naturally
        // at 8 calls/second, so sequential fetching scales to any number of locations without
        // exhausting the rate limiter.
        List<BriefingSlotBuilder.LocationWeather> locationWeathers =
                fetchWeatherSequential(colourLocations, jobRun);

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
                    BriefingSlot slot = slotBuilder.buildSlot(lw, date, eventType);
                    if (slot != null) {
                        allSlots.add(slot);
                    }
                }
            }
        }

        // Group into days → event summaries → regions
        List<BriefingDay> days = hierarchyBuilder.buildDays(allSlots, colourLocations, dates);

        String headline = headlineGenerator.generateHeadline(days);
        List<BestBet> bestBets = bestBetAdvisor.advise(days, jobRun.getId(), Map.of());
        AuroraTonightSummary auroraTonight = auroraSummaryBuilder.buildAuroraTonight();
        AuroraTomorrowSummary auroraTomorrow = auroraSummaryBuilder.buildAuroraTomorrow();

        boolean partialFailure = failed > 0;
        int total = succeeded + failed;
        boolean aboveThreshold = total == 0 || (succeeded * 100 / total) >= 50;

        if (aboveThreshold) {
            DailyBriefingResponse response = new DailyBriefingResponse(
                    LocalDateTime.now(ZoneOffset.UTC), headline, days, bestBets,
                    auroraTonight, auroraTomorrow, false, partialFailure, failed,
                    bestBetAdvisor.getModelDisplayName());
            cache.set(response);
            lastKnownGood.set(response);
            persistBriefing(response);
            eventPublisher.publishEvent(new BriefingRefreshedEvent(this));
            jobRunService.completeRun(jobRun, succeeded, failed, dates);
            LOG.info("Briefing complete: {}/{} succeeded, {} failed, stale=false",
                    succeeded, total, failed);
        } else {
            DailyBriefingResponse lkg = lastKnownGood.get();
            if (lkg != null) {
                DailyBriefingResponse staleResponse = new DailyBriefingResponse(
                        lkg.generatedAt(), lkg.headline(), lkg.days(), lkg.bestBets(),
                        auroraTonight, auroraTomorrow, true, true, failed,
                        lkg.bestBetModel());
                cache.set(staleResponse);
                LOG.warn("Briefing complete: {}/{} succeeded, {} failed — below 50% threshold, "
                        + "serving stale=true (LKG from {})", succeeded, total, failed, lkg.generatedAt());
            } else {
                DailyBriefingResponse response = new DailyBriefingResponse(
                        LocalDateTime.now(ZoneOffset.UTC), headline, days, bestBets,
                        auroraTonight, auroraTomorrow, false, partialFailure, failed,
                        bestBetAdvisor.getModelDisplayName());
                cache.set(response);
                LOG.warn("Briefing complete: {}/{} succeeded, {} failed — below threshold, no LKG; using partial",
                        succeeded, total, failed);
            }
            jobRunService.completeRun(jobRun, succeeded, failed, dates);
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
     * Fetches Open-Meteo forecast data for all locations sequentially.
     *
     * <p>Sequential fetching lets the {@code @RateLimiter} on
     * {@link OpenMeteoClient#fetchForecast} throttle calls naturally at 8/s with no
     * queuing pressure. This scales to any number of locations without exhausting the
     * rate limiter (contrast with firing N parallel threads that all compete for permits).
     *
     * @param locations the locations to fetch weather for
     * @param jobRun    the job run for API call tracking
     * @return list of location-weather pairs (forecast may be null on failure)
     */
    private List<BriefingSlotBuilder.LocationWeather> fetchWeatherSequential(
            List<LocationEntity> locations, JobRunEntity jobRun) {
        List<BriefingSlotBuilder.LocationWeather> results = new ArrayList<>();
        for (LocationEntity loc : locations) {
            long startMs = System.currentTimeMillis();
            try {
                OpenMeteoForecastResponse forecast = openMeteoClient.fetchForecastBriefing(
                        loc.getLat(), loc.getLon());
                long durationMs = System.currentTimeMillis() - startMs;
                jobRunService.logApiCall(jobRun.getId(),
                        com.gregochr.goldenhour.entity.ServiceName.OPEN_METEO_FORECAST,
                        "GET", "briefing-forecast/" + loc.getName(), null,
                        durationMs, 200, null, true, null);
                results.add(new BriefingSlotBuilder.LocationWeather(loc, forecast));
            } catch (Exception e) {
                long durationMs = System.currentTimeMillis() - startMs;
                LOG.warn("Briefing weather fetch failed for {}: {}", loc.getName(), e.getMessage());
                jobRunService.logApiCall(jobRun.getId(),
                        com.gregochr.goldenhour.entity.ServiceName.OPEN_METEO_FORECAST,
                        "GET", "briefing-forecast/" + loc.getName(), null,
                        durationMs, null, null, false, e.getMessage());
                results.add(new BriefingSlotBuilder.LocationWeather(loc, null));
            }
        }
        return results;
    }
}
