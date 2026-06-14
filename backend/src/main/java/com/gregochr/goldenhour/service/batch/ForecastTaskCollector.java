package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.CandidateDisposition;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.GridCellStabilityResult;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingGatingPolicy;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.ForecastStabilityClassifier;
import com.gregochr.goldenhour.service.FreshnessResolver;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import com.gregochr.goldenhour.util.TimeSlotUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Collects forecast evaluation tasks from the cached daily briefing and applies
 * triage, stability, and prefetch gates so the result is ready for direct
 * submission via {@link com.gregochr.goldenhour.service.evaluation.EvaluationService}.
 *
 * <p>Extracted from {@link ScheduledBatchEvaluationService} in v2.12.5 (Pass 3.2.1)
 * so the scheduled service shrinks to its two real responsibilities — concurrency
 * guarding and submission — while task-construction concerns (briefing read,
 * weather pre-fetch, cloud-point pre-fetch, per-task triage, stability gating,
 * near/far × inland/coastal bucketing) live behind this collaborator.
 *
 * <p>The collector exposes two methods, mirroring the two distinct flows in
 * {@code ScheduledBatchEvaluationService}:
 * <ul>
 *   <li>{@link #collectScheduledBatches()} — full overnight flow with near/far
 *       split (model per tier), four-bucket result, and the
 *       {@code minPrefetchSuccessRatio} gate that aborts if too many weather
 *       pre-fetches fail.</li>
 *   <li>{@link #collectRegionFilteredBatches(List)} — admin region-filtered flow
 *       using {@code BATCH_NEAR_TERM} for all tasks, two-bucket result, and a
 *       weaker prefetch gate (aborts only on zero results, not on partial
 *       degradation — preserving legacy admin-trigger behaviour).</li>
 * </ul>
 *
 * <p>The collector is stateless and Spring-singleton; concurrent invocation is
 * safe and the caller is responsible for serialising entry via the
 * {@code forecastBatchRunning} guard in {@link ScheduledBatchEvaluationService}.
 *
 * <p>All {@code [BATCH DIAG]} candidate-loop logging produced by the legacy
 * paths is preserved verbatim inside this class; per-bucket submission logs
 * remain at the call site.
 */
@Service
public class ForecastTaskCollector {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastTaskCollector.class);

    /**
     * The days-ahead threshold separating near-term from far-term batches.
     * Tasks with {@code daysAhead <= NEAR_TERM_MAX_DAYS} go to the near-term
     * batch; tasks with {@code daysAhead > NEAR_TERM_MAX_DAYS} go to the
     * far-term batch (subject to stability gating).
     */
    static final int NEAR_TERM_MAX_DAYS = 1;

    /**
     * Lowest horizon at which force-evaluation applies. T+0/T+1 are always
     * eligible under Gate 4 so never need forcing; force-eval only rescues
     * stability-gated far-out cells.
     */
    static final int FORCE_EVAL_MIN_DAYS_AHEAD = 2;

    /**
     * Highest horizon at which force-evaluation applies. Beyond T+3 there is no
     * batch model tier and the forecast is too volatile to back a headline.
     */
    static final int FORCE_EVAL_MAX_DAYS_AHEAD = 3;

    private final LocationService locationService;
    private final BriefingService briefingService;
    private final BriefingEvaluationService briefingEvaluationService;
    private final ForecastService forecastService;
    private final ForecastStabilityClassifier stabilityClassifier;
    private final ModelSelectionService modelSelectionService;
    private final OpenMeteoService openMeteoService;
    private final SolarService solarService;
    private final FreshnessResolver freshnessResolver;
    private final StabilitySnapshotProvider stabilitySnapshotProvider;

    /** Minimum ratio of successful weather pre-fetches to proceed (scheduled path only). */
    private final double minPrefetchSuccessRatio;

    /**
     * Hard cap on the number of stability-gated far-out candidates force-evaluated
     * per cycle because they are best-bet headline contenders. Keeps the extra
     * Claude spend tiny (a handful of far-term batch evals against ~£2.50/night)
     * and prevents "targeted" silently becoming "blanket". Zero disables the
     * feature.
     */
    private final int forceEvalCap;

    /**
     * Constructs the collector.
     *
     * @param locationService           service for retrieving enabled locations
     * @param briefingService           cached daily briefing accessor
     * @param briefingEvaluationService evaluation cache (read-only — freshness check)
     * @param forecastService           weather fetch + triage
     * @param stabilityClassifier       per-grid-cell stability classifier
     * @param modelSelectionService     resolves the active Claude model per run type
     * @param openMeteoService          bulk weather + cloud-point pre-fetch
     * @param solarService              solar azimuth and event time helpers
     * @param freshnessResolver         per-stability cache freshness thresholds
     * @param stabilitySnapshotProvider provides the latest stability snapshot
     * @param minPrefetchSuccessRatio   minimum prefetch ratio to proceed (scheduled path)
     * @param forceEvalCap              max force-evaluated headline candidates per cycle
     */
    public ForecastTaskCollector(LocationService locationService,
            BriefingService briefingService,
            BriefingEvaluationService briefingEvaluationService,
            ForecastService forecastService,
            ForecastStabilityClassifier stabilityClassifier,
            ModelSelectionService modelSelectionService,
            OpenMeteoService openMeteoService,
            SolarService solarService,
            FreshnessResolver freshnessResolver,
            StabilitySnapshotProvider stabilitySnapshotProvider,
            @Value("${photocast.batch.min-prefetch-success-ratio:0.5}")
            double minPrefetchSuccessRatio,
            @Value("${photocast.batch.force-eval-cap:6}")
            int forceEvalCap) {
        this.locationService = locationService;
        this.briefingService = briefingService;
        this.briefingEvaluationService = briefingEvaluationService;
        this.forecastService = forecastService;
        this.stabilityClassifier = stabilityClassifier;
        this.modelSelectionService = modelSelectionService;
        this.openMeteoService = openMeteoService;
        this.solarService = solarService;
        this.freshnessResolver = freshnessResolver;
        this.stabilitySnapshotProvider = stabilitySnapshotProvider;
        this.minPrefetchSuccessRatio = minPrefetchSuccessRatio;
        this.forceEvalCap = forceEvalCap;
    }

    /**
     * Collects scheduled forecast tasks bucketed by near/far × inland/coastal.
     *
     * <p>Returns an all-empty {@link ScheduledBatchTasks} value when:
     * <ul>
     *   <li>no cached briefing is available,</li>
     *   <li>no candidate slots survive the first pass (past dates, cached regions,
     *       non-eligible verdicts, unknown locations),</li>
     *   <li>weather pre-fetch returns zero results, or</li>
     *   <li>weather pre-fetch ratio falls below {@code minPrefetchSuccessRatio}.</li>
     * </ul>
     * Each abort path logs a clearly distinguishable reason at WARN/ERROR level so
     * operators can tell "no work this cycle" apart from "upstream degradation".
     *
     * @return bucketed tasks (possibly all-empty)
     */
    /**
     * Convenience overload: collects scheduled batches with nightly's
     * candidate-collection strategy and eligibility policy. Preserves the
     * legacy zero-arg API for callers (notably the admin {@code submitForecastBatch()}
     * path and any test harnesses) that don't supply a cycle-specific override.
     *
     * @return bucketed tasks (possibly all-empty)
     */
    public ScheduledBatchTasks collectScheduledBatches() {
        return collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE);
    }

    /**
     * Cycle-aware variant: same triage and bucketing as the nightly path, but
     * the candidate set and eligibility decisions are delegated to the supplied
     * strategy + policy. Publishes (persists) the stability snapshot, as the
     * nightly path requires.
     *
     * @param candidateStrategy filter deciding which event slots enter the candidate set
     * @param eligibilityPolicy per-candidate include/skip decision function
     * @return bucketed tasks (possibly all-empty)
     */
    public ScheduledBatchTasks collectScheduledBatches(
            CandidateCollectionStrategy candidateStrategy,
            EligibilityPolicy eligibilityPolicy) {
        return collectScheduledBatches(candidateStrategy, eligibilityPolicy, false);
    }

    /**
     * Fully-parameterised cycle-aware variant. Identical to
     * {@link #collectScheduledBatches(CandidateCollectionStrategy, EligibilityPolicy)}
     * except the caller controls whether the stability classification computed
     * during collection is persisted to the authoritative snapshot.
     *
     * <p><b>The {@code ephemeral} flag is the one real seam the intraday refresh
     * needs.</b> Nightly classifies and <em>publishes</em> ({@code ephemeral=false}),
     * making the morning's snapshot authoritative. Intraday re-classifies the
     * decision-window cells with fresh afternoon weather purely to drive its own
     * cost-gate, then <em>discards</em> ({@code ephemeral=true}) — the morning's
     * snapshot stays authoritative for everything else (other run types, the
     * admin stability view). The in-memory classification is always returned for
     * this cycle's eligibility decisions either way; only the {@code update()}
     * write-through is suppressed.
     *
     * @param candidateStrategy filter deciding which event slots enter the candidate set
     * @param eligibilityPolicy per-candidate include/skip decision function
     * @param ephemeral         when {@code true}, suppress the stability snapshot
     *                          write-through (compute-only); the morning snapshot
     *                          is left untouched
     * @return bucketed tasks (possibly all-empty)
     */
    public ScheduledBatchTasks collectScheduledBatches(
            CandidateCollectionStrategy candidateStrategy,
            EligibilityPolicy eligibilityPolicy,
            boolean ephemeral) {
        DailyBriefingResponse briefing = briefingService.getCachedBriefing();
        if (briefing == null) {
            LOG.warn("[BATCH DIAG] Forecast batch skipped: no cached briefing available");
            return ScheduledBatchTasks.empty();
        }

        EvaluationModel nearTermModel =
                modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM);
        EvaluationModel farTermModel =
                modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM);
        LOG.warn("[BATCH DIAG] Starting forecast batch — nearTermModel={}, farTermModel={}, "
                        + "briefing days={}",
                nearTermModel, farTermModel,
                briefing.days() != null ? briefing.days().size() : 0);

        List<CandidateDisposition> dispositions = new ArrayList<>();
        List<ForecastCandidate> candidates =
                collectForecastCandidates(briefing, dispositions, candidateStrategy);
        if (candidates.isEmpty()) {
            LOG.warn("[BATCH DIAG] Forecast batch: no evaluable locations found after "
                    + "task collection, skipping submission");
            return new ScheduledBatchTasks(
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.copyOf(dispositions));
        }

        LOG.info("Forecast batch: {} candidate task(s) — bulk pre-fetching weather",
                candidates.size());

        int uniqueLocationCount = countUniqueLocations(candidates);
        Map<String, WeatherExtractionResult> prefetchedWeather =
                prefetchBatchWeather(candidates);
        double successRatio = uniqueLocationCount > 0
                ? (double) prefetchedWeather.size() / uniqueLocationCount : 0.0;
        if (prefetchedWeather.isEmpty()) {
            LOG.error("Forecast batch: weather pre-fetch returned 0/{} locations "
                    + "— aborting (likely Open-Meteo outage)", uniqueLocationCount);
            return new ScheduledBatchTasks(
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.copyOf(dispositions));
        }
        if (successRatio < minPrefetchSuccessRatio) {
            LOG.error("Forecast batch: weather pre-fetch too degraded — {}/{} locations "
                            + "(ratio {}, threshold {}) — aborting (likely Open-Meteo outage)",
                    prefetchedWeather.size(), uniqueLocationCount,
                    String.format("%.2f", successRatio),
                    String.format("%.2f", minPrefetchSuccessRatio));
            return new ScheduledBatchTasks(
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.copyOf(dispositions));
        }
        if (prefetchedWeather.size() < uniqueLocationCount) {
            LOG.warn("Forecast batch: weather pre-fetch partial — {}/{} locations fetched, "
                            + "continuing with available data",
                    prefetchedWeather.size(), uniqueLocationCount);
        }

        CloudPointCache cloudCache;
        try {
            cloudCache = prefetchBatchCloudPoints(candidates, prefetchedWeather);
        } catch (Exception e) {
            LOG.warn("Forecast batch: cloud pre-fetch failed — continuing without cloud cache: {}",
                    e.getMessage());
            cloudCache = null;
        }

        Map<String, GridCellStabilityResult> stabilityByCell =
                classifyGridCellsAndPublishSnapshot(candidates, prefetchedWeather, ephemeral);

        List<EvaluationTask.Forecast> nearInland = new ArrayList<>();
        List<EvaluationTask.Forecast> nearCoastal = new ArrayList<>();
        List<EvaluationTask.Forecast> farInland = new ArrayList<>();
        List<EvaluationTask.Forecast> farCoastal = new ArrayList<>();
        List<EvaluationTask.Forecast> bluebell = new ArrayList<>();

        int skippedTriage = 0;
        int skippedStability = 0;
        int skippedError = 0;
        int includedNear = 0;
        int includedFar = 0;
        int includedForced = 0;
        int includedBluebell = 0;
        EligibilityAggregator agg = new EligibilityAggregator();

        // Best-bet headline contenders that would otherwise be stability-gated.
        // Capped so the extra spend stays tiny; forced evals flow through the
        // same far-term bucket as everything else (one path, not a fork).
        Set<String> forceEvalKeys = selectForceEvalKeys(briefing);
        int forcedCount = 0;

        LOG.warn("[BATCH DIAG] Starting triage loop — {} candidate tasks "
                        + "({} force-eval headline candidates, cap {})",
                candidates.size(), forceEvalKeys.size(), forceEvalCap);

        for (ForecastCandidate candidate : candidates) {
            int candidateDaysAhead = daysAheadFor(candidate.date());
            try {
                ForecastPreEvalResult preEval = forecastService.fetchWeatherAndTriage(
                        candidate.location(), candidate.date(), candidate.targetType(),
                        candidate.location().getTideType(), nearTermModel, false, null,
                        prefetchedWeather, cloudCache);
                // A non-null bluebell condition score is the in-season-bluebell-site signal
                // (the augmentor only populates it for a BLUEBELL site with an exposure, in
                // season). Null out of season → every branch below is a no-op, so the gate is
                // dormant and behaviour is unchanged.
                boolean bluebellInSeason = preEval.atmosphericData() != null
                        && preEval.atmosphericData().bluebellConditionScore() != null;
                boolean woodlandOnly = bluebellInSeason
                        && candidate.location().getBluebellExposure() == BluebellExposure.WOODLAND;
                // Woodland bluebell sites in season are evaluated by the bluebell prompt ALONE,
                // so the colour triage verdict does not gate them (bright overcast — ideal for a
                // bluebell carpet — would otherwise stand them down as a poor sky). Every other
                // candidate still respects the triage verdict.
                if (preEval.triaged() && !woodlandOnly) {
                    LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | reason=TRIAGED ({})",
                            candidate.location().getName(), candidate.date(),
                            candidate.targetType(), preEval.triageReason());
                    dispositions.add(new CandidateDisposition(
                            candidate.location().getId(), candidate.location().getName(),
                            candidate.date(), candidate.targetType(), candidateDaysAhead,
                            DispositionCategory.SKIPPED_TRIAGED, preEval.triageReason()));
                    skippedTriage++;
                    continue;
                }
                int daysAhead = preEval.daysAhead();
                ForecastStability stability = stabilityFor(
                        candidate.location(), preEval, stabilityByCell);
                EligibilityDecision decision = eligibilityPolicy.resolve(
                        daysAhead, stability, nearTermModel, farTermModel);
                boolean forced = false;
                if (!decision.eligible()) {
                    String forceKey = forceEvalKey(candidate.location().getName(),
                            candidate.date(), candidate.targetType());
                    if (forcedCount < forceEvalCap && forceEvalKeys.contains(forceKey)) {
                        // Headline contender the stability gate would drop — evaluate
                        // it anyway, on the far-term model, so a clear far-out day can
                        // be crowned with real Claude evidence behind it.
                        LOG.warn("[BATCH DIAG] FORCE-EVAL {} | date={} event={} | "
                                        + "headline candidate (gate would skip: {})",
                                candidate.location().getName(), candidate.date(),
                                candidate.targetType(), decision.skipReason());
                        decision = EligibilityDecision.include(farTermModel);
                        forced = true;
                        forcedCount++;
                    } else {
                        LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | reason={} ({})",
                                candidate.location().getName(), candidate.date(),
                                candidate.targetType(), decision.skipDisposition(),
                                decision.skipReason());
                        dispositions.add(new CandidateDisposition(
                                candidate.location().getId(), candidate.location().getName(),
                                candidate.date(), candidate.targetType(), daysAhead,
                                decision.skipDisposition(), decision.skipReason()));
                        agg.recordExcluded(daysAhead, stability);
                        skippedStability++;
                        continue;
                    }
                }

                boolean isNearTerm = daysAhead <= NEAR_TERM_MAX_DAYS;

                if (woodlandOnly) {
                    // Bluebell-only: ONE bluebell task, no sky task, no colour bucket (the OQ3
                    // exposure rule makes the bluebell score the rating for woodland).
                    bluebell.add(bluebellTaskFor(candidate, decision, preEval));
                    includedBluebell++;
                    agg.recordIncluded(daysAhead, stability);
                    if (forced) {
                        includedForced++;
                    }
                    dispositions.add(includeDisposition(candidate, daysAhead, forced));
                    LOG.warn("[BATCH DIAG] INCLUDE {} | date={} event={} | tier=bluebell "
                                    + "type=woodland{}",
                            candidate.location().getName(), candidate.date(),
                            candidate.targetType(), forced ? " (forced)" : "");
                    continue;
                }

                EvaluationTask.Forecast eval = new EvaluationTask.Forecast(
                        candidate.location(), candidate.date(), candidate.targetType(),
                        decision.model(), preEval.atmosphericData(),
                        EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
                boolean isCoastal = preEval.atmosphericData().tide() != null;
                String locationType = isCoastal ? "coastal" : "inland";

                if (isNearTerm) {
                    if (isCoastal) {
                        nearCoastal.add(eval);
                    } else {
                        nearInland.add(eval);
                    }
                    includedNear++;
                } else {
                    if (isCoastal) {
                        farCoastal.add(eval);
                    } else {
                        farInland.add(eval);
                    }
                    includedFar++;
                }

                // OPEN_FELL in season is scored by BOTH prompts: a sky task (above) and a paired
                // bluebell task (golden light flatters fell and flowers alike, so the combiner
                // averages the two). The bluebell result recombines with the sky at the
                // cache-merge step (C3b).
                boolean openFellPaired = bluebellInSeason
                        && candidate.location().getBluebellExposure()
                                == BluebellExposure.OPEN_FELL;
                if (openFellPaired) {
                    bluebell.add(bluebellTaskFor(candidate, decision, preEval));
                    includedBluebell++;
                }

                agg.recordIncluded(daysAhead, stability);
                if (forced) {
                    includedForced++;
                }
                dispositions.add(includeDisposition(candidate, daysAhead, forced));
                LOG.warn("[BATCH DIAG] INCLUDE {} | date={} event={} | tier={} type={}{}{}",
                        candidate.location().getName(), candidate.date(),
                        candidate.targetType(),
                        isNearTerm ? "near" : "far", locationType,
                        openFellPaired ? "+bluebell" : "",
                        forced ? " (forced)" : "");
            } catch (Exception e) {
                LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | reason=ERROR ({})",
                        candidate.location().getName(), candidate.date(),
                        candidate.targetType(), e.getMessage());
                dispositions.add(new CandidateDisposition(
                        candidate.location().getId(), candidate.location().getName(),
                        candidate.date(), candidate.targetType(), candidateDaysAhead,
                        DispositionCategory.SKIPPED_ERROR, e.getMessage()));
                skippedError++;
            }
        }

        int totalIncluded = includedNear + includedFar + includedBluebell;
        LOG.warn("[BATCH DIAG] Triage complete — {} included (near={}, far={}, bluebell={}, "
                        + "forced={}), {} skipped (triage={}, stability={}, error={})",
                totalIncluded, includedNear, includedFar, includedBluebell, includedForced,
                skippedTriage + skippedStability + skippedError,
                skippedTriage, skippedStability, skippedError);
        LOG.info("[BATCH ELIG] {}", agg.formatSummary());

        if (totalIncluded == 0) {
            LOG.info("Forecast batch: no evaluable locations after triage, skipping submission");
            return new ScheduledBatchTasks(
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.copyOf(dispositions));
        }

        return new ScheduledBatchTasks(nearInland, nearCoastal, farInland, farCoastal,
                bluebell, List.copyOf(dispositions));
    }

    /**
     * Days from today (Europe/London) to the given date. Negative for past
     * dates. Matches the {@code today} computation used in
     * {@link #collectForecastCandidates}.
     */
    private static int daysAheadFor(LocalDate date) {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));
        return (int) ChronoUnit.DAYS.between(today, date);
    }

    /**
     * Selects the capped set of far-out headline contenders to force-evaluate
     * this cycle, identified by {@code locationName|date|targetType} key.
     *
     * <p>Scans the briefing for far-out ({@value #FORCE_EVAL_MIN_DAYS_AHEAD}..{@value
     * #FORCE_EVAL_MAX_DAYS_AHEAD}) region/event cells with at least one
     * evaluation-eligible GO slot, ranks them by GO count then tide-aligned count
     * then horizon (sooner first), and accumulates the eligible GO slots of the
     * top cells until {@link #forceEvalCap} keys are gathered. These are the cells
     * most likely to win the best-bet headline; forcing them guarantees the
     * crowned region has real Claude coverage rather than cheap-threshold survivors.
     *
     * <p>Returns an empty set when the cap is zero (feature disabled) or no far-out
     * GO cells exist. The cap bounds the set size, so the per-cycle force-eval cost
     * is bounded regardless of how many GO candidates the briefing contains.
     *
     * @param briefing the cached briefing being collected
     * @return capped set of force-eval keys (possibly empty)
     */
    private Set<String> selectForceEvalKeys(DailyBriefingResponse briefing) {
        if (forceEvalCap <= 0 || briefing.days() == null) {
            return Set.of();
        }
        LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));
        List<ForceEvalCell> cells = new ArrayList<>();
        for (BriefingDay day : briefing.days()) {
            int daysAhead = (int) ChronoUnit.DAYS.between(today, day.date());
            if (daysAhead < FORCE_EVAL_MIN_DAYS_AHEAD || daysAhead > FORCE_EVAL_MAX_DAYS_AHEAD) {
                continue;
            }
            for (BriefingEventSummary es : day.eventSummaries()) {
                for (BriefingRegion region : es.regions()) {
                    if (region.slots() == null) {
                        continue;
                    }
                    List<String> goNames = new ArrayList<>();
                    long tideAligned = 0;
                    for (BriefingSlot slot : region.slots()) {
                        if (slot.verdict() == com.gregochr.goldenhour.model.Verdict.GO
                                && BriefingGatingPolicy.isEligibleForEvaluation(slot)) {
                            goNames.add(slot.locationName());
                            if (slot.tide() != null && slot.tide().tideAligned()) {
                                tideAligned++;
                            }
                        }
                    }
                    if (!goNames.isEmpty()) {
                        cells.add(new ForceEvalCell(day.date(), es.targetType(),
                                goNames.size(), tideAligned, daysAhead, goNames));
                    }
                }
            }
        }
        cells.sort(Comparator
                .comparingInt(ForceEvalCell::goCount).reversed()
                .thenComparing(Comparator.comparingLong(ForceEvalCell::tideAligned).reversed())
                .thenComparingInt(ForceEvalCell::daysAhead)
                .thenComparing(ForceEvalCell::date));
        Set<String> keys = new LinkedHashSet<>();
        for (ForceEvalCell cell : cells) {
            for (String name : cell.goLocationNames()) {
                if (keys.size() >= forceEvalCap) {
                    return keys;
                }
                keys.add(forceEvalKey(name, cell.date(), cell.targetType()));
            }
        }
        return keys;
    }

    /** Builds the {@code locationName|date|targetType} key used for force-eval matching. */
    private static String forceEvalKey(String locationName, LocalDate date,
            TargetType targetType) {
        return locationName + "|" + date + "|" + targetType.name();
    }

    /**
     * Builds a {@link EvaluationTask.Forecast.PromptKind#BLUEBELL} task for an in-season bluebell
     * candidate, reusing the same atmospheric data (which already carries the bluebell condition
     * score) and the eligibility decision's model as the sky path.
     */
    private static EvaluationTask.Forecast bluebellTaskFor(ForecastCandidate candidate,
            EligibilityDecision decision, ForecastPreEvalResult preEval) {
        return new EvaluationTask.Forecast(
                candidate.location(), candidate.date(), candidate.targetType(),
                decision.model(), preEval.atmosphericData(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE,
                EvaluationTask.Forecast.PromptKind.BLUEBELL);
    }

    /** Builds the EVALUATED / FORCE_EVALUATED disposition for an included candidate. */
    private static CandidateDisposition includeDisposition(ForecastCandidate candidate,
            int daysAhead, boolean forced) {
        return new CandidateDisposition(
                candidate.location().getId(), candidate.location().getName(),
                candidate.date(), candidate.targetType(), daysAhead,
                forced ? DispositionCategory.FORCE_EVALUATED : DispositionCategory.EVALUATED,
                forced ? "Force-evaluated best-bet headline candidate" : null);
    }

    /**
     * A far-out briefing region/event cell ranked as a best-bet headline contender.
     *
     * @param date            the event date
     * @param targetType      SUNRISE or SUNSET
     * @param goCount         number of evaluation-eligible GO slots
     * @param tideAligned     number of tide-aligned slots (headline differentiator)
     * @param daysAhead       forecast horizon
     * @param goLocationNames eligible GO slot location names, in briefing order
     */
    private record ForceEvalCell(LocalDate date, TargetType targetType, int goCount,
            long tideAligned, int daysAhead, List<String> goLocationNames) {
    }

    /**
     * Encodes the Gate 4 batch eligibility policy: which (daysAhead, stability)
     * combinations enter the batch, and which model tier evaluates them.
     *
     * <table>
     *   <caption>Eligibility table</caption>
     *   <tr><th>daysAhead</th><th>Eligibility</th><th>Model tier</th></tr>
     *   <tr><td>T+0, T+1</td><td>all stabilities</td>
     *       <td>{@code BATCH_NEAR_TERM}</td></tr>
     *   <tr><td>T+2</td><td>SETTLED or TRANSITIONAL</td>
     *       <td>{@code BATCH_FAR_TERM}</td></tr>
     *   <tr><td>T+3</td><td>SETTLED only</td>
     *       <td>{@code BATCH_FAR_TERM}</td></tr>
     *   <tr><td>T+4 and beyond</td><td>never eligible</td><td>—</td></tr>
     * </table>
     *
     * <p>UNSETTLED cells from T+1 onward are not evaluated by the batch — they
     * remain triage-only. The policy is intentionally independent of
     * {@code ForecastStability.evaluationWindowDays()}, which is now a
     * display-only depth hint for the admin UI.
     *
     * @param daysAhead     forecast horizon (T+0 = 0)
     * @param stability     classified stability for the grid cell
     * @param nearTermModel resolved {@code BATCH_NEAR_TERM} model for this run
     * @param farTermModel  resolved {@code BATCH_FAR_TERM} model for this run
     * @return include-with-model or skip-with-reason
     */
    static EligibilityDecision resolveEligibility(int daysAhead, ForecastStability stability,
            EvaluationModel nearTermModel, EvaluationModel farTermModel) {
        // Behaviour preserved for legacy callers and existing tests by delegating
        // to the policy where the table's authoritative implementation now lives.
        // Cycle-aware callers should pass an {@link EligibilityPolicy} directly
        // to {@link #collectScheduledBatches(CandidateCollectionStrategy,
        // EligibilityPolicy)} instead of calling this helper.
        return NightlyEligibilityPolicy.INSTANCE.resolve(
                daysAhead, stability, nearTermModel, farTermModel);
    }

    /**
     * Resolves the stability classification for a candidate, with a
     * TRANSITIONAL fallback for tasks that lack a grid cell or a forecast
     * response (matches the pre-Gate-4 behaviour of allowing T+0/T+1 for
     * unclassified locations; under the new policy, TRANSITIONAL adds T+2
     * as well, which is acceptable for the rare unclassified case).
     */
    private ForecastStability stabilityFor(LocationEntity location,
            ForecastPreEvalResult preEval,
            Map<String, GridCellStabilityResult> stabilityByCell) {
        if (!location.hasGridCell() || preEval.forecastResponse() == null) {
            return ForecastStability.TRANSITIONAL;
        }
        String key = location.gridCellKey();
        GridCellStabilityResult stability = stabilityByCell.computeIfAbsent(key, k ->
                stabilityClassifier.classify(
                        key, location.getGridLat(), location.getGridLng(),
                        preEval.forecastResponse().getHourly()));
        return stability != null ? stability.stability() : ForecastStability.TRANSITIONAL;
    }

    /**
     * Collects region-filtered forecast tasks for an admin batch.
     *
     * <p>Uses {@code BATCH_NEAR_TERM} model for every task and produces a
     * simpler inland/coastal bucketing (no near/far split). Returns an
     * all-empty value when the briefing has no candidates after filtering or
     * weather pre-fetch yields zero results. Unlike the scheduled path, the
     * region-filtered admin path tolerates partial-prefetch degradation
     * (no ratio threshold) — mirroring legacy behaviour.
     *
     * @param regionIds region IDs to include — null or empty means all regions
     * @return inland/coastal tasks (possibly all-empty)
     */
    public RegionFilteredBatchTasks collectRegionFilteredBatches(List<Long> regionIds) {
        DailyBriefingResponse briefing = briefingService.getCachedBriefing();
        if (briefing == null) {
            LOG.warn("[BATCH] Scheduled batch skipped: no cached briefing available");
            return RegionFilteredBatchTasks.empty();
        }

        Set<Long> regionFilter = (regionIds != null && !regionIds.isEmpty())
                ? new HashSet<>(regionIds) : null;

        EvaluationModel model = modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM);
        // Admin region-filtered path does not persist dispositions — the cycle
        // they belong to is the overnight scheduled batch, not these ad-hoc
        // admin triggers. The throwaway list keeps the helper signature uniform.
        List<CandidateDisposition> unusedDispositions = new ArrayList<>();
        List<ForecastCandidate> candidates =
                collectForecastCandidates(briefing, unusedDispositions,
                        NightlyCandidateCollectionStrategy.INSTANCE);

        if (regionFilter != null) {
            candidates = candidates.stream()
                    .filter(c -> c.location().getRegion() != null
                            && regionFilter.contains(c.location().getRegion().getId()))
                    .toList();
        }

        if (candidates.isEmpty()) {
            LOG.warn("[BATCH] No evaluable locations after region filtering");
            return RegionFilteredBatchTasks.empty();
        }

        Map<String, WeatherExtractionResult> prefetchedWeather =
                prefetchBatchWeather(candidates);
        if (prefetchedWeather.isEmpty()) {
            LOG.error("[BATCH] Weather pre-fetch returned zero results — aborting");
            return RegionFilteredBatchTasks.empty();
        }

        CloudPointCache cloudCache;
        try {
            cloudCache = prefetchBatchCloudPoints(candidates, prefetchedWeather);
        } catch (Exception e) {
            LOG.warn("[BATCH] Cloud pre-fetch failed — continuing without: {}", e.getMessage());
            cloudCache = null;
        }

        List<EvaluationTask.Forecast> inland = new ArrayList<>();
        List<EvaluationTask.Forecast> coastal = new ArrayList<>();
        Map<String, GridCellStabilityResult> stabilityByCell = new HashMap<>();

        for (ForecastCandidate candidate : candidates) {
            try {
                ForecastPreEvalResult preEval = forecastService.fetchWeatherAndTriage(
                        candidate.location(), candidate.date(), candidate.targetType(),
                        candidate.location().getTideType(), model, false, null,
                        prefetchedWeather, cloudCache);
                if (preEval.triaged()) {
                    continue;
                }
                int daysAhead = preEval.daysAhead();
                ForecastStability stability = stabilityFor(
                        candidate.location(), preEval, stabilityByCell);
                // Region-filtered admin batches only ever use the near-term model
                // (legacy contract). Pass `model` as both tiers so the policy's
                // include branch lands with the right model regardless of horizon.
                EligibilityDecision decision = NightlyEligibilityPolicy.INSTANCE.resolve(
                        daysAhead, stability, model, model);
                if (!decision.eligible()) {
                    continue;
                }
                EvaluationTask.Forecast eval = new EvaluationTask.Forecast(
                        candidate.location(), candidate.date(), candidate.targetType(),
                        decision.model(), preEval.atmosphericData(),
                        EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
                if (preEval.atmosphericData().tide() != null) {
                    coastal.add(eval);
                } else {
                    inland.add(eval);
                }
            } catch (Exception e) {
                LOG.warn("[BATCH] Failed data assembly for {}: {}",
                        candidate.location().getName(), e.getMessage());
            }
        }

        return new RegionFilteredBatchTasks(inland, coastal);
    }

    /**
     * First pass over the briefing: collects all GO/MARGINAL slots that are not
     * already cached. No API calls are made here.
     *
     * <p>Appends a {@link CandidateDisposition} to {@code dispositions} for
     * every slot the briefing considered, both inclusions (passed to the
     * triage loop) and skips (PAST_DATE, CACHED, HARD_CONSTRAINT,
     * UNKNOWN_LOCATION). Skips are recorded with {@code location_id = null}
     * since the verdict/cache/past-date paths never look the location up. The
     * inclusion entries are NOT written here; the triage loop assigns the
     * final disposition (EVALUATED / SKIPPED_TRIAGED / SKIPPED_STABILITY /
     * SKIPPED_ERROR) once weather + stability data is available.
     */
    private List<ForecastCandidate> collectForecastCandidates(DailyBriefingResponse briefing,
            List<CandidateDisposition> dispositions,
            CandidateCollectionStrategy candidateStrategy) {
        List<ForecastCandidate> candidates = new ArrayList<>();
        int skippedCache = 0;
        int skippedVerdict = 0;
        int skippedUnknown = 0;
        int skippedPastDate = 0;
        int totalSlots = 0;
        Map<ForecastStability, int[]> cachedByStability = new HashMap<>();
        Map<ForecastStability, int[]> eligibleByStability = new HashMap<>();
        for (ForecastStability s : ForecastStability.values()) {
            cachedByStability.put(s, new int[]{0});
            eligibleByStability.put(s, new int[]{0});
        }

        Map<String, ForecastStability> stabilityByLocation = buildStabilityLookup();

        // Use Europe/London because solar events are for UK locations — a sunrise
        // in Northumberland on April 19th BST is what matters, not the UTC date.
        LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));

        for (BriefingDay day : briefing.days()) {
            LocalDate date = day.date();
            int daysAhead = (int) ChronoUnit.DAYS.between(today, date);
            if (date.isBefore(today)) {
                int daySlots = 0;
                for (BriefingEventSummary eventSummary : day.eventSummaries()) {
                    TargetType targetType = eventSummary.targetType();
                    for (BriefingRegion region : eventSummary.regions()) {
                        if (region.slots() == null) {
                            continue;
                        }
                        for (BriefingSlot slot : region.slots()) {
                            dispositions.add(new CandidateDisposition(
                                    null, slot.locationName(), date, targetType, daysAhead,
                                    DispositionCategory.SKIPPED_PAST_DATE, "Date in past"));
                            daySlots++;
                        }
                    }
                }
                skippedPastDate += daySlots;
                totalSlots += daySlots;
                LOG.warn("[BATCH DIAG] SKIP date {} | reason=PAST_DATE ({} slots skipped)",
                        date, daySlots);
                continue;
            }
            for (BriefingEventSummary eventSummary : day.eventSummaries()) {
                TargetType targetType = eventSummary.targetType();
                // Cycle-specific window filter. Slots outside the cycle's window
                // are silently skipped — they are not "decided against", they
                // simply aren't this cycle's responsibility. Nightly's strategy
                // accepts everything; the intraday refresh will use this to
                // restrict to its decision window.
                if (!candidateStrategy.includes(date, targetType)) {
                    continue;
                }
                for (BriefingRegion region : eventSummary.regions()) {
                    String cacheKey = com.gregochr.goldenhour.service.evaluation.CacheKeyFactory
                            .build(region.regionName(), date, targetType);
                    ForecastStability regionStability = mostVolatileStability(
                            region, stabilityByLocation);
                    Duration freshness = freshnessResolver.maxAgeFor(regionStability);
                    int regionSlots = region.slots() != null ? region.slots().size() : 0;
                    eligibleByStability.get(regionStability)[0] += regionSlots;
                    if (briefingEvaluationService.hasFreshEvaluation(cacheKey, freshness)) {
                        LOG.warn("[BATCH DIAG] SKIP region {} | reason=CACHED "
                                        + "(stability={}, threshold={}h, {} slots skipped)",
                                cacheKey, regionStability,
                                freshness.toHours(), regionSlots);
                        String cachedDetail = String.format(
                                "Fresh cached evaluation within %dh (%s)",
                                freshness.toHours(), regionStability);
                        if (region.slots() != null) {
                            for (BriefingSlot slot : region.slots()) {
                                dispositions.add(new CandidateDisposition(
                                        null, slot.locationName(), date, targetType, daysAhead,
                                        DispositionCategory.SKIPPED_CACHED, cachedDetail));
                            }
                        }
                        cachedByStability.get(regionStability)[0] += regionSlots;
                        skippedCache += regionSlots;
                        totalSlots += regionSlots;
                        continue;
                    }
                    for (BriefingSlot slot : region.slots()) {
                        totalSlots++;
                        if (!BriefingGatingPolicy.isEligibleForEvaluation(slot)) {
                            String reasonLabel = BriefingGatingPolicy.isHardConstraintSkip(slot)
                                    ? "HARD_CONSTRAINT"
                                    : "VERDICT_" + slot.verdict();
                            LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | "
                                            + "reason={} ({})",
                                    slot.locationName(), date, targetType,
                                    reasonLabel, slot.standdownReason());
                            // Both hard-constraint tide skips and the (now rare,
                            // Gate-2-redesigned) VERDICT_* skips are hard physical
                            // gates — fold them into SKIPPED_HARD_CONSTRAINT with the
                            // standdown reason as detail.
                            dispositions.add(new CandidateDisposition(
                                    null, slot.locationName(), date, targetType, daysAhead,
                                    DispositionCategory.SKIPPED_HARD_CONSTRAINT,
                                    slot.standdownReason() != null ? slot.standdownReason()
                                            : reasonLabel));
                            skippedVerdict++;
                            continue;
                        }
                        LocationEntity location = findLocation(slot.locationName());
                        if (location == null) {
                            LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | "
                                            + "reason=UNKNOWN_LOCATION",
                                    slot.locationName(), date, targetType);
                            dispositions.add(new CandidateDisposition(
                                    null, slot.locationName(), date, targetType, daysAhead,
                                    DispositionCategory.SKIPPED_UNKNOWN_LOCATION,
                                    "Location not found in enabled set"));
                            skippedUnknown++;
                            continue;
                        }
                        candidates.add(new ForecastCandidate(location, date, targetType));
                    }
                }
            }
        }

        LOG.warn("[BATCH DIAG] Task collection complete — {} tasks from {} total slots "
                        + "(pastDate={}, cached={}, verdict={}, unknownLoc={})",
                candidates.size(), totalSlots, skippedPastDate, skippedCache,
                skippedVerdict, skippedUnknown);
        logStabilityBreakdown(eligibleByStability, cachedByStability);
        return candidates;
    }

    private int countUniqueLocations(List<ForecastCandidate> candidates) {
        Set<String> seen = new HashSet<>();
        for (ForecastCandidate c : candidates) {
            seen.add(OpenMeteoService.coordKey(c.location().getLat(),
                    c.location().getLon()));
        }
        return seen.size();
    }

    private Map<String, WeatherExtractionResult> prefetchBatchWeather(
            List<ForecastCandidate> candidates) {
        Map<String, double[]> uniqueCoords = new LinkedHashMap<>();
        for (ForecastCandidate c : candidates) {
            String key = OpenMeteoService.coordKey(c.location().getLat(),
                    c.location().getLon());
            uniqueCoords.putIfAbsent(key,
                    new double[]{c.location().getLat(), c.location().getLon()});
        }
        LOG.info("Forecast batch: weather pre-fetch for {} unique location(s) (from {} tasks)",
                uniqueCoords.size(), candidates.size());
        Map<String, WeatherExtractionResult> result =
                openMeteoService.prefetchWeatherBatchResilient(
                        new ArrayList<>(uniqueCoords.values()));
        return result != null ? result : Map.of();
    }

    private CloudPointCache prefetchBatchCloudPoints(List<ForecastCandidate> candidates,
            Map<String, WeatherExtractionResult> prefetchedWeather) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<double[]> allPoints = new ArrayList<>();

        for (ForecastCandidate c : candidates) {
            double lat = c.location().getLat();
            double lon = c.location().getLon();
            LocalDate date = c.date();
            TargetType targetType = c.targetType();

            int azimuth = targetType == TargetType.SUNRISE
                    ? solarService.sunriseAzimuthDeg(lat, lon, date)
                    : solarService.sunsetAzimuthDeg(lat, lon, date);

            allPoints.addAll(openMeteoService.computeDirectionalCloudPoints(lat, lon, azimuth));

            if (prefetchedWeather != null) {
                String coordKey = OpenMeteoService.coordKey(lat, lon);
                WeatherExtractionResult cached = prefetchedWeather.get(coordKey);
                if (cached != null && cached.forecastResponse() != null
                        && cached.forecastResponse().getHourly() != null) {
                    LocalDateTime eventTime = targetType == TargetType.SUNRISE
                            ? solarService.sunriseUtc(lat, lon, date)
                            : solarService.sunsetUtc(lat, lon, date);
                    OpenMeteoForecastResponse.Hourly h = cached.forecastResponse().getHourly();
                    List<String> times = h.getTime();
                    if (times != null && h.getWindDirection10m() != null
                            && h.getWindSpeed10m() != null) {
                        int idx = TimeSlotUtils.findNearestIndex(times, eventTime);
                        if (idx < h.getWindDirection10m().size()
                                && idx < h.getWindSpeed10m().size()) {
                            Integer windDir = h.getWindDirection10m().get(idx);
                            Double windSpeed = h.getWindSpeed10m().get(idx);
                            if (windDir != null && windSpeed != null) {
                                double[] upwind = openMeteoService.computeUpwindPoint(
                                        lat, lon, windDir, windSpeed, now, eventTime);
                                if (upwind != null) {
                                    allPoints.add(upwind);
                                }
                            }
                        }
                    }
                }
            }
        }

        LOG.info("Forecast batch: cloud point pre-fetch — {} raw points from {} tasks",
                allPoints.size(), candidates.size());
        return openMeteoService.prefetchCloudBatch(allPoints, null);
    }

    /**
     * Classifies every unique grid cell touched by the candidate set, publishes the
     * resulting snapshot via {@link StabilitySnapshotProvider#update}, and returns the
     * classification map for reuse during the per-task triage loop.
     *
     * <p>This is the canonical producer of {@code stability_snapshot} rows for the
     * overnight scheduled flow — previously written by
     * {@code ForecastCommandExecutor.applyStabilityFilter} but stranded when that
     * path's {@code @Scheduled} trigger was commented out during the v2.12
     * consolidation. Without this write the reader at
     * {@link #buildStabilityLookup()} sees nothing in memory or in the database and
     * defaults every region to UNSETTLED, collapsing the stability gate.
     *
     * <p>Cells whose locations lack a grid assignment are skipped (the existing
     * triage-loop helper handles those by returning a 1-day window). If no cells
     * survive classification, no snapshot is published — the previously persisted
     * snapshot remains authoritative.
     *
     * <p>The persist itself is best-effort: see the failure semantics in
     * {@link StabilitySnapshotProvider}.
     *
     * @param candidates        surviving briefing candidates
     * @param prefetchedWeather coord-key → prefetched forecast/air-quality result
     * @param ephemeral         when {@code true}, the classification is computed and
     *                          returned but the snapshot is NOT published — used by
     *                          the intraday refresh so its in-memory cost-gate does
     *                          not overwrite the morning's authoritative snapshot
     * @return classification keyed by grid-cell key (empty if no cells classified)
     */
    private Map<String, GridCellStabilityResult> classifyGridCellsAndPublishSnapshot(
            List<ForecastCandidate> candidates,
            Map<String, WeatherExtractionResult> prefetchedWeather,
            boolean ephemeral) {
        Map<String, GridCellStabilityResult> stabilityByCell = new LinkedHashMap<>();
        Map<String, Set<String>> locationsByCell = new LinkedHashMap<>();

        for (ForecastCandidate candidate : candidates) {
            LocationEntity loc = candidate.location();
            if (!loc.hasGridCell()) {
                continue;
            }
            String key = loc.gridCellKey();
            stabilityByCell.computeIfAbsent(key, k -> {
                String coordKey = OpenMeteoService.coordKey(loc.getLat(), loc.getLon());
                WeatherExtractionResult weather = prefetchedWeather.get(coordKey);
                OpenMeteoForecastResponse resp =
                        weather != null ? weather.forecastResponse() : null;
                return stabilityClassifier.classify(
                        key, loc.getGridLat(), loc.getGridLng(),
                        resp != null ? resp.getHourly() : null);
            });
            locationsByCell
                    .computeIfAbsent(key, k -> new LinkedHashSet<>())
                    .add(loc.getName());
        }

        if (stabilityByCell.isEmpty()) {
            LOG.warn("[STABILITY] No grid cells classified in this batch run "
                    + "— snapshot not written");
            return stabilityByCell;
        }

        Map<ForecastStability, Long> countsByStability = stabilityByCell.values().stream()
                .collect(Collectors.groupingBy(
                        GridCellStabilityResult::stability, Collectors.counting()));

        List<StabilitySummaryResponse.GridCellDetail> cellDetails =
                stabilityByCell.values().stream()
                        .map(r -> new StabilitySummaryResponse.GridCellDetail(
                                r.gridCellKey(), r.gridLat(), r.gridLng(),
                                r.stability(), r.reason(), r.evaluationWindowDays(),
                                List.copyOf(locationsByCell.getOrDefault(
                                        r.gridCellKey(), Set.of()))))
                        .sorted(Comparator.comparing(
                                StabilitySummaryResponse.GridCellDetail::gridCellKey))
                        .toList();

        StabilitySummaryResponse summary = new StabilitySummaryResponse(
                Instant.now(), stabilityByCell.size(), countsByStability, cellDetails);

        if (ephemeral) {
            LOG.info("[STABILITY] Ephemeral re-classification of {} grid cells "
                    + "(counts: {}) — snapshot NOT published (morning snapshot preserved)",
                    stabilityByCell.size(), countsByStability);
        } else {
            LOG.info("[STABILITY] Built snapshot for {} grid cells (counts: {})",
                    stabilityByCell.size(), countsByStability);
            stabilitySnapshotProvider.update(summary);
        }

        return stabilityByCell;
    }

    private LocationEntity findLocation(String name) {
        return locationService.findAllEnabled().stream()
                .filter(loc -> loc.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private void logStabilityBreakdown(Map<ForecastStability, int[]> eligible,
            Map<ForecastStability, int[]> cached) {
        StringBuilder sb = new StringBuilder("[BATCH DIAG] Candidate breakdown by stability:");
        for (ForecastStability level : ForecastStability.values()) {
            int elig = eligible.get(level)[0];
            int cach = cached.get(level)[0];
            if (elig == 0) {
                continue;
            }
            int refreshed = elig - cach;
            double pct = elig > 0 ? (refreshed * 100.0 / elig) : 0;
            Duration threshold = freshnessResolver.maxAgeFor(level);
            sb.append(String.format(" %s: %d of %d (%.1f%% refreshed, threshold %dh) |",
                    level, refreshed, elig, pct, threshold.toHours()));
        }
        if (sb.charAt(sb.length() - 1) == '|') {
            sb.setLength(sb.length() - 1);
        }
        LOG.warn("{}", sb);
    }

    private Map<String, ForecastStability> buildStabilityLookup() {
        StabilitySummaryResponse snapshot = stabilitySnapshotProvider.getLatestStabilitySummary();
        if (snapshot == null || snapshot.cells() == null) {
            LOG.warn("[BATCH DIAG] Stability snapshot unavailable — no snapshot in memory or DB, "
                    + "all regions treated as UNSETTLED ({}h threshold)",
                    freshnessResolver.maxAgeFor(ForecastStability.UNSETTLED).toHours());
            return Map.of();
        }
        long ageHours = java.time.temporal.ChronoUnit.HOURS.between(
                snapshot.generatedAt(), java.time.Instant.now());
        String source = ageHours > 12 ? "DB (recovered after restart)" : "in-memory";
        LOG.info("[BATCH DIAG] Stability snapshot loaded from {}: age={}h, {} grid cells",
                source, ageHours, snapshot.cells().size());
        Map<String, ForecastStability> lookup = new HashMap<>();
        for (StabilitySummaryResponse.GridCellDetail cell : snapshot.cells()) {
            for (String locName : cell.locationNames()) {
                lookup.put(locName, cell.stability());
            }
        }
        return lookup;
    }

    private ForecastStability mostVolatileStability(BriefingRegion region,
            Map<String, ForecastStability> stabilityByLocation) {
        if (region.slots() == null || region.slots().isEmpty()
                || stabilityByLocation.isEmpty()) {
            return ForecastStability.UNSETTLED;
        }
        ForecastStability most = ForecastStability.SETTLED;
        for (BriefingSlot slot : region.slots()) {
            ForecastStability slotStability = stabilityByLocation.getOrDefault(
                    slot.locationName(), ForecastStability.UNSETTLED);
            if (slotStability == ForecastStability.UNSETTLED) {
                return ForecastStability.UNSETTLED;
            }
            if (slotStability == ForecastStability.TRANSITIONAL) {
                most = ForecastStability.TRANSITIONAL;
            }
        }
        return most;
    }

    /**
     * Lightweight (location, date, targetType) triple emitted by the first
     * briefing pass before weather pre-fetch and triage are applied.
     */
    private record ForecastCandidate(LocationEntity location, LocalDate date,
            TargetType targetType) {
    }

    /**
     * Per-cycle counters that cross-tab included and excluded candidate counts
     * by {@code (daysAhead, stability)}. Used to emit the single-line
     * {@code [BATCH ELIG]} INFO summary that operators rely on to confirm Gate 4
     * is honouring its policy table after each scheduled run.
     */
    private static final class EligibilityAggregator {

        private final Map<Integer, EnumMap<ForecastStability, Integer>> included =
                new LinkedHashMap<>();
        private final Map<Integer, EnumMap<ForecastStability, Integer>> excluded =
                new LinkedHashMap<>();

        void recordIncluded(int daysAhead, ForecastStability stability) {
            bump(included, daysAhead, stability);
        }

        void recordExcluded(int daysAhead, ForecastStability stability) {
            bump(excluded, daysAhead, stability);
        }

        private static void bump(
                Map<Integer, EnumMap<ForecastStability, Integer>> sink,
                int daysAhead, ForecastStability stability) {
            sink.computeIfAbsent(daysAhead, k -> new EnumMap<>(ForecastStability.class))
                    .merge(stability, 1, Integer::sum);
        }

        /**
         * Formats a single readable line of the form:
         * {@code included T+0=5(S:3,T:1,U:1) T+2=18(S:12,T:6) | excluded T+2=1(U:1) T+3=4(T:3,U:1)}.
         */
        String formatSummary() {
            return "included " + formatBlock(included) + " | excluded " + formatBlock(excluded);
        }

        private static String formatBlock(
                Map<Integer, EnumMap<ForecastStability, Integer>> block) {
            if (block.isEmpty()) {
                return "none";
            }
            StringBuilder sb = new StringBuilder();
            block.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        if (sb.length() > 0) {
                            sb.append(' ');
                        }
                        int total = entry.getValue().values().stream()
                                .mapToInt(Integer::intValue).sum();
                        sb.append("T+").append(entry.getKey()).append('=').append(total);
                        sb.append('(');
                        boolean first = true;
                        for (Map.Entry<ForecastStability, Integer> e : entry.getValue().entrySet()) {
                            if (!first) {
                                sb.append(',');
                            }
                            sb.append(initial(e.getKey())).append(':').append(e.getValue());
                            first = false;
                        }
                        sb.append(')');
                    });
            return sb.toString();
        }

        private static char initial(ForecastStability stability) {
            return switch (stability) {
                case SETTLED      -> 'S';
                case TRANSITIONAL -> 'T';
                case UNSETTLED    -> 'U';
            };
        }
    }
}
