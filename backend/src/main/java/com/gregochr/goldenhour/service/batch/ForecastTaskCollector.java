package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.CandidateDisposition;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.GridCellStabilityResult;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.ForecastStabilityClassifier;
import com.gregochr.goldenhour.service.FreshnessResolver;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import com.gregochr.goldenhour.service.TravelDayService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import com.gregochr.goldenhour.service.evaluation.SurvivorAtmosphereWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p>Assembly concerns are delegated to four instance-scoped collaborators
 * constructed from this collector's own dependencies:
 * {@link BatchWeatherPrefetcher} (weather + cloud pre-fetch),
 * {@link ForceEvalHeadlineSelector} (capped force-eval key selection),
 * {@link BriefingCandidateCollector} (first briefing pass), and
 * {@link GridCellStabilityService} (grid-cell classification + snapshot publish).
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
    private final SurvivorAtmosphereWriter survivorAtmosphereWriter;
    private final TravelDayService travelDayService;

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

    private final java.time.Clock clock;

    private final BatchWeatherPrefetcher batchWeatherPrefetcher;
    private final ForceEvalHeadlineSelector forceEvalHeadlineSelector;
    private final BriefingCandidateCollector briefingCandidateCollector;
    private final GridCellStabilityService gridCellStabilityService;

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
     * @param survivorAtmosphereWriter  captures survivor atmospheric readings at submission time
     * @param travelDayService          gates out candidates whose target date is a travel day
     * @param minPrefetchSuccessRatio   minimum prefetch ratio to proceed (scheduled path)
     * @param forceEvalCap              max force-evaluated headline candidates per cycle
     * @param clock                     UTC clock supplying "now" and (via London) "today"
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
            SurvivorAtmosphereWriter survivorAtmosphereWriter,
            TravelDayService travelDayService,
            @Value("${photocast.batch.min-prefetch-success-ratio:0.5}")
            double minPrefetchSuccessRatio,
            @Value("${photocast.batch.force-eval-cap:6}")
            int forceEvalCap,
            java.time.Clock clock) {
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
        this.survivorAtmosphereWriter = survivorAtmosphereWriter;
        this.travelDayService = travelDayService;
        this.minPrefetchSuccessRatio = minPrefetchSuccessRatio;
        this.forceEvalCap = forceEvalCap;
        this.clock = clock;

        // Instance-scoped assembly seams, wired from this collector's own dependency
        // fields so they share the exact collaborator instances (important for the
        // interaction-based tests that verify on the injected mocks).
        this.batchWeatherPrefetcher =
                new BatchWeatherPrefetcher(openMeteoService, solarService, clock);
        this.forceEvalHeadlineSelector =
                new ForceEvalHeadlineSelector(forceEvalCap, clock);
        this.briefingCandidateCollector = new BriefingCandidateCollector(
                travelDayService, locationService, briefingEvaluationService,
                freshnessResolver, stabilitySnapshotProvider, clock);
        this.gridCellStabilityService = new GridCellStabilityService(
                stabilityClassifier, stabilitySnapshotProvider);
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

        int uniqueLocationCount = batchWeatherPrefetcher.countUniqueLocations(candidates);
        Map<String, WeatherExtractionResult> prefetchedWeather =
                batchWeatherPrefetcher.prefetchBatchWeather(candidates);
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
            cloudCache = batchWeatherPrefetcher.prefetchBatchCloudPoints(
                    candidates, prefetchedWeather);
        } catch (Exception e) {
            LOG.warn("Forecast batch: cloud pre-fetch failed — continuing without cloud cache: {}",
                    e.getMessage());
            cloudCache = null;
        }

        Map<String, GridCellStabilityResult> stabilityByCell =
                gridCellStabilityService.classifyGridCellsAndPublishSnapshot(
                        candidates, prefetchedWeather, ephemeral);

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
        Set<String> forceEvalKeys = forceEvalHeadlineSelector.selectForceEvalKeys(briefing);
        int forcedCount = 0;

        LOG.warn("[BATCH DIAG] Starting triage loop — {} candidate tasks "
                        + "({} force-eval headline candidates, cap {})",
                candidates.size(), forceEvalKeys.size(), forceEvalCap);

        for (ForecastCandidate candidate : candidates) {
            int candidateDaysAhead =
                    BriefingCandidateCollector.daysAheadFor(candidate.date(), clock);
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
                ForecastStability stability = gridCellStabilityService.stabilityFor(
                        candidate.location(), preEval, stabilityByCell);
                EligibilityDecision decision = eligibilityPolicy.resolve(
                        daysAhead, stability, nearTermModel, farTermModel);
                boolean forced = false;
                if (!decision.eligible()) {
                    String forceKey = ForceEvalHeadlineSelector.forceEvalKey(
                            candidate.location().getName(),
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

                // Survivor confirmed (past triage + gating): capture its atmospheric readings to
                // the survivor surface now, before the async batch boundary discards them. Covers
                // both the woodland-only and sky branches below. Isolated so a carrier write
                // failure never aborts batch collection.
                try {
                    survivorAtmosphereWriter.write(candidate.location(), candidate.date(),
                            candidate.targetType(), preEval.atmosphericData());
                } catch (Exception e) {
                    LOG.error("survivor_atmosphere write FAILED for {} {} {}; collection proceeds: {}",
                            candidate.location().getName(), candidate.date(),
                            candidate.targetType(), e.getMessage(), e);
                }

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
     * Thin delegator over {@link BriefingCandidateCollector#collectForecastCandidates}:
     * runs the pure first-pass producer and merges its dispositions into the
     * caller-supplied list, returning the surviving candidates. Retained on the
     * collector (with its legacy {@code dispositions}-append contract) so both
     * collect flows and the reflection-based cache-gate test call the same seam.
     */
    private List<ForecastCandidate> collectForecastCandidates(DailyBriefingResponse briefing,
            List<CandidateDisposition> dispositions,
            CandidateCollectionStrategy candidateStrategy) {
        BriefingCandidateCollector.Result result =
                briefingCandidateCollector.collectForecastCandidates(briefing, candidateStrategy);
        dispositions.addAll(result.dispositions());
        return result.candidates();
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
                batchWeatherPrefetcher.prefetchBatchWeather(candidates);
        if (prefetchedWeather.isEmpty()) {
            LOG.error("[BATCH] Weather pre-fetch returned zero results — aborting");
            return RegionFilteredBatchTasks.empty();
        }

        CloudPointCache cloudCache;
        try {
            cloudCache = batchWeatherPrefetcher.prefetchBatchCloudPoints(
                    candidates, prefetchedWeather);
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
                ForecastStability stability = gridCellStabilityService.stabilityFor(
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
