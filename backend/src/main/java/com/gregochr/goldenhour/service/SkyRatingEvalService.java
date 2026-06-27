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
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.SkyRatingEvalResultRepository;
import com.gregochr.goldenhour.repository.SkyRatingEvalRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

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

    private static final Logger log = LoggerFactory.getLogger(SkyRatingEvalService.class);

    /** Default runs per fixture — matches the gated {@code SkyRatingEvalTest} pass^k depth. */
    public static final int DEFAULT_RUNS_PER_FIXTURE = 8;

    private final EvaluationService evaluationService;
    private final CostCalculator costCalculator;
    private final GitInfoService gitInfoService;
    private final SkyRatingEvalRunRepository runRepository;
    private final SkyRatingEvalResultRepository resultRepository;

    /**
     * Constructs the service.
     *
     * @param evaluationService the production scorer (same path the forecast pipeline uses)
     * @param costCalculator    token → micro-dollar cost
     * @param gitInfoService    git commit metadata, for attributing drift to a prompt edit
     * @param runRepository     parent-run persistence
     * @param resultRepository  per-result persistence
     */
    public SkyRatingEvalService(EvaluationService evaluationService, CostCalculator costCalculator,
            GitInfoService gitInfoService, SkyRatingEvalRunRepository runRepository,
            SkyRatingEvalResultRepository resultRepository) {
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
            log.info("Sky-rating eval run {} COMPLETED: {}/{} in band ({}%), {} DOWN, {} UP, cost {}µ$",
                    run.getId(), agg.passes, agg.totalRuns, Math.round(agg.passRate() * 100),
                    agg.below, agg.above, agg.costMicroDollars);
        } catch (RuntimeException e) {
            finalise(run, agg, SkyRatingEvalStatus.FAILED, e.getMessage(), startMs);
            log.error("Sky-rating eval run {} FAILED after {} results", run.getId(), agg.totalRuns, e);
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

    private void scoreOnce(SkyRatingEvalRunEntity run, SkyRatingEvalFixture fixture,
            AtmosphericData data, int runIndex, Aggregate agg) {
        EvaluationDetail detail = evaluationService.evaluateWithDetails(data, run.getModel(), null);
        SunsetEvaluation eval = detail.evaluation();
        TokenUsage usage = detail.tokenUsage();
        Integer rating = eval.rating();

        MissDirection direction = null;
        if (rating == null) {
            log.warn("Sky-rating eval run {}: fixture {} run {} returned no rating",
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
