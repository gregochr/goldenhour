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
import com.gregochr.goldenhour.model.BriefingEventSummary;
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
import java.util.stream.IntStream;


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
    private final EvaluationViewService evaluationViewService;
    private final SeasonalWindow bluebellSeason;
    private final NlcClarityService nlcClarityService;
    private final MeteorClarityService meteorClarityService;
    private final SurgeCurveService surgeCurveService;
    private final java.time.Clock clock;
    private final MarineWaveRefreshService marineWaveRefreshService;
    private final BriefingRegionSnapshotService regionSnapshotService;
    private final ServedBriefingAssembler assembler;
    private final BriefingRegionEvaluationRollup rollup;

    /** UK civil-date zone for "today" derivation. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /**
     * Number of consecutive dates the briefing covers, starting on the day it is built.
     *
     * <p><b>Five, not four, because the briefing is read a day after it is written.</b> Since
     * V103 retired the standalone {@code daily_briefing} cron it is refreshed only at the
     * <em>tail</em> of a pipeline cycle, so the 01:00 nightly cycle consumes the briefing the
     * previous afternoon's intraday cycle left behind. That briefing's first date is by then
     * yesterday, and {@code BriefingCandidateCollector} drops it as {@code PAST_DATE} — so a
     * four-date window arrives at the nightly cycle already one date short at the far end.
     *
     * <p>That capped the nightly cycle's effective horizon at T+2 and made the T+3 SETTLED tier
     * in {@code NightlyEligibilityPolicy} <b>unreachable in production</b>. Measured over 14 days
     * and 28 cycles: zero candidates at {@code days_ahead = 3}, and 5,684 at
     * {@code days_ahead = -1} — one full quarter of every nightly candidate set spent on a date
     * that had already happened. The intraday cycle showed no {@code -1} bucket at all, because
     * it reads a briefing built the same morning; that asymmetry is the mechanism, not a symptom.
     *
     * <p>The fifth date is never rendered — the Plan tab caps the matrix at
     * {@link PlanRenderLimits#MAX_VISIBLE_EVENTS} solar events — and costs one extra
     * region × event round of briefing gloss. It exists purely so the window still
     * reaches T+3 after ageing overnight.
     */
    private static final int BRIEFING_WINDOW_DAYS = 5;
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
     * @param evaluationViewService      merged evaluation view service (lazy to break cycle)
     * @param bluebellSeason             the configured bluebell season window
     * @param nlcClarityService          caches which nights have a clear dark-sky NLC chance
     * @param meteorClarityService       caches overhead dark-sky clarity for shower-peak nights
     * @param surgeCurveService          caches the hourly storm-surge curve computed from the
     *                                   weather this build has already fetched
     * @param clock                      UTC clock supplying "now" and (via London) "today"
     * @param marineWaveRefreshService   fetches + persists coastal sea-state each briefing cycle
     * @param regionSnapshotService      records what each build displayed, and reads the previous
     *                                   build back so the Plan strip can show which way it moved
     * @param assembler                  owns the serve-time composition of a cached snapshot into
     *                                   the served payload; see {@link ServedBriefingAssembler}
     * @param rollup                     turns the weather/triage hierarchy into a scored one;
     *                                   shared with the serve path through the
     *                                   {@link BriefingScoreEnricher} socket
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
            @Lazy EvaluationViewService evaluationViewService,
            SeasonalWindow bluebellSeason,
            NlcClarityService nlcClarityService,
            MeteorClarityService meteorClarityService,
            SurgeCurveService surgeCurveService,
            java.time.Clock clock,
            MarineWaveRefreshService marineWaveRefreshService,
            BriefingRegionSnapshotService regionSnapshotService,
            ServedBriefingAssembler assembler,
            BriefingRegionEvaluationRollup rollup) {
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
        this.evaluationViewService = evaluationViewService;
        this.bluebellSeason = bluebellSeason;
        this.nlcClarityService = nlcClarityService;
        this.meteorClarityService = meteorClarityService;
        this.surgeCurveService = surgeCurveService;
        this.clock = clock;
        this.marineWaveRefreshService = marineWaveRefreshService;
        this.regionSnapshotService = regionSnapshotService;
        this.assembler = assembler;
        this.rollup = rollup;
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
            // bestBetStatus MUST be carried through. This rebuild overlays live aurora and hot
            // topics; it is not a new verdict on the best-bet advisor, so dropping the status
            // silently disables two things that switch on it — the serve-time fallback in
            // applyBestBetFallback (which returns early unless the status is FAILED) and the
            // frontend's "from an earlier forecast" chip. Both would go dark on exactly the
            // requests that reach this branch, i.e. whenever aurora is live or a hot-topic
            // simulation is toggled. The 12-arg convenience constructor defaults it to null,
            // which is why this passes all 13 explicitly.
            return new DailyBriefingResponse(
                    cached.generatedAt(), cached.headline(), cached.days(), cached.bestBets(),
                    liveTonight, liveTomorrow, cached.stale(), cached.partialFailure(),
                    cached.failedLocationCount(), cached.bestBetModel(),
                    liveTopics, cached.seasonalFeatures(), cached.bestBetStatus());
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
        // The ordered composition itself — projector outermost, honesty wrapping the fallback,
        // movement attached here rather than inside getServedBriefing, the tide rollup fed the
        // FILTERED days, UTC not London — is ServedBriefingAssembler's job now; see
        // ServedBriefingAssembler.assembleForPlan for the rules and why each one holds.
        return assembler.assembleForPlan(getCachedBriefing(), minCoverageRatio);
    }

    /**
     * The served briefing <b>without</b> the Plan-tab window projection — for panels that read
     * slots and never read {@link BriefingEventSummary#window()}.
     *
     * <p>Identical to {@link #getCachedBriefingForApi()} in every other respect: the same
     * re-enrichment, the same serve-time best-bet fallback, and the same honesty filter, in the
     * same order. That matters, because the whole point of a panel sharing this accessor is that
     * two panels cannot disagree about the same location — and the window projection only
     * <em>attaches</em> a window, leaving slots, verdicts and {@code bestBets} untouched.
     *
     * <p>It exists because the projection is no longer free. Deriving the per-window tide rollup
     * costs a coastal-roster query, a multi-day {@code tide_extreme} scan, the representative's
     * historical statistics and a {@code marine_wave} lookup per window — and
     * {@code CloseToHomeService}, the one other caller, discards every byte of it. The Plan tab
     * fetches both endpoints on load, so leaving them joined paid that cost twice per page.
     *
     * <p>⚠️ This is <em>not</em> the API payload. Anything served to a client that renders windows
     * must go through {@link #getCachedBriefingForApi()}, or the Plan tab silently loses its
     * verdict badges, its picks and its tide rows.
     *
     * @return the enriched, filtered briefing with no windows attached, or {@code null} if none has
     *         been built yet
     */
    public DailyBriefingResponse getServedBriefing() {
        return assembler.assembleWithoutPlan(getCachedBriefing(), minCoverageRatio);
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
     * locations across the next {@link #BRIEFING_WINDOW_DAYS} dates, rolling up by region per
     * solar event.
     *
     * <p>Logs a {@link RunType#BRIEFING} job run for metrics tracking.
     */
    public void refreshBriefing() {
        LOG.info("Daily briefing refresh started");
        long briefingStart = System.currentTimeMillis();
        JobRunEntity jobRun = jobRunService.startRun(RunType.BRIEFING, false, null);

        LocalDate today = LocalDate.now(clock.withZone(LONDON));
        List<LocalDate> dates = IntStream.range(0, BRIEFING_WINDOW_DAYS)
                .mapToObj(today::plusDays)
                .toList();

        // Candidacy is tested across the whole briefed window, not just today: a bluebell site
        // whose bloom opens in two days belongs in a briefing that already covers that day, and
        // one whose bloom ends tomorrow should stay until it does.
        List<LocationEntity> colourLocations = locationService.findAllEnabled().stream()
                .filter(loc -> dates.stream().anyMatch(d -> isColourLocation(loc, d)))
                .toList();

        if (colourLocations.isEmpty()) {
            LOG.info("No enabled colour locations — skipping briefing");
            jobRunService.completeRun(jobRun, 0, 0);
            return;
        }

        int succeeded = 0;
        int failed = 0;

        // Fetch weather sequentially — the @RateLimiter on fetchForecast() throttles naturally
        // at 8 calls/second, so sequential fetching scales to any number of locations without
        // exhausting the rate limiter.
        List<BriefingSlotBuilder.LocationWeather> locationWeathers =
                fetchWeatherSequential(colourLocations, jobRun);

        // Fetch horizon cloud data (one batch call for all unique horizon grid cells)
        HorizonCloudData horizonData = fetchHorizonCloud(colourLocations, jobRun);

        // Fetch + persist coastal sea-state (waves) for the enriched king/spring/surge pills.
        // Isolated like the NLC refresh below — an optional enrichment source must never abort the
        // briefing cycle (a transient DB or solar error would otherwise leave the job run dangling).
        try {
            marineWaveRefreshService.refresh(colourLocations, dates, jobRun.getId());
        } catch (Exception e) {
            LOG.warn("Marine wave refresh failed — sea-state facts may be absent: {}", e.getMessage());
        }

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
                    // A wood is briefed at dawn only. Mist forms overnight and burns off through
                    // the morning, so the hours after sunrise are when a canopy location is at its
                    // best — and it is then nearly flat from mid-morning until dusk. Scoring a
                    // sunset column for it would manufacture a "best evening hour" that the
                    // subject does not have. The column is left empty rather than filled with
                    // fiction. (findBestIndex already samples at or just after sunrise, so the
                    // hour itself needs no adjustment.)
                    if (lw.location().isWoodlandOnly() && eventType != TargetType.SUNRISE) {
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
        // The BUILD path's resolver: a per-region lookup. The serve path hands the same
        // rollup a bulk index instead — one owner, two timings, which is the whole point of
        // RegionScoreResolver being a parameter rather than a collaborator.
        days = rollup.enrich(days, evaluationViewService::getScoresForEnrichment);

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

        // Refresh the meteor overhead-clarity cache — samples total cloud above each dark-sky site,
        // but only on shower-peak nights, so the meteor topic can show "clear at X of Y dark-sky
        // locations". Failure only drops that fact, never the topic.
        try {
            meteorClarityService.refresh(dates);
        } catch (Exception e) {
            LOG.warn("Meteor clarity refresh failed — clear-sky fact may be omitted: {}",
                    e.getMessage());
        }

        // Refresh the hourly storm-surge curve from weather ALREADY fetched above — no HTTP, no DB,
        // just the pure surge calculation run once per local hour. Isolated like the others: a
        // failure here costs the chart, never the briefing.
        //
        // Over `dates` rather than the narrower hot-topic window on purpose: serve time uses the
        // REQUEST day, so a briefing built today and served tomorrow asks for a date one further
        // out than this build's own horizon.
        try {
            surgeCurveService.refresh(locationWeathers, dates);
        } catch (Exception e) {
            LOG.warn("Surge curve refresh failed — surge chart may be absent: {}", e.getMessage());
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
            recordRegionSnapshots(response);
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
                // Snapshotted like the healthy branch, and deliberately: this partial response IS
                // what gets served, so it is what the next run's movement must be measured
                // against. The stale branch above is NOT snapshotted — it re-serves the previous
                // build's own days under that build's own stamp, so writing rows for it would
                // record one build twice and make the next delta read zero.
                recordRegionSnapshots(response);
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
     * Records what this build displayed per region, so the next serve can say which way it moved.
     *
     * <p>Isolated like the NLC, meteor and surge refreshes above, and for the same reason: an
     * optional enrichment sink must never abort a briefing cycle or leave its job run dangling. A
     * failure here costs the movement chip until the next build, nothing else — and the frontend
     * renders a null delta as silence, so there is no degraded state to explain.
     *
     * <p><b>After enrichment, and from the response's own days.</b> The rows must hold the
     * {@code meanRating} the payload publishes, not a number recomputed here: the chip is printed
     * beside that star, and a movement figure that cannot be reconciled with the number it
     * qualifies is worse than none.
     *
     * @param response the response just cached
     */
    private void recordRegionSnapshots(DailyBriefingResponse response) {
        try {
            regionSnapshotService.record(response.generatedAt(), response.days());
        } catch (Exception e) {
            LOG.warn("Region snapshot write failed — the Plan strip shows no movement until the "
                    + "next build: {}", e.getMessage());
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
     * Determines whether a location is a candidate for the colour (sunrise/sunset) briefing on the
     * given date.
     *
     * <p>A location qualifies on either of two grounds:
     * <ul>
     *   <li>it carries a <b>year-round colour type</b> — LANDSCAPE, SEASCAPE or WATERFALL; or</li>
     *   <li>it is a <b>BLUEBELL site and the bloom is on</b>, which is a seasonal candidacy that
     *       expires with the window.</li>
     * </ul>
     *
     * <p>The seasonal clause is the point. BLUEBELL is not a colour type in its own right: an
     * enclosed wood has no horizon, so outside the bloom there is nothing for a sunrise forecast
     * to be about. Before this, the check admitted anything that was not WILDLIFE, so every
     * bluebell wood was a year-round candidate and sites like Houghall Woods were briefed in July.
     * The former {@code bluebell_evaluate_year_round} flag existed to subtract exactly that
     * over-inclusion; it is gone (V132), because "is this wood worth an out-of-season trip" is
     * already answered by whether the site also carries LANDSCAPE.
     *
     * <p>A location with no types at all still qualifies — an untyped location is treated as a
     * plain colour location rather than silently dropped from the briefing.
     *
     * @param location the location to check
     * @param date     the date being briefed, tested against the bluebell {@link SeasonalWindow}
     * @return true if the location is a colour candidate on that date
     */
    boolean isColourLocation(LocationEntity location, LocalDate date) {
        if (location.getLocationType().isEmpty()) {
            return true;
        }
        // WOODLAND counts as year-round (V134): a wood has a subject in October, it just is not
        // the sky. BLUEBELL alone does not — that is a seasonal display, not a place to stand.
        boolean hasYearRoundColourType = location.getLocationType().stream()
                .anyMatch(t -> t != LocationType.WILDLIFE && t != LocationType.BLUEBELL);
        if (hasYearRoundColourType) {
            return true;
        }
        return location.getLocationType().contains(LocationType.BLUEBELL)
                && bluebellSeason.isActive(date);
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
