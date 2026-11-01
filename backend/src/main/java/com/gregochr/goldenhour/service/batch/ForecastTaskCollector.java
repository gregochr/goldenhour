package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.CandidateDisposition;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.InversionProfile;
import com.gregochr.goldenhour.model.PrefetchCall;
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
import com.gregochr.goldenhour.service.TideExtremeSnapshot;
import com.gregochr.goldenhour.service.TravelDayService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import com.gregochr.goldenhour.util.ForecastHorizon;
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
    private final SeasonalWindow bluebellSeason;

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
     * @param travelDayService          gates out candidates whose target date is a travel day
     * @param minPrefetchSuccessRatio   minimum prefetch ratio to proceed (scheduled path)
     * @param forceEvalCap              max force-evaluated headline candidates per cycle
     * @param clock                     UTC clock supplying "now" and (via London) "today"
     * @param bluebellSeason               the configured bluebell season window, tested
     *                                     directly so the woodland/bluebell fork does not
     *                                     hang off an augmentor side effect
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
            TravelDayService travelDayService,
            @Value("${photocast.batch.min-prefetch-success-ratio:0.5}")
            double minPrefetchSuccessRatio,
            @Value("${photocast.batch.force-eval-cap:6}")
            int forceEvalCap,
            java.time.Clock clock,
            SeasonalWindow bluebellSeason) {
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
        this.travelDayService = travelDayService;
        this.minPrefetchSuccessRatio = minPrefetchSuccessRatio;
        this.forceEvalCap = forceEvalCap;
        this.clock = clock;
        this.bluebellSeason = bluebellSeason;

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
     * <p>Cycle-aware variant: same triage and bucketing as the nightly path, but
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
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.copyOf(dispositions));
        }

        LOG.info("Forecast batch: {} candidate task(s) — bulk pre-fetching weather",
                candidates.size());

        // The pre-fetch's Open-Meteo calls ride out on every return below as a report, because the
        // job run they belong on does not exist yet. Accepted losses: an exception after this point
        // (stability classification, triage) propagates and the report goes with it.
        List<PrefetchCall> prefetchCalls = new ArrayList<>();
        int uniqueLocationCount = batchWeatherPrefetcher.countUniqueLocations(candidates);
        Map<String, WeatherExtractionResult> prefetchedWeather =
                batchWeatherPrefetcher.prefetchBatchWeather(candidates, prefetchCalls::add);
        double successRatio = uniqueLocationCount > 0
                ? (double) prefetchedWeather.size() / uniqueLocationCount : 0.0;
        if (prefetchedWeather.isEmpty()) {
            LOG.error("Forecast batch: weather pre-fetch returned 0/{} locations "
                    + "— aborting (likely Open-Meteo outage)", uniqueLocationCount);
            return new ScheduledBatchTasks(
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.copyOf(dispositions),
                    new WeatherPrefetchReport(prefetchCalls, uniqueLocationCount, 0,
                            "0/" + uniqueLocationCount + " locations returned"));
        }
        if (successRatio < minPrefetchSuccessRatio) {
            LOG.error("Forecast batch: weather pre-fetch too degraded — {}/{} locations "
                            + "(ratio {}, threshold {}) — aborting (likely Open-Meteo outage)",
                    prefetchedWeather.size(), uniqueLocationCount,
                    String.format("%.2f", successRatio),
                    String.format("%.2f", minPrefetchSuccessRatio));
            return new ScheduledBatchTasks(
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.copyOf(dispositions),
                    new WeatherPrefetchReport(prefetchCalls, uniqueLocationCount,
                            prefetchedWeather.size(),
                            prefetchedWeather.size() + "/" + uniqueLocationCount
                                    + " locations returned, ratio "
                                    + String.format("%.2f", successRatio) + " below threshold "
                                    + String.format("%.2f", minPrefetchSuccessRatio)));
        }
        if (prefetchedWeather.size() < uniqueLocationCount) {
            LOG.warn("Forecast batch: weather pre-fetch partial — {}/{} locations fetched, "
                            + "continuing with available data",
                    prefetchedWeather.size(), uniqueLocationCount);
        }

        CloudPointCache cloudCache;
        try {
            cloudCache = batchWeatherPrefetcher.prefetchBatchCloudPoints(
                    candidates, prefetchedWeather, prefetchCalls::add);
        } catch (Exception e) {
            LOG.warn("Forecast batch: cloud pre-fetch failed — continuing without cloud cache: {}",
                    e.getMessage());
            cloudCache = null;
        }

        Map<Long, InversionProfile> inversionProfiles = prefetchInversionProfiles(candidates);

        Map<String, GridCellStabilityResult> stabilityByCell =
                gridCellStabilityService.classifyGridCellsAndPublishSnapshot(
                        candidates, prefetchedWeather, ephemeral);

        List<EvaluationTask.Forecast> nearInland = new ArrayList<>();
        List<EvaluationTask.Forecast> nearCoastal = new ArrayList<>();
        List<EvaluationTask.Forecast> farInland = new ArrayList<>();
        List<EvaluationTask.Forecast> farCoastal = new ArrayList<>();
        List<EvaluationTask.Forecast> bluebell = new ArrayList<>();
        List<EvaluationTask.Forecast> woodland = new ArrayList<>();

        int skippedTriage = 0;
        int skippedStability = 0;
        int skippedError = 0;
        int includedNear = 0;
        int includedFar = 0;
        int includedForced = 0;
        int includedBluebell = 0;
        int includedWoodland = 0;
        EligibilityAggregator agg = new EligibilityAggregator();

        // Best-bet headline contenders that would otherwise be stability-gated.
        // Capped so the extra spend stays tiny; forced evals flow through the
        // same far-term bucket as everything else (one path, not a fork).
        Set<String> forceEvalKeys = forceEvalHeadlineSelector.selectForceEvalKeys(briefing);
        int forcedCount = 0;

        LOG.warn("[BATCH DIAG] Starting triage loop — {} candidate tasks "
                        + "({} force-eval headline candidates, cap {})",
                candidates.size(), forceEvalKeys.size(), forceEvalCap);

        TideExtremeSnapshot tideSnapshot = loadTideSnapshot(candidates);

        for (ForecastCandidate candidate : candidates) {
            // One horizon per candidate, used by the eligibility policy and by every
            // disposition row it produces. It was two values — this one and
            // preEval.daysAhead() — derived on Europe/London and UTC respectively, and which
            // of them a row carried depended on which branch emitted it: the triage and error
            // rows took the London one, the stability-skip and include rows the UTC one. For
            // the BST hour before midnight UTC that put two horizons a day apart in the same
            // table for the same run and the same date. ForecastHorizon now makes both London;
            // sharing the variable makes them one value rather than two that agree.
            int daysAhead = ForecastHorizon.daysAhead(candidate.date(), clock);
            try {
                ForecastPreEvalResult preEval = triageCandidate(candidate, nearTermModel,
                        prefetchedWeather, cloudCache, tideSnapshot, inversionProfiles);
                // Record conditions for every place this cycle fetched weather for — no longer a
                // call made here. `ForecastService.fetchWeatherAndTriage` above now makes this
                // write itself, immediately after assembling the atmospheric data and before its
                // own triage check, so it covers this call site and every other caller of that
                // method (collectRegionFilteredBatches below, ForceSubmitBatchService,
                // BatchRetryService) with the one
                // seam — see that method's own javadoc for the full reasoning (owner decision
                // 2026-09-29 "record conditions for every place", Phase 1, and the P1 fix that
                // moved the write off this collector's own three call sites and into the seam).

                // Season is tested DIRECTLY against the configured window. It used to be
                // inferred from a non-null bluebell condition score, which is a three-way
                // conjunction the augmentor evaluates (in season AND typed BLUEBELL AND exposure
                // set). A bluebell site whose exposure column was null therefore read as
                // out-of-season, and once the woodland lane exists that routes it to the WOODLAND
                // prompt in the middle of May — the wrong prompt, silently. Production has no
                // such rows today; one admin edit would create one.
                // The bluebell lane is gated on the condition score being PRESENT, not on the
                // season alone. That is deliberate and load-bearing: BluebellPromptBuilder throws
                // without one, and the augmentor populates it under a three-way conjunction (in
                // season AND typed BLUEBELL AND exposure set). Routing on anything broader than
                // what the augmentor actually populated would submit a task the builder cannot
                // build.
                boolean bluebellScored = preEval.atmosphericData() != null
                        && preEval.atmosphericData().bluebellConditionScore() != null;
                boolean canopySite = candidate.location().isWoodlandOnly();

                // ...but the season is ALSO tested directly, purely so a misconfiguration is
                // visible. Before the woodland lane existed, a BLUEBELL site with a null exposure
                // simply got no bluebell evaluation. Now it silently falls through to the woodland
                // prompt in the middle of May instead. Routing it there is the right fail-soft — a
                // wood is still a wood, and the alternative is nothing — but it must not be quiet.
                // Production has no such rows today; one admin edit would create one.
                if (bluebellSeason.isActive(candidate.date())
                        && candidate.location().getLocationType() != null
                        && candidate.location().getLocationType().contains(LocationType.BLUEBELL)
                        && candidate.location().getBluebellExposure() == null) {
                    LOG.warn("[BATCH DIAG] {} is a BLUEBELL site in season with no "
                                    + "bluebell_exposure set — it will take the woodland lane "
                                    + "instead. Set its exposure to restore bluebell scoring.",
                            candidate.location().getName());
                }

                boolean bluebellWoodInSeason = bluebellScored
                        && candidate.location().getBluebellExposure() == BluebellExposure.WOODLAND;
                boolean woodlandTask = canopySite && !bluebellWoodInSeason;

                // A canopy site never belongs in the sky lane, in or out of season. The sky
                // triage asks sky questions — is there too much cloud, is visibility poor — and
                // for a wood the honest answers are inverted, so letting it run produces exactly
                // the wrong selection: a wood is triaged OUT on the misty overcast morning it
                // wants, and passed THROUGH on the clear day it does not, where it would then be
                // rated for fiery-sky potential it structurally cannot have.
                //
                // A canopy site now HAS a prompt of its own, so it is routed rather than
                // dropped: the woodland lane out of bluebell season, the bluebell lane in it.
                // What has not changed is that it never enters the sky lane.

                // Woodland bluebell sites in season are evaluated by the bluebell prompt ALONE,
                // so the colour triage verdict does not gate them (bright overcast — ideal for a
                // bluebell carpet — would otherwise stand them down as a poor sky). Every other
                // candidate still respects the triage verdict.
                // The woodland lane needs the same exemption for the same reason, and it is not
                // optional: sky triage stands a slot down at >80% low cloud and <5 km visibility,
                // which are precisely the overcast and the mist a wood wants. Without this the
                // feature is born inverted — every good woodland morning triaged out.
                if (preEval.triaged() && !bluebellWoodInSeason && !woodlandTask) {
                    LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | reason=TRIAGED ({})",
                            candidate.location().getName(), candidate.date(),
                            candidate.targetType(), preEval.triageReason());
                    dispositions.add(new CandidateDisposition(
                            candidate.location().getId(), candidate.location().getName(),
                            candidate.date(), candidate.targetType(), daysAhead,
                            DispositionCategory.SKIPPED_TRIAGED, preEval.triageReason()));
                    skippedTriage++;
                    continue;
                }
                ForecastStability stability = gridCellStabilityService.stabilityFor(
                        candidate.location(), preEval, stabilityByCell);
                EligibilityDecision decision = eligibilityPolicy.resolve(
                        daysAhead, candidate.targetType(), stability,
                        nearTermModel, farTermModel);
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

                // Evening-refresh deferral. Placed AFTER triage and eligibility on purpose: a slot
                // that triage stands down must still record SKIPPED_TRIAGED (which retracts its
                // stale stars), and the weather fetch, slot_atmosphere write and stability
                // classification above all ran as usual. Only a slot that would otherwise be sent
                // to Claude, in the sky lane, is left to tonight's evening refresh, and only when
                // the location already holds a live rating for it.
                if (!woodlandTask && !bluebellWoodInSeason
                        && candidateStrategy.defersRatedSlot(candidate.date(), candidate.targetType())
                        && hasStoredRating(candidate)) {
                    LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | reason=NO_REFRESH_NEEDED ({})",
                            candidate.location().getName(), candidate.date(),
                            candidate.targetType(),
                            IntradayCandidateCollectionStrategy.EVENING_DEFERRAL_REASON);
                    dispositions.add(new CandidateDisposition(
                            candidate.location().getId(), candidate.location().getName(),
                            candidate.date(), candidate.targetType(), daysAhead,
                            DispositionCategory.SKIPPED_NO_REFRESH_NEEDED,
                            IntradayCandidateCollectionStrategy.EVENING_DEFERRAL_REASON));
                    agg.recordExcluded(daysAhead, stability);
                    skippedStability++;
                    continue;
                }

                boolean isNearTerm = daysAhead <= NEAR_TERM_MAX_DAYS;

                if (woodlandTask) {
                    // Canopy site out of bluebell season: ONE woodland task, no sky task. Its own
                    // homogeneous bucket, so the woodland system prompt caches across the batch.
                    woodland.add(woodlandTaskFor(candidate, decision, preEval, forced));
                    includedWoodland++;
                    agg.recordIncluded(daysAhead, stability);
                    if (forced) {
                        includedForced++;
                    }
                    dispositions.add(includeDisposition(candidate, daysAhead, forced));
                    LOG.warn("[BATCH DIAG] INCLUDE {} | date={} event={} | tier=woodland "
                                    + "type=canopy{}",
                            candidate.location().getName(), candidate.date(),
                            candidate.targetType(), forced ? " (forced)" : "");
                    continue;
                }

                if (bluebellWoodInSeason) {
                    // Bluebell-only: ONE bluebell task, no sky task, no colour bucket (the OQ3
                    // exposure rule makes the bluebell score the rating for woodland).
                    bluebell.add(bluebellTaskFor(candidate, decision, preEval, forced));
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

                // R4: persist the PENDING row before submission — it is the durable carrier of
                // this preEval's snapshot, and its id rides the batch custom_id (R3) so the
                // result side can score it in place (R5).
                Long evalRowId = forecastService.persistPendingEvaluation(preEval);
                // `forced` rides the canonical nine-arg constructor so the fact reaches the batch
                // custom id (CustomIdFactory) and survives to result time — see
                // EvaluationTask.Forecast#forced's own javadoc for why a timestamp comparison at
                // serve time could not be trusted instead.
                EvaluationTask.Forecast eval = new EvaluationTask.Forecast(
                        candidate.location(), candidate.date(), candidate.targetType(),
                        decision.model(), preEval.atmosphericData(),
                        EvaluationTask.Forecast.PromptKind.SKY, evalRowId, forced);
                boolean isCoastal = preEval.atmosphericData().tide() != null;
                String locationType = isCoastal ? "coastal" : "inland";

                // Keyed on DATA, not on metadata — deliberately, and BatchRequestFactory
                // .selectBuilder uses the same predicate, so the bucket and the prompt always
                // agree and the batch stays homogeneous (which is what lets its system prompt
                // cache). But that also means a genuinely coastal location whose tide_extreme rows
                // are missing is evaluated by the inland prompt, with no tide and no surge, and
                // looks identical to an inland one in every log line. Say so once per slot, or the
                // degradation is invisible: it is what a location inserted by migration (V138), a
                // failed eager fetch, or an expired tide window all look like.
                if (!isCoastal && locationService.isCoastal(candidate.location())) {
                    LOG.warn("[BATCH DIAG] TIDE-LESS COASTAL {} | date={} event={} — typed coastal "
                                    + "but carries no tide data, so it is bucketed inland and gets "
                                    + "the inland prompt. Run the tide_refresh job.",
                            candidate.location().getName(), candidate.date(),
                            candidate.targetType());
                }

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
                boolean openFellPaired = bluebellScored
                        && candidate.location().getBluebellExposure()
                                == BluebellExposure.OPEN_FELL;
                if (openFellPaired) {
                    bluebell.add(bluebellTaskFor(candidate, decision, preEval, forced));
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
                        candidate.date(), candidate.targetType(), daysAhead,
                        DispositionCategory.SKIPPED_ERROR, e.getMessage()));
                skippedError++;
            }
        }

        int totalIncluded = includedNear + includedFar + includedBluebell + includedWoodland;
        // Every skip is named, so included + skipped reconciles against the candidate count. A
        // category missing from this line makes the cycle summary silently stop adding up.
        LOG.warn("[BATCH DIAG] Triage complete — {} included (near={}, far={}, bluebell={}, "
                        + "woodland={}, forced={}), {} skipped (triage={}, stability={}, "
                        + "error={})",
                totalIncluded, includedNear, includedFar, includedBluebell, includedWoodland,
                includedForced, skippedTriage + skippedStability + skippedError,
                skippedTriage, skippedStability, skippedError);
        LOG.info("[BATCH ELIG] {}", agg.formatSummary());

        if (totalIncluded == 0) {
            LOG.info("Forecast batch: no evaluable locations after triage, skipping submission");
            return new ScheduledBatchTasks(
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.copyOf(dispositions),
                    new WeatherPrefetchReport(prefetchCalls, uniqueLocationCount,
                            prefetchedWeather.size(), null));
        }

        return new ScheduledBatchTasks(nearInland, nearCoastal, farInland, farCoastal,
                bluebell, woodland, List.copyOf(dispositions),
                new WeatherPrefetchReport(prefetchCalls, uniqueLocationCount,
                        prefetchedWeather.size(), null));
    }

    /**
     * Whether the candidate's region cache holds a live rating (not a stand-down, failure or
     * retraction) for its location and slot.
     */
    private boolean hasStoredRating(ForecastCandidate candidate) {
        if (candidate.location().getRegion() == null) {
            return false;
        }
        BriefingEvaluationResult stored = briefingEvaluationService
                .getCachedScores(candidate.location().getRegion().getName(), candidate.date(),
                        candidate.targetType())
                .get(candidate.location().getName());
        return stored != null && stored.rating() != null && !stored.retracted();
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
     *
     * @param forced whether the eligibility decision for this exact candidate was a {@code
     *               ForceEvalHeadlineSelector} rescue — the routing to bluebell (WOODLAND exposure,
     *               or the OPEN_FELL pairing) happens AFTER that decision, so a force-evaluated
     *               candidate can land here exactly as it can land in the sky lane, and the fact
     *               must travel with this task the same way (no {@code evalRowId}: R8 scopes
     *               pending rows to the sky lane alone, but the batch custom id still carries the
     *               {@code -f} marker via {@link CustomIdFactory#forBluebell(Long, LocalDate,
     *               TargetType, boolean)})
     */
    private static EvaluationTask.Forecast bluebellTaskFor(ForecastCandidate candidate,
            EligibilityDecision decision, ForecastPreEvalResult preEval, boolean forced) {
        return new EvaluationTask.Forecast(
                candidate.location(), candidate.date(), candidate.targetType(),
                decision.model(), preEval.atmosphericData(),
                EvaluationTask.Forecast.PromptKind.BLUEBELL, null, forced);
    }

    /**
     * Builds a {@link EvaluationTask.Forecast.PromptKind#WOODLAND} task for a canopy candidate out
     * of bluebell season, reusing the same atmospheric data and eligibility model as the sky path.
     * Needs no augmentor precondition — the woodland builder derives its deterministic hint from
     * the atmospheric data it is handed.
     *
     * @param forced whether the eligibility decision for this exact candidate was a {@code
     *               ForceEvalHeadlineSelector} rescue — see {@link #bluebellTaskFor}'s own javadoc
     *               for why the woodland lane needs the identical treatment
     */
    private static EvaluationTask.Forecast woodlandTaskFor(ForecastCandidate candidate,
            EligibilityDecision decision, ForecastPreEvalResult preEval, boolean forced) {
        return new EvaluationTask.Forecast(
                candidate.location(), candidate.date(), candidate.targetType(),
                decision.model(), preEval.atmosphericData(),
                EvaluationTask.Forecast.PromptKind.WOODLAND, null, forced);
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
     * Collects region-filtered forecast tasks for an admin batch.
     *
     * <p>Uses {@code BATCH_NEAR_TERM} model for every task and produces a
     * simpler inland/coastal bucketing (no near/far split). Returns an
     * all-empty value when the briefing has no candidates after filtering or
     * weather pre-fetch yields zero results. Unlike the scheduled path, the
     * region-filtered admin path tolerates partial-prefetch degradation
     * (no ratio threshold) — mirroring legacy behaviour.
     *
     * <p>⚠️ <b>A canopy candidate reaches {@link ForecastService#fetchWeatherAndTriage} exactly
     * like every other candidate here, and is excluded only AFTER that call, exactly matching
     * {@link #collectScheduledBatches} — a Codex review of PR #947 (round 2, against commit
     * 1f637d56) found this loop used to test {@code isWoodlandOnly()} BEFORE the call, so a
     * canopy candidate never reached the seam that records its atmospheric readings at all, even
     * though the scheduled loop (which decides its own woodland lane strictly after the
     * identical call) did. The exclusion below is unconditional — it does not consult
     * {@code preEval.triaged()} first — because the scheduled loop's own woodland lane ignores
     * the sky triage verdict entirely for the same kind of site (a wood wants exactly the
     * overcast, misty conditions sky triage stands a slot down for); this path has no woodland
     * bucket to route a canopy candidate to (unlike the scheduled loop's own {@code woodland}
     * list), so the two paths agree by both excluding it from the sky lane, one into its own
     * bucket and the other into none.
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

        // As on the scheduled path, the pre-fetch's calls ride out on the result as a report. A
        // partial pre-fetch followed by everything being triaged out returns no handle, and its
        // rows are not written (accepted loss).
        List<PrefetchCall> prefetchCalls = new ArrayList<>();
        int uniqueLocationCount = batchWeatherPrefetcher.countUniqueLocations(candidates);
        Map<String, WeatherExtractionResult> prefetchedWeather =
                batchWeatherPrefetcher.prefetchBatchWeather(candidates, prefetchCalls::add);
        if (prefetchedWeather.isEmpty()) {
            LOG.error("[BATCH] Weather pre-fetch returned zero results — aborting");
            return RegionFilteredBatchTasks.aborted(new WeatherPrefetchReport(prefetchCalls,
                    uniqueLocationCount, 0, "0/" + uniqueLocationCount + " locations returned"));
        }

        CloudPointCache cloudCache;
        try {
            cloudCache = batchWeatherPrefetcher.prefetchBatchCloudPoints(
                    candidates, prefetchedWeather, prefetchCalls::add);
        } catch (Exception e) {
            LOG.warn("[BATCH] Cloud pre-fetch failed — continuing without: {}", e.getMessage());
            cloudCache = null;
        }

        Map<Long, InversionProfile> inversionProfiles = prefetchInversionProfiles(candidates);

        List<EvaluationTask.Forecast> inland = new ArrayList<>();
        List<EvaluationTask.Forecast> coastal = new ArrayList<>();
        Map<String, GridCellStabilityResult> stabilityByCell = new HashMap<>();

        TideExtremeSnapshot tideSnapshot = loadTideSnapshot(candidates);

        for (ForecastCandidate candidate : candidates) {
            try {
                // fetchWeatherAndTriage records this candidate's atmospheric readings itself
                // (the one seam every caller shares — see its own javadoc), including a slot
                // this admin path is about to triage, Gate-4-skip, or route away below. A
                // canopy candidate must reach this call too, exactly like the scheduled loop
                // (collectScheduledBatches above) — excluding it BEFORE this call, as this
                // method used to, fetches its weather (the prefetch above already covers it)
                // and then records nothing for it, while the scheduled loop, which decides the
                // woodland lane strictly AFTER this same call, does.
                ForecastPreEvalResult preEval = triageCandidate(candidate, model,
                        prefetchedWeather, cloudCache, tideSnapshot, inversionProfiles);

                // Canopy sites never belong in the sky lane, and this path has no woodland
                // bucket to route them to (unlike the scheduled loop's own `woodland` list) —
                // so a canopy candidate is now recorded (above) and then excluded here, the
                // same outcome as before, just after the fetch instead of before it. This
                // exclusion is UNCONDITIONAL — it must not consult `preEval.triaged()` first.
                // The scheduled loop's own woodland lane ignores the sky triage verdict
                // entirely (`woodlandTask` is one of the exemptions on its `preEval.triaged()`
                // check), because a wood wants exactly the overcast, misty conditions sky
                // triage stands a slot down for; treating a canopy site's own triage verdict
                // as meaningful here — e.g. by testing `preEval.triaged()` before this check —
                // would silently make this path agree with the sky triage the scheduled loop
                // deliberately disregards for the same kind of site.
                if (candidate.location().isWoodlandOnly()) {
                    continue;
                }
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
                        daysAhead, candidate.targetType(), stability, model, model);
                if (!decision.eligible()) {
                    continue;
                }
                // R4: persist the PENDING row before submission (see the sibling scheduled-flow
                // call site above for why).
                Long evalRowId = forecastService.persistPendingEvaluation(preEval);
                EvaluationTask.Forecast eval = new EvaluationTask.Forecast(
                        candidate.location(), candidate.date(), candidate.targetType(),
                        decision.model(), preEval.atmosphericData(),
                        EvaluationTask.Forecast.PromptKind.SKY, evalRowId);
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

        return new RegionFilteredBatchTasks(inland, coastal,
                new WeatherPrefetchReport(prefetchCalls, uniqueLocationCount,
                        prefetchedWeather.size(), null));
    }

    /**
     * Loads one tide snapshot for this collection's coastal candidates over their dates, so the
     * per-candidate tide derivation reads from memory instead of one query per candidate (and
     * more). A failed load returns {@code null}, which leaves every candidate on the live query.
     */
    private TideExtremeSnapshot loadTideSnapshot(List<ForecastCandidate> candidates) {
        try {
            return forecastService.tideSnapshotFor(
                    candidates.stream().map(ForecastCandidate::location).toList(),
                    candidates.stream().map(ForecastCandidate::date).distinct().toList());
        } catch (Exception e) {
            LOG.warn("Forecast batch: tide snapshot load failed — candidates use live tide reads: {}",
                    e.getMessage());
            return null;
        }
    }

    /**
     * Pre-fetches the v2 inversion valley profiles for the candidate set (inversion-v2-plan Phase 1b), after the
     * weather and cloud pre-fetches. Never throws and never returns null: a switched-off or failed
     * prefetch is an empty map, which the per-candidate call reads as "no profile" and does NOT replace with
     * an on-demand fetch per slot.
     */
    private Map<Long, InversionProfile> prefetchInversionProfiles(List<ForecastCandidate> candidates) {
        try {
            if (!forecastService.inversionV2Enabled()) {
                return Map.of();
            }
            return batchWeatherPrefetcher.prefetchInversionProfiles(candidates);
        } catch (Exception e) {
            LOG.warn("Forecast batch: inversion profile pre-fetch failed — continuing without v2 profiles: {}",
                    e.getMessage());
            return Map.of();
        }
    }

    /**
     * Fetches and triages one candidate, passing the collection's tide snapshot (null for live reads) and
     * the run's inversion profiles (a map, never null, so no slot fetches a profile of its own).
     */
    private ForecastPreEvalResult triageCandidate(ForecastCandidate candidate,
            EvaluationModel model, Map<String, WeatherExtractionResult> prefetchedWeather,
            CloudPointCache cloudCache, TideExtremeSnapshot tideSnapshot,
            Map<Long, InversionProfile> inversionProfiles) {
        return forecastService.fetchWeatherAndTriage(
                candidate.location(), candidate.date(), candidate.targetType(),
                candidate.location().getTideType(), model, null, prefetchedWeather, cloudCache,
                tideSnapshot, inversionProfiles);
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
