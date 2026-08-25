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
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.BestBetResult;
import com.gregochr.goldenhour.model.BestBetStatus;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingRefreshedEvent;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final BriefingEvaluationService briefingEvaluationService;
    private final EvaluationViewService evaluationViewService;
    private final com.gregochr.goldenhour.service.pipeline.BestBetFallbackService bestBetFallbackService;
    private final SeasonalWindow bluebellSeason;
    private final NlcClarityService nlcClarityService;
    private final MeteorClarityService meteorClarityService;
    private final SurgeCurveService surgeCurveService;
    private final java.time.Clock clock;
    private final MarineWaveRefreshService marineWaveRefreshService;
    private final WindowTideRollupBuilder windowTideRollupBuilder;
    private final BriefingRegionSnapshotService regionSnapshotService;

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
     * @param briefingEvaluationService  cached Claude evaluation scores (lazy to break cycle)
     * @param evaluationViewService      merged evaluation view service (lazy to break cycle)
     * @param bestBetFallbackService     serves the fail-safe stale best-bet fallback on FAILED
     * @param bluebellSeason             the configured bluebell season window
     * @param nlcClarityService          caches which nights have a clear dark-sky NLC chance
     * @param meteorClarityService       caches overhead dark-sky clarity for shower-peak nights
     * @param surgeCurveService          caches the hourly storm-surge curve computed from the
     *                                   weather this build has already fetched
     * @param clock                      UTC clock supplying "now" and (via London) "today"
     * @param marineWaveRefreshService   fetches + persists coastal sea-state each briefing cycle
     * @param windowTideRollupBuilder    derives the Plan tab's per-window tide rollup at serve time
     * @param regionSnapshotService      records what each build displayed, and reads the previous
     *                                   build back so the Plan strip can show which way it moved
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
            MeteorClarityService meteorClarityService,
            SurgeCurveService surgeCurveService,
            java.time.Clock clock,
            MarineWaveRefreshService marineWaveRefreshService,
            WindowTideRollupBuilder windowTideRollupBuilder,
            BriefingRegionSnapshotService regionSnapshotService) {
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
        this.meteorClarityService = meteorClarityService;
        this.surgeCurveService = surgeCurveService;
        this.clock = clock;
        this.marineWaveRefreshService = marineWaveRefreshService;
        this.windowTideRollupBuilder = windowTideRollupBuilder;
        this.regionSnapshotService = regionSnapshotService;
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
        // The window projection is deliberately OUTERMOST. It reads regions to name a Best Bet, a
        // verdict and a rating, so it must see the same regions the caller does — and the honesty
        // filter blanks a zero-coverage region to STAND_DOWN with an empty slot list on the way
        // past. Projected any earlier, a window would confidently describe a region the same
        // response reports as unevaluable. See PlanWindowProjector.
        //
        // The honesty filter wraps the fallback rather than sitting inside it, so that EVERY pick
        // on the way out has been checked against the regions blanked on this serve. With the
        // order reversed, a fallback swapped in afterwards was the one list never checked — and it
        // is the most likely to fail the check, being picks from an earlier forecast offered
        // precisely because this one's advisor failed. The stale chip tells a reader the picks are
        // old; it does not tell them the region behind one has no forecast at all.
        //
        // The fallback still runs on the ENRICHED response, so its FAILED test and the filter's
        // coverage test both read current state. Nothing else about it is order-sensitive: it
        // reads only bestBetStatus and passes the day hierarchy through untouched.
        // Movement is attached HERE and not inside getServedBriefing, and the distinction is the
        // same one that method's own javadoc twenty lines up was written to make: CloseToHomeService
        // shares that accessor and reads slots and bestBets only, so a movement step inside it would
        // cost /api/user/settings/reach two queries and a full rebuild of the day hierarchy for a
        // field it discards — the exact "paid twice per page, one copy thrown away" pattern the
        // projection was moved out here to stop. Nothing renders a delta except the Plan tab, and
        // this is the Plan tab's payload.
        //
        // Before the projector, deliberately: the projector is the outermost step and rebuilds the
        // response through withPlan, which carries previousGeneratedAt and every region untouched.
        DailyBriefingResponse filtered = attachMovement(getServedBriefing());
        // The tide rollup is derived here rather than inside the projector so the projector stays a
        // pure function of the response: it needs three repositories, and the projector has none.
        // It is fed the FILTERED days for one reason only — the same day list the windows are
        // projected from, so a rollup can never be keyed to a window that no longer exists.
        //
        // UTC, deliberately not LONDON: BriefingSlot.solarEventTime (and every other stored clock
        // time this "now" is compared against inside the projector's isPast/afterglow check) is
        // UTC. Reading "now" through Europe/London put it an hour ahead of that axis during BST,
        // which declared a window past up to an hour early — see plan-verdict-consolidation-plan.md
        // §1 D4. This is the "one clock, two calendars" rule from the daysAhead work: DATES are
        // London, INSTANT comparisons are UTC, and this is an instant comparison.
        return PlanWindowProjector.apply(
                filtered,
                LocalDateTime.now(clock.withZone(ZoneOffset.UTC)),
                filtered == null ? java.util.Map.of()
                        : windowTideRollupBuilder.build(filtered.days()));
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
        return BriefingHonestyFilter.apply(
                applyBestBetFallback(reEnrichVerdicts(getCachedBriefing())),
                minCoverageRatio);
    }

    /**
     * Attaches each region's movement since the previous briefing build, and names that build.
     *
     * <p>A sibling step beside {@link #enrichWithCachedScores} rather than a clause inside it,
     * because the two run on different paths for different reasons. Enrichment runs on the build
     * path as well, and a delta computed there would be persisted into
     * {@code daily_briefing_cache} — where it would be a frozen figure measured against a build
     * that is by then two behind, re-served for hours. Movement is a serve-time quantity only.
     *
     * <p><b>After {@link BriefingHonestyFilter}.</b> The filter blanks a zero-coverage region by
     * rewriting it with a null {@code meanRating}, so running afterwards means a blanked region can
     * never acquire a delta — no null check about it is needed here, and none can be forgotten.
     * Running before would also have handed the filter's positional rewrite a field to drop
     * silently.
     *
     * <p><b>Its cost is two indexed queries per Plan-tab serve, and the try/catch is what makes it
     * safe.</b> {@code previousBuild} throws rather than swallowing — see its own javadoc for why a
     * catch inside a transactional method cannot work — so the isolation is here, outside the bean
     * boundary, exactly as {@link #recordRegionSnapshots} does on the write side. Every failure
     * mode (no snapshot rows, no earlier build, a repository error) then resolves to no delta
     * anywhere, which the frontend renders as nothing at all.
     *
     * @param response the honesty-filtered response (may be {@code null})
     * @return a copy carrying per-region deltas and the basis they were measured against
     */
    private DailyBriefingResponse attachMovement(DailyBriefingResponse response) {
        if (response == null || response.days().isEmpty()) {
            return response;
        }
        BriefingRegionSnapshotService.PreviousBuild previous;
        try {
            previous = regionSnapshotService.previousBuild(response.generatedAt());
        } catch (Exception e) {
            LOG.warn("Movement lookup failed — the Plan tab renders no movement this serve: {}",
                    e.getMessage());
            return response;
        }
        if (previous.isEmpty()) {
            // No basis: publish neither deltas nor a stamp. A `previousGeneratedAt` with no delta
            // behind it would name a comparison the payload does not contain.
            return response;
        }
        List<BriefingDay> withMovement = new ArrayList<>(response.days().size());
        for (BriefingDay day : response.days()) {
            List<BriefingEventSummary> events = new ArrayList<>(day.eventSummaries().size());
            for (BriefingEventSummary es : day.eventSummaries()) {
                List<BriefingRegion> regions = es.regions().stream()
                        .map(region -> region.withMeanRatingDelta(
                                BriefingRegionSnapshotService.delta(
                                        region.meanRating(),
                                        previous.meanByKey().get(BriefingRegionSnapshotService.key(
                                                region.regionName(), day.date(),
                                                es.targetType().name())))))
                        .toList();
                events.add(es.withRegions(regions));
            }
            // The WITHER, never `new BriefingDay(date, events)`: that form defaults `peak` away,
            // and it is safe here only because the sole producer of a peak runs strictly after this
            // step. The two-arg constructor's own javadoc names this as the trap it exists to avoid.
            withMovement.add(day.withEventSummaries(events));
        }
        return response.withMovement(withMovement, previous.generatedAt());
    }

    /**
     * Re-derives each region's Claude-rating rollup (slot scores, {@code displayVerdict},
     * {@code scoredLocationCount}, gloss validity) from the <em>current</em> evaluation state at
     * serve time, so the verdict a cell shows never lags behind the rating batch that has since
     * re-scored it.
     *
     * <p>The region {@code displayVerdict} is otherwise frozen when the briefing is built
     * (every ~8h), while the per-location scores the frontend badges are read live through
     * {@link EvaluationViewService}. That gap let a stale "Worth it" label sit above a fresh low
     * rating. Re-running the same enrichment used at build time — sourced from the same
     * {@link EvaluationViewService} the badge reads — makes the label and the rating coherent by
     * construction, with no logic pushed into the render layer.
     *
     * <p>Scoped to the API read path only, for the same reason as {@link BriefingHonestyFilter}:
     * internal callers of {@link #getCachedBriefing()} (batch task collector, model-comparison
     * harness) need the untransformed triage slots to decide what to evaluate.
     *
     * <p>The current scores are pulled with a single {@link EvaluationViewService#getScoresForEnrichmentBulk}
     * load over the plan window rather than a lookup per region/date/target, so re-enriching on
     * every request stays to O(locations) queries.
     *
     * @param response the cached briefing (may be {@code null})
     * @return a copy whose regions carry freshly re-derived verdicts, or {@code null} if input was
     */
    private DailyBriefingResponse reEnrichVerdicts(DailyBriefingResponse response) {
        if (response == null || response.days().isEmpty()) {
            return response;
        }
        LocalDate start = response.days().getFirst().date();
        LocalDate end = response.days().getLast().date();
        Set<TargetType> types = response.days().stream()
                .flatMap(day -> day.eventSummaries().stream())
                .map(BriefingEventSummary::targetType)
                .collect(java.util.stream.Collectors.toCollection(
                        () -> java.util.EnumSet.noneOf(TargetType.class)));
        if (types.isEmpty()) {
            return response;
        }
        Map<String, Map<String, BriefingEvaluationResult>> index =
                evaluationViewService.getScoresForEnrichmentBulk(start, end, types);
        RegionScoreResolver resolver = (regionName, date, targetType) ->
                index.getOrDefault(regionName + "|" + date + "|" + targetType, Map.of());
        return response.withDays(enrichWithCachedScores(response.days(), resolver));
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
     * <p><b>Runs BEFORE {@link BriefingHonestyFilter}, and must stay there.</b> Picks substituted
     * here are therefore still subject to withdrawal, which is the whole reason the filter wraps
     * this method rather than sitting inside it: a stale list is the one most likely to name a
     * region this serve cannot evaluate, being picks from an earlier forecast offered precisely
     * because this cycle's advisor failed. Hoist the filter back inside and this list becomes the
     * only one never coverage-checked — which is how a stale pick came to crown a blanked region.
     * One test guards the ordering: {@code fallbackPickOnBlankedRegion_isWithdrawn}.
     *
     * @param response the re-enriched response, not yet honesty-filtered (may be null)
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
                // Carried, not cleared: this now runs BEFORE the honesty filter, so there is no
                // withdrawal yet to describe — the filter sets the flag afterwards, against
                // whichever list it ends up seeing, including this one.
                response.bestBetStatus(), response.bestBetsWithdrawn());
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
     * Resolves the cached Claude scores for one region/date/target, keyed by location name.
     * Lets {@link #enrichWithCachedScores(List, RegionScoreResolver)} run against either a
     * per-region lookup (build time) or a pre-loaded bulk index (serve time).
     */
    @FunctionalInterface
    private interface RegionScoreResolver {
        Map<String, BriefingEvaluationResult> resolve(String regionName, LocalDate date,
                TargetType targetType);
    }

    /**
     * Walks the day/event/region hierarchy and populates each slot's Claude fields from the
     * evaluation cache, resolved one region/date/target at a time. Returns a rebuilt hierarchy
     * with enriched slots; the original is unchanged.
     */
    private List<BriefingDay> enrichWithCachedScores(List<BriefingDay> days) {
        return enrichWithCachedScores(days, evaluationViewService::getScoresForEnrichment);
    }

    /**
     * Enriches the hierarchy using the supplied {@link RegionScoreResolver}. The build path passes
     * the per-region {@code getScoresForEnrichment} lookup; the serve path passes a resolver backed
     * by a single bulk load so it does not fan out into a query per region/date/target.
     */
    private List<BriefingDay> enrichWithCachedScores(List<BriefingDay> days,
            RegionScoreResolver resolver) {
        // Request-time "today" so the confidence horizon stays fresh when a briefing built
        // yesterday is served today (this method runs on both the build and the serve paths).
        LocalDate today = LocalDate.now(clock.withZone(LONDON));
        List<BriefingDay> enrichedDays = new ArrayList<>(days.size());
        for (BriefingDay day : days) {
            List<BriefingEventSummary> enrichedEvents = new ArrayList<>();
            for (BriefingEventSummary es : day.eventSummaries()) {
                List<BriefingRegion> enrichedRegions = new ArrayList<>();
                for (BriefingRegion region : es.regions()) {
                    Map<String, BriefingEvaluationResult> cached =
                            resolver.resolve(region.regionName(), day.date(), es.targetType());
                    List<BriefingSlot> enrichedSlots = region.slots().stream()
                            .map(slot -> enrichSlot(slot, cached))
                            .toList();
                    // TWO rollups over one slot list, because two different questions are asked
                    // of it and answering both with one number is what let a wood set a sky
                    // region's band.
                    //
                    //   coverageStats — every slot, because "how many locations here were
                    //   evaluated" counts the wood: it IS evaluated, by the bluebell or woodland
                    //   prompt, and both arms render "N of M evaluated" against the full slot list.
                    //
                    //   votingStats — the voting slots only (BriefingSlot.votingSlots), because the
                    //   VERDICT and the star are a claim about the sky. A canopy GO means heavy
                    //   cloud and mist, the opposite of what it means for a sky window, so a rated
                    //   wood used to lift the region's band — and with it the grid cell, the day
                    //   card and (since the verdict became region-led) the window badge — above
                    //   every rating those surfaces could show. Every other consumer of a region
                    //   verdict already excluded canopy; this was the one place that did not.
                    List<BriefingRatingStats.Entry> coverageEntries = enrichedSlots.stream()
                            .map(s -> new BriefingRatingStats.Entry(
                                    s.locationName(), s.claudeRating()))
                            .toList();
                    BriefingRatingStats.Stats coverageStats = BriefingRatingStats.compute(
                            coverageEntries, region.regionName(), day.date(), es.targetType());
                    List<BriefingRatingStats.Entry> votingEntries =
                            BriefingSlot.votingSlots(enrichedSlots).stream()
                                    .map(s -> new BriefingRatingStats.Entry(
                                            s.locationName(), s.claudeRating()))
                                    .toList();
                    BriefingRatingStats.Stats votingStats = BriefingRatingStats.compute(
                            votingEntries, region.regionName(), day.date(), es.targetType());
                    // Only warn about zero coverage where coverage was actually expected. An
                    // all-canopy region has none by construction and the honesty filter now
                    // passes it through untouched, so logging it here would fire per region per
                    // event on EVERY serve — drowning the line an operator greps for a genuine
                    // overnight batch failure, and asserting a rewrite that no longer happens.
                    boolean anyScoreable = enrichedSlots.stream()
                            .anyMatch(slot -> !(slot.canopy() && slot.claudeRating() == null));
                    if (coverageStats.isEmpty() && anyScoreable) {
                        LOG.info("[ZERO COVERAGE] region={} date={} target={} "
                                        + "briefingVerdict={} scoredCount=0 "
                                        + "(honesty filter will rewrite at API read time — "
                                        + "post-Gate-2 this should only fire on batch "
                                        + "failures or all-hard-constrained regions)",
                                region.regionName(), day.date(), es.targetType(),
                                region.verdict());
                    }
                    DisplayVerdict freshVerdict = BriefingRatingStats
                            .resolveRegionDisplayVerdict(votingStats, region.verdict());
                    // A gloss is Claude prose written against the verdict that held when the
                    // briefing was built. When read-time re-enrichment moves the verdict —
                    // a batch re-scored the region after build — that prose can now contradict
                    // the fresh rating (a glowing gloss on a downgraded cell). Drop it rather
                    // than show copy that disagrees with the score; regenerating would cost a
                    // Claude call. At build time the gloss is still null (generated afterwards),
                    // so this is a no-op on the write path.
                    boolean verdictChanged = region.displayVerdict() != freshVerdict;
                    String glossHeadline = verdictChanged ? null : region.glossHeadline();
                    String glossDetail = verdictChanged ? null : region.glossDetail();
                    // Derive the quiet confidence channel from horizon + rating spread/coverage.
                    // A region with NOTHING scored yields null, the documented unknown case.
                    int daysAhead = (int) (day.date().toEpochDay() - today.toEpochDay());
                    // Coverage denominator counts only slots that COULD carry a rating. A
                    // canopy slot is excluded from the sky batch, so counting it would report a
                    // permanent, unfixable shortfall and downgrade a wood-bearing region's
                    // confidence a band every day — including for the sky locations in it.
                    // Keyed on what the slot actually carries, NOT on "is it canopy": an in-season
                    // bluebell site IS scored, by the bluebell prompt. See rosterOf for why it is
                    // now a denominator alone.
                    // The VOTING stats, so the channel qualifies the verdict it is attached to:
                    // the spread term stops counting a wood as sky locations disagreeing, which it
                    // is not. The coverage term's denominator stays `scoreable` (canopy-inclusive
                    // where rated), so for a wood-bearing region the ratio is marginally
                    // pessimistic — it can only make the channel more provisional, the safe
                    // direction.
                    //
                    // The FLOOR is not decoration. Handing `derive` empty voting stats returns
                    // null, and null does NOT read as "no confidence" on the client:
                    // `confidenceUtils.resolveConfidence` is fail-soft and falls back to a
                    // horizon-only tier capped at medium. So a region whose only rated slot is a
                    // wood — verdict from the triage fallback, no sky score behind it at all —
                    // would render MEDIUM where the canopy-inclusive stats could have yielded LOW.
                    // That is the channel reading LESS provisional at the moment it knows least,
                    // the exact inversion it exists to prevent. Something here was scored and none
                    // of it votes: LOW, explicitly.
                    //
                    // Null is still right where NOTHING is scored — the documented zero-coverage
                    // case — and `coverageStats` is what tells the two apart.
                    Confidence confidence = votingStats.isEmpty() && !coverageStats.isEmpty()
                            ? Confidence.LOW
                            : ConfidenceDeriver.derive(
                                    daysAhead, votingStats, rosterOf(enrichedSlots));
                    enrichedRegions.add(new BriefingRegion(
                            region.regionName(), region.verdict(), region.summary(),
                            region.tideHighlights(), enrichedSlots,
                            region.regionTemperatureCelsius(),
                            region.regionApparentTemperatureCelsius(),
                            region.regionWindSpeedMs(), region.regionWeatherCode(),
                            glossHeadline, glossDetail,
                            // Coverage, NOT the voting count: the honesty filter tests this
                            // against its own canopy-inclusive scoreable count, and both arms
                            // render "N of M evaluated" with M as the whole slot list. A voting
                            // count here would under-report a scored wood in the drill-down and
                            // could blank a region whose only evaluated location is one.
                            freshVerdict, coverageStats.count())
                            .withConfidence(confidence)
                            // The grid cell's star, from the SAME statistics as freshVerdict above,
                            // so the word and the number in one cell can no longer come from two
                            // computations. The grid used to derive this in the browser off a second
                            // endpoint joined on a region-name prefix — see plan §1 D2. Null rather
                            // than 0.0 when nothing is scored: "not rated" and "rated badly" are
                            // different statements and the cell renders them differently.
                            .withMeanRating(
                                    votingStats.isEmpty() ? null : votingStats.averageRating())
                            // The region rail's `best N★`, from the SAME statistics again — so the
                            // best, the mean and the verdict word describe one population and the
                            // best can never sit below the mean it is printed beside. The VOTING
                            // stats for the reason the mean uses them: a woodland GO means heavy
                            // cloud and mist, so a rated wood must not supply a sky region's best
                            // spot any more than it may set its band. Null rather than 0 when
                            // nothing that votes is scored — "not rated" and "rated badly" are
                            // different statements, and BriefingRatingStats.Stats.empty() reports
                            // maxRating 0 for the same reason its averageRating is 0.0.
                            .withBestRating(
                                    votingStats.isEmpty() ? null : votingStats.maxRating()));
                }
                enrichedEvents.add(es.withRegions(enrichedRegions));
            }
            enrichedDays.add(new BriefingDay(day.date(), enrichedEvents));
        }
        return enrichedDays;
    }

    /**
     * Derives the two roster sizes {@link ConfidenceDeriver} needs from a region's slots. They are
     * different filters over the same list, and getting either wrong is silent — hence one named,
     * tested method rather than two inline stream counts at the call site.
     *
     * <p><b>scoreable</b> — slots that COULD carry a rating, the coverage DENOMINATOR. A canopy
     * slot is excluded while it holds no rating: it is out of the sky batch, so counting it
     * unconditionally would report a permanent, unfixable shortfall and downgrade a wood-bearing
     * region every day, including for the sky locations in it. Keyed on what the slot actually
     * carries rather than on the season, so no clock is needed — an in-season bluebell wood IS
     * scored, by the bluebell prompt.
     *
     * <p>Since the canopy fix it is a denominator alone: the numerator handed to
     * {@link ConfidenceDeriver} is now over VOTING slots, so a rated wood is counted here and not
     * there. The ratio is therefore slightly pessimistic for a wood-bearing region, which can only
     * downgrade — the safe direction, and preferred to measuring coverage of a population the
     * verdict does not use.
     *
     * <p><b>voting</b> — slots that vote on the region VERDICT. Mirrors
     * {@code BriefingHierarchyBuilder.buildRegion} exactly, non-canopy slots with a fallback to
     * the full list for an all-canopy region, because a floor guarding a roster the verdict does
     * not use guards the wrong number.
     *
     * <p>For a wood-bearing region in bluebell season the two genuinely differ: the scored wood
     * counts toward coverage and still never votes.
     *
     * @param slots the region's slots for one date and event
     * @return the two denominators, never null
     */
    static ConfidenceDeriver.RegionRoster rosterOf(List<BriefingSlot> slots) {
        if (slots == null || slots.isEmpty()) {
            return new ConfidenceDeriver.RegionRoster(0, 0);
        }
        long scoreable = slots.stream()
                .filter(slot -> !(slot.canopy() && slot.claudeRating() == null))
                .count();
        return new ConfidenceDeriver.RegionRoster(
                (int) scoreable, BriefingSlot.votingSlots(slots).size());
    }

    /**
     * Enriches a single slot from the resolved evaluation for its location.
     *
     * <p><b>A resolved triage clears the rating.</b> The resolver returns the winner of a merge
     * across both evaluation stores, so a triaged entry is a positive statement — "the freshest
     * thing known about this slot is that it stood down before Claude was ever called" — and not
     * merely an absence. This used to overwrite only on a non-null rating, which meant it could
     * raise a rating but never retract one: a slot scored 4★ by an overnight batch kept that 4★ on
     * the payload after a later run triaged it, while the map and the region drill-down (which
     * merge through {@code EvaluationViewService.mergeToView}) already showed the stand-down. That
     * is the same rating reading two ways on one screen, and it is the half of the divergence that
     * gating the resolver alone cannot reach.
     *
     * <p>The prose goes with the rating. A summary written to explain a 4★ is worse than no
     * summary at all once the 4★ is gone — the same reasoning that drops a region gloss when
     * re-enrichment moves its verdict.
     *
     * <p>An absent entry still leaves the slot untouched: not being in the map is an absence, and
     * only a resolved triage is evidence.
     */
    private BriefingSlot enrichSlot(BriefingSlot slot,
            Map<String, BriefingEvaluationResult> cached) {
        BriefingEvaluationResult eval = cached.get(slot.locationName());
        if (eval == null) {
            return slot;
        }
        if (eval.rating() != null) {
            return slot.withClaudeScores(eval.rating(), eval.fierySkyPotential(),
                    eval.goldenHourPotential(), eval.summary(), eval.headline());
        }
        if (eval.triageReason() != null && slot.claudeRating() != null) {
            return slot.withClaudeScores(null, null, null, null, null);
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
