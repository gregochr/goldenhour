package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.SkyRatingEvalModelExclusionEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalResultEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalTrigger;
import com.gregochr.goldenhour.eval.MissDirection;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixture;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixtures;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.ScheduledModelView;
import com.gregochr.goldenhour.model.SkyRatingEvalTrendPoint;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.SkyRatingEvalModelExclusionRepository;
import com.gregochr.goldenhour.repository.SkyRatingEvalResultRepository;
import com.gregochr.goldenhour.repository.SkyRatingEvalRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The historian for the pass^k sky-rating eval: runs the same frozen fixtures the gated
 * {@code SkyRatingEvalTest} uses, but through the real production scorer
 * ({@link EvaluationService#evaluateWithDetails}) on a cadence, and persists every run so
 * calibration drift can be graphed over time.
 *
 * <p>The gated JUnit test is the pass/fail gate; this is its longitudinal counterpart. Both share
 * one fixture set ({@link SkyRatingEvalFixtures}); neither is the other's substitute.
 *
 * <p><b>Transaction shape.</b> A run makes {@code fixtures × N} real API calls over several
 * minutes, so the work is deliberately <em>not</em> wrapped in a single transaction — that would
 * pin a DB connection for the whole run. Each result is saved as it completes; the parent run row
 * is created up front (RUNNING) and finalised at the end (COMPLETED/FAILED).
 */
@Service
public class SkyRatingEvalService {

    private static final Logger LOG = LoggerFactory.getLogger(SkyRatingEvalService.class);

    /**
     * Batch-priced cost of one response for this run's model, as the success path prices it.
     *
     * @param run   the run (supplies the model)
     * @param usage the response's usage, or null when nothing was billed
     * @return cost in micro-dollars (0 when there is no usage)
     */
    long batchCostMicroDollars(SkyRatingEvalRunEntity run, TokenUsage usage) {
        return usage == null ? 0L : costCalculator.calculateCostMicroDollars(run.getModel(), usage, true);
    }

    /** Prefix of the {@code summary} on a failed-attempt child row. */
    static final String FAILED_SUMMARY_PREFIX = "FAILED: ";

    /** Repeats per fixture a quick check may ask for. */
    public static final Set<Integer> CHECK_RUNS_OPTIONS = Set.of(1, 3, 8);

    /** Column width of {@code sky_rating_eval_run.git_commit_message}. */
    static final int MAX_COMMIT_MESSAGE_LENGTH = 500;

    /** Default runs per fixture — matches the gated {@code SkyRatingEvalTest} pass^k depth. */
    public static final int DEFAULT_RUNS_PER_FIXTURE = 8;

    /** Scheduler job key — matches the seed row in V118 and the registered runnable. */
    public static final String JOB_KEY = "sky_rating_eval";

    private final EvaluationService evaluationService;
    private final CostCalculator costCalculator;
    private final GitInfoService gitInfoService;
    private final SkyRatingEvalRunRepository runRepository;
    private final SkyRatingEvalResultRepository resultRepository;
    private final SkyRatingEvalModelExclusionRepository exclusionRepository;

    /**
     * Constructs the service.
     *
     * @param evaluationService the production scorer (same path the forecast pipeline uses)
     * @param costCalculator    token → micro-dollar cost
     * @param gitInfoService    git commit metadata, for attributing drift to a prompt edit
     * @param runRepository     parent-run persistence
     * @param resultRepository  per-result persistence
     * @param exclusionRepository models an admin has switched off for the weekly run
     */
    public SkyRatingEvalService(EvaluationService evaluationService, CostCalculator costCalculator,
            GitInfoService gitInfoService, SkyRatingEvalRunRepository runRepository,
            SkyRatingEvalResultRepository resultRepository,
            SkyRatingEvalModelExclusionRepository exclusionRepository) {
        this.exclusionRepository = exclusionRepository;
        this.evaluationService = evaluationService;
        this.costCalculator = costCalculator;
        this.gitInfoService = gitInfoService;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
    }

    /**
     * Creates and persists a RUNNING run row, stamped with the current git commit, and returns it.
     *
     * <p>Separated from {@link #executeRun(Long)} so a controller can return the run id immediately
     * (202) and dispatch the multi-minute execution to a background thread.
     *
     * @param model           the Claude model to score with
     * @param trigger         what initiated the run
     * @param runsPerFixture  how many times to score each fixture
     * @return the persisted RUNNING run
     */
    public SkyRatingEvalRunEntity startRun(EvaluationModel model, SkyRatingEvalTrigger trigger,
            int runsPerFixture) {
        return startRun(model, trigger, runsPerFixture, null);
    }

    /**
     * Creates and persists a RUNNING run over a subset of the fixtures (a manual quick check), stamped
     * with the running build's commit and message.
     *
     * @param model           the Claude model to score with
     * @param trigger         what initiated the run
     * @param runsPerFixture  how many times to score each fixture
     * @param fixtureNames    the fixtures to score, or null for all of them
     * @return the persisted RUNNING run, {@code fixture_count} already set to the subset size
     * @throws IllegalArgumentException if {@code fixtureNames} is empty or names an unknown fixture
     */
    public SkyRatingEvalRunEntity startRun(EvaluationModel model, SkyRatingEvalTrigger trigger,
            int runsPerFixture, List<String> fixtureNames) {
        List<SkyRatingEvalFixture> chosen = fixtureNames == null
                ? SkyRatingEvalFixtures.ALL : SkyRatingEvalFixtures.select(fixtureNames);
        boolean everything = chosen.size() == SkyRatingEvalFixtures.ALL.size();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SkyRatingEvalRunEntity run = SkyRatingEvalRunEntity.builder()
                .runTimestamp(now)
                .startedAt(now)
                .model(model)
                .runsPerFixture(runsPerFixture)
                .triggerSource(trigger)
                .status(SkyRatingEvalStatus.RUNNING)
                .gitCommitHash(gitInfoService.getCommitHash())
                .gitCommitDate(gitInfoService.getCommitDate())
                .gitDirty(gitInfoService.isDirty())
                .gitBranch(gitInfoService.getBranch())
                .gitCommitMessage(truncate(gitInfoService.getCommitMessage(), MAX_COMMIT_MESSAGE_LENGTH))
                .fixtureNames(everything ? null : chosen.stream().map(SkyRatingEvalFixture::name)
                        .collect(Collectors.joining(SkyRatingEvalFixtures.NAME_SEPARATOR)))
                .fixtureCount(chosen.size())
                .build();
        return runRepository.save(run);
    }

    /**
     * Validates a quick-check request and starts one MANUAL run per model on the current build. The caller
     * dispatches {@link #executeRun(Long)} for each returned run (in parallel).
     *
     * @param models         the models to check (at least one, all selectable, no duplicates)
     * @param runsPerFixture repeats per fixture, one of {@link #CHECK_RUNS_OPTIONS}
     * @param fixtureNames   the fixtures to score, or null for all
     * @return the started runs, in model order
     * @throws IllegalArgumentException on any invalid input (mapped to 400); nothing is started in that case
     */
    public List<SkyRatingEvalRunEntity> startChecks(List<EvaluationModel> models, Integer runsPerFixture,
            List<String> fixtureNames) {
        if (models == null || models.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one model to check");
        }
        if (models.stream().anyMatch(m -> m == null || !m.isSelectable())) {
            throw new IllegalArgumentException("Every model must be one of " + EvaluationModel.selectable());
        }
        if (new HashSet<>(models).size() != models.size()) {
            throw new IllegalArgumentException("Each model can be checked once per request");
        }
        if (runsPerFixture == null || !CHECK_RUNS_OPTIONS.contains(runsPerFixture)) {
            throw new IllegalArgumentException("runsPerFixture must be one of " + CHECK_RUNS_OPTIONS);
        }
        if (fixtureNames != null) {
            SkyRatingEvalFixtures.select(fixtureNames);
        }
        return models.stream()
                .map(m -> startRun(m, SkyRatingEvalTrigger.MANUAL, runsPerFixture, fixtureNames))
                .toList();
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    /**
     * All runs currently in {@code RUNNING} — the batch reconciler's per-tick work list.
     *
     * @return the RUNNING runs (empty when nothing is in flight, the common case)
     */
    public List<SkyRatingEvalRunEntity> findRunning() {
        return runRepository.findByStatus(SkyRatingEvalStatus.RUNNING);
    }

    /**
     * Records the Anthropic batch id a run's requests were submitted under, so a restart-surviving
     * reconciler can later match the batch's results back to this run.
     *
     * @param run     the just-submitted run
     * @param batchId the Anthropic message-batch id
     */
    public void attachBatchId(SkyRatingEvalRunEntity run, String batchId) {
        run.setBatchId(batchId);
        runRepository.save(run);
    }

    /**
     * Removes any already-persisted results for a run. Called before the reconciler re-persists a
     * batch's results, so a reconcile that crashed part-way (rows written, run not yet finalised)
     * cannot leave duplicate child rows on the retry. Its own transaction, since the reconciler
     * deliberately runs outside one.
     *
     * @param runId the run whose results to clear
     */
    @Transactional
    public void deleteResultsForRun(Long runId) {
        resultRepository.deleteByRunId(runId);
    }

    /**
     * Executes a previously-started run: scores every fixture {@code runsPerFixture} times, persists
     * each result with its band and direction bucket, then finalises the run with aggregate
     * pass-rate, miss counts, token cost, and duration. On any error the run is marked FAILED with
     * the message preserved.
     *
     * @param runId the id of a RUNNING run produced by {@link #startRun}
     */
    public void executeRun(Long runId) {
        SkyRatingEvalRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("No sky-rating eval run: " + runId));
        long startMs = System.currentTimeMillis();
        Aggregate agg = new Aggregate();
        try {
            for (SkyRatingEvalFixture fixture : SkyRatingEvalFixtures.forStoredNames(run.getFixtureNames())) {
                AtmosphericData data = SkyRatingEvalFixtures.load(fixture);
                for (int runIndex = 1; runIndex <= run.getRunsPerFixture(); runIndex++) {
                    scoreOnce(run, fixture, data, runIndex, agg);
                }
            }
            finalise(run, agg, SkyRatingEvalStatus.COMPLETED, null, startMs);
            LOG.info("Sky-rating eval run {} COMPLETED: {}/{} in band ({}%), {} DOWN, {} UP, cost {}µ$",
                    run.getId(), agg.passes, agg.totalRuns, Math.round(agg.passRate() * 100),
                    agg.below, agg.above, agg.costMicroDollars);
        } catch (RuntimeException e) {
            finalise(run, agg, SkyRatingEvalStatus.FAILED, e.getMessage(), startMs);
            LOG.error("Sky-rating eval run {} FAILED after {} results", run.getId(), agg.totalRuns, e);
            throw e;
        }
    }

    /**
     * Entry point for the scheduler: scores every fixture with EACH model in
     * {@link #scheduledModels()}, one persisted run per model, so the weekly cadence both
     * regression-tests the prompt AND measures cross-model accuracy/drift (is each scheduled model as
     * accurate as the others on the ground-truth fixtures?). Each model's run is independent — one model's
     * failure is logged and does not abort the others. Synchronous today; the per-model runs are the
     * unit a future batched execution layer would submit.
     *
     * @return the started runs, one per model (their final status is persisted)
     */
    public List<SkyRatingEvalRunEntity> runScheduled() {
        List<SkyRatingEvalRunEntity> runs = new ArrayList<>();
        List<EvaluationModel> models = scheduledModels();
        if (models.isEmpty()) {
            LOG.info("Sky-rating eval weekly run skipped: every model is switched off");
            return runs;
        }
        for (EvaluationModel model : models) {
            SkyRatingEvalRunEntity run = startRun(model, SkyRatingEvalTrigger.SCHEDULED,
                    DEFAULT_RUNS_PER_FIXTURE);
            runs.add(run);
            try {
                executeRun(run.getId());
            } catch (RuntimeException e) {
                // executeRun already marked the run FAILED; keep going so the other models still run.
                LOG.error("Sky-rating eval scheduled run for model {} failed; continuing", model, e);
            }
        }
        return runs;
    }

    /**
     * The models the weekly eval scores: every {@link EvaluationModel#selectable() selectable} model
     * except those an admin has switched off, in selectable order. It is an opt-out list, so a newly
     * added platform model joins the weekly eval without an edit. Each model adds one weekly run (cost).
     *
     * @return the scheduled models (empty when all are switched off)
     */
    public List<EvaluationModel> scheduledModels() {
        Set<EvaluationModel> excluded = excludedModels();
        return EvaluationModel.selectable().stream().filter(m -> !excluded.contains(m)).toList();
    }

    /**
     * The weekly schedule: one row per selectable model, in selectable order.
     *
     * @return each model with whether the weekly run includes it
     */
    public List<ScheduledModelView> schedule() {
        Set<EvaluationModel> excluded = excludedModels();
        return EvaluationModel.selectable().stream()
                .map(m -> new ScheduledModelView(m, !excluded.contains(m)))
                .toList();
    }

    /**
     * Switches a model on or off for the weekly run. Idempotent: repeating a call changes nothing.
     * A manual run of any selectable model is unaffected.
     *
     * @param model     the model (must be selectable)
     * @param scheduled true to include it in the weekly run, false to exclude it
     * @return the model's resulting schedule row
     * @throws IllegalArgumentException if the model is null or not selectable
     */
    // Deliberately not @Transactional: each repository call has its own transaction, so a concurrent
    // duplicate insert is caught here without marking an outer transaction rollback-only.
    public ScheduledModelView setScheduled(EvaluationModel model, boolean scheduled) {
        if (model == null || !model.isSelectable()) {
            throw new IllegalArgumentException("Model " + model + " cannot be scheduled; choose one of "
                    + EvaluationModel.selectable());
        }
        boolean excluded = exclusionRepository.existsById(model.name());
        if (scheduled && excluded) {
            exclusionRepository.deleteById(model.name());
        } else if (!scheduled && !excluded) {
            try {
                exclusionRepository.saveAndFlush(SkyRatingEvalModelExclusionEntity.builder()
                        .model(model.name())
                        .excludedAt(LocalDateTime.now(ZoneOffset.UTC))
                        .build());
            } catch (DataIntegrityViolationException e) {
                // A concurrent request excluded the same model first: it is excluded, which is the goal.
                LOG.debug("Sky-rating eval model {} was already excluded", model);
            }
        }
        return new ScheduledModelView(model, scheduled);
    }

    private Set<EvaluationModel> excludedModels() {
        Set<EvaluationModel> excluded = new HashSet<>();
        List<String> unknown = new ArrayList<>();
        for (SkyRatingEvalModelExclusionEntity row : exclusionRepository.findAll()) {
            try {
                excluded.add(EvaluationModel.valueOf(row.getModel()));
            } catch (IllegalArgumentException e) {
                unknown.add(row.getModel());
            }
        }
        if (!unknown.isEmpty()) {
            LOG.warn("Sky-rating eval exclusion rows name models that no longer exist and are ignored: {}", unknown);
        }
        return excluded;
    }

    /**
     * Recent runs, newest first, for the admin runs list.
     *
     * @return up to 100 runs
     */
    public List<SkyRatingEvalRunEntity> recentRuns() {
        return runRepository.findTop100ByOrderByRunTimestampDesc();
    }

    /**
     * Looks up a single run.
     *
     * @param runId the run id
     * @return the run
     * @throws IllegalArgumentException if no such run exists
     */
    public SkyRatingEvalRunEntity getRun(Long runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("No sky-rating eval run: " + runId));
    }

    /**
     * Builds the calibration-drift series: one aggregate point per (completed run × fixture),
     * oldest run first. The frontend groups by fixture (a line each, against its band) and by model.
     *
     * @return the trend points, ordered by run timestamp then fixture name
     */
    public List<SkyRatingEvalTrendPoint> trend() {
        List<SkyRatingEvalRunEntity> runs =
                runRepository.findByStatusOrderByRunTimestampAsc(SkyRatingEvalStatus.COMPLETED);
        if (runs.isEmpty()) {
            return List.of();
        }
        List<Long> runIds = runs.stream().map(SkyRatingEvalRunEntity::getId).toList();
        Map<Long, List<SkyRatingEvalResultEntity>> resultsByRun = resultRepository.findByRunIdIn(runIds)
                .stream().collect(Collectors.groupingBy(SkyRatingEvalResultEntity::getRunId));

        return runs.stream()
                .flatMap(run -> resultsByRun.getOrDefault(run.getId(), List.of()).stream()
                        .collect(Collectors.groupingBy(SkyRatingEvalResultEntity::getFixtureName))
                        .entrySet().stream()
                        .map(entry -> toTrendPoint(run, entry.getKey(), entry.getValue()))
                        .sorted(java.util.Comparator.comparing(SkyRatingEvalTrendPoint::fixtureName)))
                .toList();
    }

    private static SkyRatingEvalTrendPoint toTrendPoint(SkyRatingEvalRunEntity run, String fixtureName,
            List<SkyRatingEvalResultEntity> group) {
        SkyRatingEvalResultEntity first = group.getFirst();
        long passes = group.stream()
                .filter(r -> r.getMissDirection() == MissDirection.IN_BAND).count();
        return new SkyRatingEvalTrendPoint(
                run.getId(), run.getRunTimestamp(), run.getModel(), run.getGitCommitHash(),
                fixtureName, first.getExpectedMin(), first.getExpectedMax(),
                meanOf(group, SkyRatingEvalResultEntity::getRating),
                meanOf(group, SkyRatingEvalResultEntity::getFierySky),
                meanOf(group, SkyRatingEvalResultEntity::getGoldenHour),
                group.size(), (int) passes);
    }

    private static Double meanOf(List<SkyRatingEvalResultEntity> group,
            java.util.function.Function<SkyRatingEvalResultEntity, Integer> field) {
        OptionalDouble mean = group.stream()
                .map(field).filter(Objects::nonNull).mapToInt(Integer::intValue).average();
        return mean.isPresent() ? mean.getAsDouble() : null;
    }

    private void scoreOnce(SkyRatingEvalRunEntity run, SkyRatingEvalFixture fixture,
            AtmosphericData data, int runIndex, Aggregate agg) {
        EvaluationDetail detail = evaluationService.evaluateWithDetails(data, run.getModel(), null);
        persistResult(run, fixture, runIndex, detail.evaluation(), detail.tokenUsage(),
                detail.durationMs(), false, agg);
    }

    /**
     * Persists one scored result into {@code sky_rating_eval_result} and folds it into the run's
     * aggregate. Shared by the synchronous {@link #scoreOnce} path and the batched path
     * ({@link SkyRatingEvalBatchService}), so both surfaces band-classify, cost, and accumulate
     * identically — the only difference is {@code isBatch} (which halves the cost) and the absence
     * of a per-call {@code durationMs} for batch rows.
     *
     * @param run        the parent run
     * @param fixture    the scored fixture (supplies the expected band)
     * @param runIndex   1-based repeat index within the fixture
     * @param eval       the parsed evaluation (rating may be null)
     * @param usage      token usage for this call
     * @param durationMs wall-clock duration for the call, or {@code null} for batch rows
     * @param isBatch    whether this was a Batch API call (50% cost discount)
     * @param agg        the run accumulator to fold this result into
     */
    void persistResult(SkyRatingEvalRunEntity run, SkyRatingEvalFixture fixture, int runIndex,
            SunsetEvaluation eval, TokenUsage usage, Long durationMs, boolean isBatch,
            Aggregate agg) {
        Integer rating = eval.rating();
        MissDirection direction = null;
        if (rating == null) {
            LOG.warn("Sky-rating eval run {}: fixture {} run {} returned no rating",
                    run.getId(), fixture.name(), runIndex);
        } else {
            direction = fixture.band().classify(rating);
        }

        long cost = costCalculator.calculateCostMicroDollars(run.getModel(), usage, isBatch);
        resultRepository.save(SkyRatingEvalResultEntity.builder()
                .runId(run.getId())
                .fixtureName(fixture.name())
                .runIndex(runIndex)
                .rating(rating)
                .fierySky(eval.fierySkyPotential())
                .goldenHour(eval.goldenHourPotential())
                .expectedMin(fixture.band().min())
                .expectedMax(fixture.band().max())
                .missDirection(direction)
                .summary(eval.summary())
                .inputTokens(usage.inputTokens())
                .outputTokens(usage.outputTokens())
                .durationMs(durationMs)
                .build());

        agg.record(direction, usage, cost);
    }

    /**
     * Persists a failed attempt (refused, truncated, errored, unreadable or never answered) as a
     * child row, so per-fixture reads count it as an attempt and a non-pass. The row carries no
     * rating or band direction (so no average or distribution ever sees a value for it) and
     * records the failure type in {@code summary} as {@code "FAILED: <TYPE>"}. The run aggregate
     * is updated separately through {@link Aggregate#recordFailure}.
     *
     * @param run      the parent run
     * @param fixture  the fixture the attempt was for (supplies the expected band)
     * @param runIndex 1-based repeat index within the fixture
     * @param type     why the attempt failed (e.g. REFUSAL, MAX_TOKENS, MISSING)
     * @param billedUsage usage of the billed response, or null when none was billed
     */
    void persistFailureRow(SkyRatingEvalRunEntity run, SkyRatingEvalFixture fixture, int runIndex,
            String type, TokenUsage billedUsage) {
        resultRepository.save(SkyRatingEvalResultEntity.builder()
                .inputTokens(billedUsage == null ? null : billedUsage.inputTokens())
                .outputTokens(billedUsage == null ? null : billedUsage.outputTokens())
                .runId(run.getId())
                .fixtureName(fixture.name())
                .runIndex(runIndex)
                .expectedMin(fixture.band().min())
                .expectedMax(fixture.band().max())
                .summary(FAILED_SUMMARY_PREFIX + (type == null ? "UNKNOWN" : type))
                .build());
    }

    void finalise(SkyRatingEvalRunEntity run, Aggregate agg, SkyRatingEvalStatus status,
            String errorMessage, long startMs) {
        run.setFixtureCount(SkyRatingEvalFixtures.forStoredNames(run.getFixtureNames()).size());
        run.setTotalRuns(agg.totalRuns);
        run.setTotalPasses(agg.passes);
        run.setBelowMisses(agg.below);
        run.setAboveMisses(agg.above);
        run.setPassRate(agg.passRate());
        run.setInputTokens(agg.inputTokens);
        run.setOutputTokens(agg.outputTokens);
        run.setCostMicroDollars(agg.costMicroDollars);
        run.setDurationMs(System.currentTimeMillis() - startMs);
        run.setCompletedAt(LocalDateTime.now(ZoneOffset.UTC));
        run.setStatus(status);
        run.setErrorMessage(errorMessage);
        runRepository.save(run);
    }

    /**
     * Mutable per-run accumulator. Package-private so the batched path can drive finalisation.
     *
     * <p>{@code totalRuns} is the number of evaluations attempted, so the pass rate means
     * passes / attempted: an evaluation that was refused, truncated, errored, unreadable or never
     * answered is recorded through {@link #recordFailure} and counts as a non-pass.
     */
    static final class Aggregate {
        private int failed;
        private final java.util.Map<String, Integer> failuresByType = new java.util.TreeMap<>();
        private int totalRuns;
        private int passes;
        private int below;
        private int above;
        private long inputTokens;
        private long outputTokens;
        private long costMicroDollars;

        private void record(MissDirection direction, TokenUsage usage, long cost) {
            totalRuns++;
            if (direction == MissDirection.IN_BAND) {
                passes++;
            } else if (direction == MissDirection.BELOW) {
                below++;
            } else if (direction == MissDirection.ABOVE) {
                above++;
            }
            inputTokens += usage.inputTokens();
            outputTokens += usage.outputTokens();
            costMicroDollars += cost;
        }

        /**
         * Records an evaluation that produced no usable result as an attempted non-pass.
         *
         * @param type why it failed (e.g. REFUSAL, MAX_TOKENS, ERRORED, PARSE_ERROR, MISSING)
         */
        void recordFailure(String type) {
            recordFailure(type, null, 0L);
        }

        /**
         * Records a failed attempt and folds in whatever Anthropic billed for it.
         *
         * @param type  why it failed
         * @param usage usage of the billed response, or null when none was billed
         * @param cost  batch-priced cost of that usage in micro-dollars
         */
        void recordFailure(String type, TokenUsage usage, long cost) {
            totalRuns++;
            failed++;
            failuresByType.merge(type == null ? "UNKNOWN" : type, 1, Integer::sum);
            if (usage != null) {
                inputTokens += usage.inputTokens();
                outputTokens += usage.outputTokens();
                costMicroDollars += cost;
            }
        }

        int totalRuns() {
            return totalRuns;
        }

        long inputTokens() {
            return inputTokens;
        }

        long outputTokens() {
            return outputTokens;
        }

        long costMicroDollars() {
            return costMicroDollars;
        }

        int failedCount() {
            return failed;
        }

        /** Per-type failure counts as {@code "MAX_TOKENS=1, REFUSAL=2"}, or null when none failed. */
        String failureSummary() {
            if (failed == 0) {
                return null;
            }
            return failuresByType.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(java.util.stream.Collectors.joining(", "));
        }

        private double passRate() {
            return totalRuns == 0 ? 0.0 : (double) passes / totalRuns;
        }
    }
}
