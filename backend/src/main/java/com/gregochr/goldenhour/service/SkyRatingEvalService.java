package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.SkyRatingEvalResultEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalTrigger;
import com.gregochr.goldenhour.eval.MissDirection;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixture;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixtures;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SkyRatingEvalTrendPoint;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.SkyRatingEvalResultRepository;
import com.gregochr.goldenhour.repository.SkyRatingEvalRunRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
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

    /** Default runs per fixture — matches the gated {@code SkyRatingEvalTest} pass^k depth. */
    public static final int DEFAULT_RUNS_PER_FIXTURE = 8;

    /** Scheduler job key — matches the seed row in V118 and the registered runnable. */
    public static final String JOB_KEY = "sky_rating_eval";

    private final EvaluationService evaluationService;
    private final CostCalculator costCalculator;
    private final GitInfoService gitInfoService;
    private final SkyRatingEvalRunRepository runRepository;
    private final SkyRatingEvalResultRepository resultRepository;
    private final DynamicSchedulerService dynamicSchedulerService;

    /**
     * Constructs the service.
     *
     * @param evaluationService       the production scorer (same path the forecast pipeline uses)
     * @param costCalculator          token → micro-dollar cost
     * @param gitInfoService          git commit metadata, for attributing drift to a prompt edit
     * @param runRepository           parent-run persistence
     * @param resultRepository        per-result persistence
     * @param dynamicSchedulerService the scheduler this service registers its weekly job with
     */
    public SkyRatingEvalService(EvaluationService evaluationService, CostCalculator costCalculator,
            GitInfoService gitInfoService, SkyRatingEvalRunRepository runRepository,
            SkyRatingEvalResultRepository resultRepository,
            DynamicSchedulerService dynamicSchedulerService) {
        this.evaluationService = evaluationService;
        this.costCalculator = costCalculator;
        this.gitInfoService = gitInfoService;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.dynamicSchedulerService = dynamicSchedulerService;
    }

    /**
     * Registers the weekly eval as a dynamic scheduler target. The job is seeded PAUSED (V118), so
     * registering the runnable is harmless until an admin resumes it from the Scheduler UI.
     */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget(JOB_KEY, this::runScheduled);
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
                .build();
        return runRepository.save(run);
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
            for (SkyRatingEvalFixture fixture : SkyRatingEvalFixtures.ALL) {
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
     * Convenience entry point for the scheduler: start + execute synchronously with defaults
     * (Sonnet, the production near-term scorer; {@link #DEFAULT_RUNS_PER_FIXTURE} runs).
     *
     * @return the completed (or failed) run
     */
    public SkyRatingEvalRunEntity runScheduled() {
        SkyRatingEvalRunEntity run = startRun(EvaluationModel.SONNET, SkyRatingEvalTrigger.SCHEDULED,
                DEFAULT_RUNS_PER_FIXTURE);
        executeRun(run.getId());
        return run;
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
     * All per-(fixture × run-index) results for one run.
     *
     * @param runId the run id
     * @return the run's results, ordered by fixture then run index
     */
    public List<SkyRatingEvalResultEntity> resultsForRun(Long runId) {
        return resultRepository.findByRunIdOrderByFixtureNameAscRunIndexAsc(runId);
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
        SunsetEvaluation eval = detail.evaluation();
        TokenUsage usage = detail.tokenUsage();
        Integer rating = eval.rating();

        MissDirection direction = null;
        if (rating == null) {
            LOG.warn("Sky-rating eval run {}: fixture {} run {} returned no rating",
                    run.getId(), fixture.name(), runIndex);
        } else {
            direction = fixture.band().classify(rating);
        }

        long cost = costCalculator.calculateCostMicroDollars(run.getModel(), usage);
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
                .durationMs(detail.durationMs())
                .build());

        agg.record(direction, usage, cost);
    }

    private void finalise(SkyRatingEvalRunEntity run, Aggregate agg, SkyRatingEvalStatus status,
            String errorMessage, long startMs) {
        run.setFixtureCount(SkyRatingEvalFixtures.ALL.size());
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

    /** Mutable per-run accumulator. */
    private static final class Aggregate {
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

        private double passRate() {
            return totalRuns == 0 ? 0.0 : (double) passes / totalRuns;
        }
    }
}
