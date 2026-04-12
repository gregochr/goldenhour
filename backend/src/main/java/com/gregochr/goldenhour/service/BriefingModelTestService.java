package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.BriefingModelTestResultEntity;
import com.gregochr.goldenhour.entity.BriefingModelTestRunEntity;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.repository.BriefingModelTestResultRepository;
import com.gregochr.goldenhour.repository.BriefingModelTestRunRepository;
import com.gregochr.goldenhour.service.evaluation.BriefingBestBetAdvisor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates briefing model comparison tests — calls all three Claude models
 * (Haiku, Sonnet, Opus) with the same briefing rollup and persists the results
 * for side-by-side comparison.
 */
@Service
public class BriefingModelTestService {

    private static final Logger LOG = LoggerFactory.getLogger(BriefingModelTestService.class);

    private final BriefingService briefingService;
    private final BriefingBestBetAdvisor bestBetAdvisor;
    private final BriefingModelTestRunRepository runRepository;
    private final BriefingModelTestResultRepository resultRepository;
    private final CostCalculator costCalculator;
    private final ExchangeRateService exchangeRateService;
    private final ObjectMapper objectMapper;
    private final DynamicSchedulerService dynamicSchedulerService;

    /**
     * Constructs a {@code BriefingModelTestService}.
     *
     * @param briefingService         provides the cached briefing data
     * @param bestBetAdvisor          advisor that calls Claude for best-bet picks
     * @param runRepository           repository for test run entities
     * @param resultRepository        repository for test result entities
     * @param costCalculator          calculates micro-dollar costs from token usage
     * @param exchangeRateService     provides current GBP/USD exchange rate
     * @param objectMapper            Jackson mapper for serializing picks to JSON
     * @param dynamicSchedulerService registers the daily scheduled comparison job
     */
    public BriefingModelTestService(BriefingService briefingService,
            BriefingBestBetAdvisor bestBetAdvisor,
            BriefingModelTestRunRepository runRepository,
            BriefingModelTestResultRepository resultRepository,
            CostCalculator costCalculator,
            ExchangeRateService exchangeRateService,
            ObjectMapper objectMapper,
            DynamicSchedulerService dynamicSchedulerService) {
        this.briefingService = briefingService;
        this.bestBetAdvisor = bestBetAdvisor;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.costCalculator = costCalculator;
        this.exchangeRateService = exchangeRateService;
        this.objectMapper = objectMapper;
        this.dynamicSchedulerService = dynamicSchedulerService;
    }

    /**
     * Registers the briefing model comparison job with the dynamic scheduler.
     */
    @PostConstruct
    void registerJobs() {
        dynamicSchedulerService.registerJobTarget("briefing_model_comparison",
                this::runComparisonScheduled);
    }

    /**
     * Scheduled entry point — runs the comparison and swallows exceptions so a
     * missing cached briefing or transient API failure does not kill the scheduler thread.
     */
    void runComparisonScheduled() {
        try {
            runComparison();
        } catch (Exception e) {
            LOG.error("Scheduled briefing model comparison failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Runs a model comparison test using the currently cached briefing data.
     *
     * <p>Rebuilds the drive map from enabled locations, calls all three models via
     * {@link BriefingBestBetAdvisor#compareModels}, and persists the run + result rows.
     *
     * @return the completed test run entity
     * @throws IllegalStateException if no cached briefing is available
     */
    public BriefingModelTestRunEntity runComparison() {
        DailyBriefingResponse briefing = briefingService.getCachedBriefing();
        if (briefing == null) {
            throw new IllegalStateException("No cached briefing available — run a briefing refresh first.");
        }

        Map<String, Integer> driveMap = Map.of();

        LocalDateTime startedAt = LocalDateTime.now(ZoneOffset.UTC);

        BriefingBestBetAdvisor.ComparisonRun comparison;
        try {
            comparison = bestBetAdvisor.compareModels(briefing.days(), driveMap);
        } catch (Exception e) {
            LOG.error("Briefing model comparison failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Model comparison failed: " + e.getMessage(), e);
        }

        LocalDateTime completedAt = LocalDateTime.now(ZoneOffset.UTC);
        double exchangeRate = exchangeRateService.getCurrentRate();

        int succeeded = 0;
        int failed = 0;
        long totalCostMicroDollars = 0;

        for (BriefingBestBetAdvisor.ModelComparisonResult r : comparison.results()) {
            if (r.rawResponse() != null) {
                succeeded++;
                totalCostMicroDollars += costCalculator.calculateCostMicroDollars(
                        r.model(), r.tokenUsage());
            } else {
                failed++;
            }
        }

        BriefingModelTestRunEntity run = BriefingModelTestRunEntity.builder()
                .startedAt(startedAt)
                .completedAt(completedAt)
                .durationMs(java.time.Duration.between(startedAt, completedAt).toMillis())
                .succeeded(succeeded)
                .failed(failed)
                .totalCostMicroDollars(totalCostMicroDollars)
                .exchangeRateGbpPerUsd(exchangeRate)
                .rollupJson(comparison.rollupJson())
                .briefingGeneratedAt(briefing.generatedAt())
                .build();
        run = runRepository.save(run);

        for (BriefingBestBetAdvisor.ModelComparisonResult r : comparison.results()) {
            boolean ok = r.rawResponse() != null;
            String picksJson = null;
            try {
                picksJson = objectMapper.writeValueAsString(r.validatedPicks());
            } catch (Exception e) {
                LOG.warn("Failed to serialize picks for {}: {}", r.model(), e.getMessage());
            }

            BriefingModelTestResultEntity result = BriefingModelTestResultEntity.builder()
                    .testRunId(run.getId())
                    .evaluationModel(r.model())
                    .picksJson(picksJson)
                    .picksReturned(r.parsedPicks().size())
                    .picksValid(r.validatedPicks().size())
                    .rawResponse(r.rawResponse())
                    .durationMs(r.durationMs())
                    .inputTokens(r.tokenUsage().inputTokens())
                    .outputTokens(r.tokenUsage().outputTokens())
                    .cacheCreationInputTokens(r.tokenUsage().cacheCreationInputTokens())
                    .cacheReadInputTokens(r.tokenUsage().cacheReadInputTokens())
                    .costMicroDollars(ok ? costCalculator.calculateCostMicroDollars(
                            r.model(), r.tokenUsage()) : 0L)
                    .succeeded(ok)
                    .errorMessage(ok ? null : "Model call failed")
                    .createdAt(completedAt)
                    .build();
            resultRepository.save(result);
        }

        LOG.info("Briefing model comparison complete: {}/{} succeeded, cost={}µ$",
                succeeded, comparison.results().size(), totalCostMicroDollars);
        return run;
    }

    /**
     * Returns the 20 most recent briefing model test runs.
     *
     * @return recent test runs, newest first
     */
    public List<BriefingModelTestRunEntity> getRecentRuns() {
        return runRepository.findTop20ByOrderByStartedAtDesc();
    }

    /**
     * Returns all results for a given test run.
     *
     * @param runId the test run ID
     * @return results ordered by evaluation model
     */
    public List<BriefingModelTestResultEntity> getResults(Long runId) {
        return resultRepository.findByTestRunIdOrderByEvaluationModelAsc(runId);
    }
}
