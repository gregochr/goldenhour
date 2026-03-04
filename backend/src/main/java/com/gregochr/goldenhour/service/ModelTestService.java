package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.ModelTestResultEntity;
import com.gregochr.goldenhour.entity.ModelTestRunEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RerunType;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.ModelTestResultRepository;
import com.gregochr.goldenhour.repository.ModelTestRunRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Orchestrates model comparison tests across regions.
 *
 * <p>For each enabled region, selects one representative colour location and runs
 * all three Claude models (Haiku, Sonnet, Opus) against identical atmospheric data.
 * Results are persisted for side-by-side comparison in the admin UI.
 */
@Service
public class ModelTestService {

    private static final Logger LOG = LoggerFactory.getLogger(ModelTestService.class);

    private static final List<EvaluationModel> TEST_MODELS = List.of(
            EvaluationModel.HAIKU, EvaluationModel.SONNET, EvaluationModel.OPUS);

    private final RegionRepository regionRepository;
    private final LocationRepository locationRepository;
    private final ModelTestRunRepository testRunRepository;
    private final ModelTestResultRepository testResultRepository;
    private final OpenMeteoService openMeteoService;
    private final ForecastService forecastService;
    private final EvaluationService evaluationService;
    private final SolarService solarService;
    private final CostCalculator costCalculator;
    private final ExchangeRateService exchangeRateService;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a {@code ModelTestService}.
     *
     * @param regionRepository      region repository
     * @param locationRepository    location repository
     * @param testRunRepository     model test run repository
     * @param testResultRepository  model test result repository
     * @param openMeteoService      Open-Meteo weather data service
     * @param forecastService       forecast service (for tide augmentation)
     * @param evaluationService     evaluation service (delegates to strategies)
     * @param solarService          solar calculation service
     * @param costCalculator        API cost calculator
     * @param exchangeRateService   exchange rate service for GBP conversion
     */
    public ModelTestService(RegionRepository regionRepository,
            LocationRepository locationRepository,
            ModelTestRunRepository testRunRepository,
            ModelTestResultRepository testResultRepository,
            OpenMeteoService openMeteoService,
            ForecastService forecastService,
            EvaluationService evaluationService,
            SolarService solarService,
            CostCalculator costCalculator,
            ExchangeRateService exchangeRateService) {
        this.regionRepository = regionRepository;
        this.locationRepository = locationRepository;
        this.testRunRepository = testRunRepository;
        this.testResultRepository = testResultRepository;
        this.openMeteoService = openMeteoService;
        this.forecastService = forecastService;
        this.evaluationService = evaluationService;
        this.solarService = solarService;
        this.costCalculator = costCalculator;
        this.exchangeRateService = exchangeRateService;
        this.objectMapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    /**
     * Runs a model comparison test across all enabled regions.
     *
     * <p>For each region, finds the first enabled colour location (alphabetically)
     * and evaluates it with Haiku, Sonnet, and Opus using the same atmospheric data.
     *
     * @return the completed test run with populated results
     */
    public ModelTestRunEntity runTest() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<RegionEntity> regions = regionRepository.findAllByEnabledTrueOrderByNameAsc();
        List<LocationEntity> allLocations = locationRepository.findAllByEnabledTrueOrderByNameAsc();

        // Determine target date and type
        TargetType primaryTargetType = resolveTargetType(now, allLocations);
        LocalDate targetDate = resolveTargetDate(now, primaryTargetType, allLocations);
        List<TargetType> targetTypes = resolveTargetTypesForDate(targetDate, now, allLocations);
        Double exchangeRate = fetchExchangeRate();

        LOG.info("Model test started — {} regions, target: {} {}",
                regions.size(), targetDate, targetTypes);

        ModelTestRunEntity testRun = testRunRepository.save(ModelTestRunEntity.builder()
                .startedAt(now)
                .targetDate(targetDate)
                .targetType(primaryTargetType)
                .regionsCount(0)
                .succeeded(0)
                .failed(0)
                .totalCostPence(0)
                .exchangeRateGbpPerUsd(exchangeRate)
                .build());

        int succeeded = 0;
        int failed = 0;
        int totalCostPence = 0;
        long totalCostMicroDollars = 0;
        int regionsProcessed = 0;

        for (RegionEntity region : regions) {
            LocationEntity location = findRepresentativeLocation(allLocations, region);
            if (location == null) {
                LOG.info("Region '{}' has no enabled colour location — skipping", region.getName());
                continue;
            }

            regionsProcessed++;
            LOG.info("Testing region '{}' with location '{}'", region.getName(), location.getName());

            for (TargetType targetType : targetTypes) {
                if (!locationSupportsTargetType(location, targetType)) {
                    continue;
                }

                // Fetch atmospheric data once for all three models per target type
                AtmosphericData atmosphericData;
                try {
                    atmosphericData = fetchAtmosphericData(location, targetDate, targetType);
                } catch (Exception e) {
                    LOG.error("Failed to fetch weather data for {} {} — skipping region '{}'",
                            location.getName(), targetType, region.getName(), e);
                    for (EvaluationModel model : TEST_MODELS) {
                        failed++;
                        testResultRepository.save(buildFailedResult(testRun, region, location,
                                targetDate, targetType, model, 0,
                                "Weather data fetch failed: " + e.getMessage()));
                    }
                    continue;
                }

                // Run each model against the same data
                for (EvaluationModel model : TEST_MODELS) {
                    try {
                        EvaluationDetail detail = evaluationService.evaluateWithDetails(
                                atmosphericData, model, null);
                        TokenUsage tokenUsage = detail.tokenUsage() != null
                                ? detail.tokenUsage() : TokenUsage.EMPTY;
                        int costPence = costCalculator.calculateCost(
                                com.gregochr.goldenhour.entity.ServiceName.ANTHROPIC, model);
                        long costMicroDollars = costCalculator.calculateCostMicroDollars(
                                model, tokenUsage);
                        totalCostPence += costPence;
                        totalCostMicroDollars += costMicroDollars;
                        succeeded++;

                        ModelTestResultEntity result = ModelTestResultEntity.builder()
                                .testRunId(testRun.getId())
                                .regionId(region.getId())
                                .regionName(region.getName())
                                .locationId(location.getId())
                                .locationName(location.getName())
                                .targetDate(targetDate)
                                .targetType(targetType)
                                .evaluationModel(model)
                                .rating(detail.evaluation().rating())
                                .fierySkyPotential(detail.evaluation().fierySkyPotential())
                                .goldenHourPotential(detail.evaluation().goldenHourPotential())
                                .summary(detail.evaluation().summary())
                                .promptSent(detail.promptSent())
                                .responseJson(detail.rawResponse())
                                .durationMs(detail.durationMs())
                                .costPence(costPence)
                                .inputTokens(tokenUsage.inputTokens())
                                .outputTokens(tokenUsage.outputTokens())
                                .cacheCreationInputTokens(tokenUsage.cacheCreationInputTokens())
                                .cacheReadInputTokens(tokenUsage.cacheReadInputTokens())
                                .costMicroDollars(costMicroDollars)
                                .succeeded(true)
                                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                                .build();
                        populateAtmosphericData(result, atmosphericData);
                        testResultRepository.save(result);
                    } catch (Exception e) {
                        LOG.error("Model {} failed for {} {} in region '{}': {}",
                                model, location.getName(), targetType, region.getName(),
                                e.getMessage());
                        failed++;

                        testResultRepository.save(buildFailedResult(testRun, region, location,
                                targetDate, targetType, model, 0, e.getMessage()));
                    }
                }
            }
        }

        return completeRun(testRun, now, regionsProcessed, succeeded, failed,
                totalCostPence, totalCostMicroDollars);
    }

    /**
     * Runs a model comparison test for a single location.
     *
     * <p>Looks up the location by ID, validates it is enabled, has colour types,
     * and belongs to a region. Then evaluates it with all three Claude models
     * using identical atmospheric data.
     *
     * @param locationId the location ID to test
     * @return the completed test run with populated results
     * @throws NoSuchElementException   if no location exists with the given ID
     * @throws IllegalArgumentException if the location is disabled, has no colour types,
     *                                  or has no region assigned
     */
    public ModelTestRunEntity runTestForLocation(Long locationId) {
        LocationEntity location = locationRepository.findById(locationId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Location not found: " + locationId));

        if (!location.isEnabled()) {
            throw new IllegalArgumentException(
                    "Location '" + location.getName() + "' is disabled");
        }
        if (!hasColourTypes(location)) {
            throw new IllegalArgumentException(
                    "Location '" + location.getName() + "' has no colour types (pure WILDLIFE)");
        }
        if (location.getRegion() == null) {
            throw new IllegalArgumentException(
                    "Location '" + location.getName() + "' has no region assigned");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<LocationEntity> allLocations = locationRepository.findAllByEnabledTrueOrderByNameAsc();
        TargetType primaryTargetType = resolveTargetType(now, allLocations);
        LocalDate targetDate = resolveTargetDate(now, primaryTargetType, allLocations);
        List<TargetType> targetTypes = resolveTargetTypesForDate(targetDate, now, allLocations);
        RegionEntity region = location.getRegion();

        Double exchangeRate = fetchExchangeRate();
        LOG.info("Single-location model test started — location '{}', target: {} {}",
                location.getName(), targetDate, targetTypes);

        ModelTestRunEntity testRun = testRunRepository.save(ModelTestRunEntity.builder()
                .startedAt(now)
                .targetDate(targetDate)
                .targetType(primaryTargetType)
                .regionsCount(0)
                .succeeded(0)
                .failed(0)
                .totalCostPence(0)
                .exchangeRateGbpPerUsd(exchangeRate)
                .build());

        int succeeded = 0;
        int failed = 0;
        int totalCostPence = 0;
        long totalCostMicroDollars = 0;

        for (TargetType targetType : targetTypes) {
            if (!locationSupportsTargetType(location, targetType)) {
                continue;
            }

            // Fetch atmospheric data once for all three models per target type
            AtmosphericData atmosphericData;
            try {
                atmosphericData = fetchAtmosphericData(location, targetDate, targetType);
            } catch (Exception e) {
                LOG.error("Failed to fetch weather data for '{}' {}: {}",
                        location.getName(), targetType, e.getMessage(), e);
                for (EvaluationModel model : TEST_MODELS) {
                    failed++;
                    testResultRepository.save(buildFailedResult(testRun, region, location,
                            targetDate, targetType, model, 0,
                            "Weather data fetch failed: " + e.getMessage()));
                }
                continue;
            }

            // Run each model against the same data
            for (EvaluationModel model : TEST_MODELS) {
                try {
                    EvaluationDetail detail = evaluationService.evaluateWithDetails(
                            atmosphericData, model, null);
                    TokenUsage tokenUsage = detail.tokenUsage() != null
                            ? detail.tokenUsage() : TokenUsage.EMPTY;
                    int costPence = costCalculator.calculateCost(
                            com.gregochr.goldenhour.entity.ServiceName.ANTHROPIC, model);
                    long costMicroDollars = costCalculator.calculateCostMicroDollars(
                            model, tokenUsage);
                    totalCostPence += costPence;
                    totalCostMicroDollars += costMicroDollars;
                    succeeded++;

                    ModelTestResultEntity result = ModelTestResultEntity.builder()
                            .testRunId(testRun.getId())
                            .regionId(region.getId())
                            .regionName(region.getName())
                            .locationId(location.getId())
                            .locationName(location.getName())
                            .targetDate(targetDate)
                            .targetType(targetType)
                            .evaluationModel(model)
                            .rating(detail.evaluation().rating())
                            .fierySkyPotential(detail.evaluation().fierySkyPotential())
                            .goldenHourPotential(detail.evaluation().goldenHourPotential())
                            .summary(detail.evaluation().summary())
                            .promptSent(detail.promptSent())
                            .responseJson(detail.rawResponse())
                            .durationMs(detail.durationMs())
                            .costPence(costPence)
                            .inputTokens(tokenUsage.inputTokens())
                            .outputTokens(tokenUsage.outputTokens())
                            .cacheCreationInputTokens(tokenUsage.cacheCreationInputTokens())
                            .cacheReadInputTokens(tokenUsage.cacheReadInputTokens())
                            .costMicroDollars(costMicroDollars)
                            .succeeded(true)
                            .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                            .build();
                    populateAtmosphericData(result, atmosphericData);
                    testResultRepository.save(result);
                } catch (Exception e) {
                    LOG.error("Model {} failed for '{}' {}: {}",
                            model, location.getName(), targetType, e.getMessage());
                    failed++;

                    testResultRepository.save(buildFailedResult(testRun, region, location,
                            targetDate, targetType, model, 0, e.getMessage()));
                }
            }
        }

        return completeRun(testRun, now, 1, succeeded, failed,
                totalCostPence, totalCostMicroDollars);
    }

    /**
     * Re-runs a previous model test using the same locations but fresh weather data
     * and fresh Anthropic API calls. Useful for measuring variance between runs.
     *
     * @param previousRunId the ID of the test run to re-run
     * @return the completed new test run
     * @throws NoSuchElementException if the previous run does not exist or has no results
     */
    public ModelTestRunEntity rerunTest(Long previousRunId) {
        testRunRepository.findById(previousRunId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Test run not found: " + previousRunId));

        List<ModelTestResultEntity> previousResults = testResultRepository
                .findByTestRunIdOrderByRegionNameAscEvaluationModelAsc(previousRunId);
        if (previousResults.isEmpty()) {
            throw new NoSuchElementException(
                    "No results found for test run: " + previousRunId);
        }

        // Extract distinct locations (preserving order) from the previous run
        Map<Long, ModelTestResultEntity> locationMap = new LinkedHashMap<>();
        for (ModelTestResultEntity r : previousResults) {
            locationMap.putIfAbsent(r.getLocationId(), r);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<LocationEntity> allLocations = locationRepository.findAllByEnabledTrueOrderByNameAsc();
        TargetType primaryTargetType = resolveTargetType(now, allLocations);
        LocalDate targetDate = resolveTargetDate(now, primaryTargetType, allLocations);
        List<TargetType> targetTypes = resolveTargetTypesForDate(targetDate, now, allLocations);

        Double exchangeRate = fetchExchangeRate();
        LOG.info("Re-running model test #{} (fresh data) — {} locations, target: {} {}",
                previousRunId, locationMap.size(), targetDate, targetTypes);

        ModelTestRunEntity testRun = testRunRepository.save(ModelTestRunEntity.builder()
                .startedAt(now)
                .targetDate(targetDate)
                .targetType(primaryTargetType)
                .regionsCount(0)
                .succeeded(0)
                .failed(0)
                .totalCostPence(0)
                .exchangeRateGbpPerUsd(exchangeRate)
                .parentRunId(previousRunId)
                .rerunType(RerunType.FRESH_DATA)
                .build());

        int succeeded = 0;
        int failed = 0;
        int totalCostPence = 0;
        long totalCostMicroDollars = 0;
        int locationsProcessed = 0;

        for (ModelTestResultEntity ref : locationMap.values()) {
            LocationEntity location = locationRepository.findById(ref.getLocationId()).orElse(null);
            if (location == null || !location.isEnabled() || !hasColourTypes(location)) {
                LOG.info("Skipping location '{}' (id={}) — not found, disabled, or no colour types",
                        ref.getLocationName(), ref.getLocationId());
                continue;
            }

            RegionEntity region = location.getRegion();
            if (region == null) {
                LOG.info("Skipping location '{}' — no region assigned", location.getName());
                continue;
            }

            locationsProcessed++;

            for (TargetType targetType : targetTypes) {
                if (!locationSupportsTargetType(location, targetType)) {
                    continue;
                }

                AtmosphericData atmosphericData;
                try {
                    atmosphericData = fetchAtmosphericData(location, targetDate, targetType);
                } catch (Exception e) {
                    LOG.error("Failed to fetch weather data for '{}' {}: {}",
                            location.getName(), targetType, e.getMessage(), e);
                    for (EvaluationModel model : TEST_MODELS) {
                        failed++;
                        testResultRepository.save(buildFailedResult(testRun, region, location,
                                targetDate, targetType, model, 0,
                                "Weather data fetch failed: " + e.getMessage()));
                    }
                    continue;
                }

                for (EvaluationModel model : TEST_MODELS) {
                    try {
                        EvaluationDetail detail = evaluationService.evaluateWithDetails(
                                atmosphericData, model, null);
                        TokenUsage tokenUsage = detail.tokenUsage() != null
                                ? detail.tokenUsage() : TokenUsage.EMPTY;
                        int costPence = costCalculator.calculateCost(
                                com.gregochr.goldenhour.entity.ServiceName.ANTHROPIC, model);
                        long costMicroDollars = costCalculator.calculateCostMicroDollars(
                                model, tokenUsage);
                        totalCostPence += costPence;
                        totalCostMicroDollars += costMicroDollars;
                        succeeded++;

                        ModelTestResultEntity result = ModelTestResultEntity.builder()
                                .testRunId(testRun.getId())
                                .regionId(region.getId())
                                .regionName(region.getName())
                                .locationId(location.getId())
                                .locationName(location.getName())
                                .targetDate(targetDate)
                                .targetType(targetType)
                                .evaluationModel(model)
                                .rating(detail.evaluation().rating())
                                .fierySkyPotential(detail.evaluation().fierySkyPotential())
                                .goldenHourPotential(detail.evaluation().goldenHourPotential())
                                .summary(detail.evaluation().summary())
                                .promptSent(detail.promptSent())
                                .responseJson(detail.rawResponse())
                                .durationMs(detail.durationMs())
                                .costPence(costPence)
                                .inputTokens(tokenUsage.inputTokens())
                                .outputTokens(tokenUsage.outputTokens())
                                .cacheCreationInputTokens(tokenUsage.cacheCreationInputTokens())
                                .cacheReadInputTokens(tokenUsage.cacheReadInputTokens())
                                .costMicroDollars(costMicroDollars)
                                .succeeded(true)
                                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                                .build();
                        populateAtmosphericData(result, atmosphericData);
                        testResultRepository.save(result);
                    } catch (Exception e) {
                        LOG.error("Model {} failed for '{}' {}: {}",
                                model, location.getName(), targetType, e.getMessage());
                        failed++;

                        testResultRepository.save(buildFailedResult(testRun, region, location,
                                targetDate, targetType, model, 0, e.getMessage()));
                    }
                }
            }
        }

        return completeRun(testRun, now, locationsProcessed, succeeded, failed,
                totalCostPence, totalCostMicroDollars);
    }

    /**
     * Re-runs a previous model test using the exact same atmospheric data (no Open-Meteo calls).
     *
     * <p>Deserialises stored {@code atmosphericDataJson} from the previous run's results and
     * replays it through all three Claude models. This tests whether Claude's evaluation
     * is deterministic given identical input.
     *
     * @param previousRunId the ID of the test run to replay
     * @return the completed new test run
     * @throws NoSuchElementException   if the previous run does not exist or has no results
     * @throws IllegalStateException    if the previous run's results lack stored atmospheric data
     */
    public ModelTestRunEntity rerunTestDeterministic(Long previousRunId) {
        ModelTestRunEntity previousRun = testRunRepository.findById(previousRunId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Test run not found: " + previousRunId));

        List<ModelTestResultEntity> previousResults = testResultRepository
                .findByTestRunIdOrderByRegionNameAscEvaluationModelAsc(previousRunId);
        if (previousResults.isEmpty()) {
            throw new NoSuchElementException(
                    "No results found for test run: " + previousRunId);
        }

        // Extract distinct locations with their stored atmospheric data
        Map<Long, ModelTestResultEntity> locationMap = new LinkedHashMap<>();
        for (ModelTestResultEntity r : previousResults) {
            if (r.getSucceeded() && r.getAtmosphericDataJson() != null
                    && !locationMap.containsKey(r.getLocationId())) {
                locationMap.put(r.getLocationId(), r);
            }
        }
        if (locationMap.isEmpty()) {
            throw new IllegalStateException(
                    "No atmospheric data stored on results of test run " + previousRunId
                            + " — cannot replay. Run a new test first (post-V39).");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Double exchangeRate = fetchExchangeRate();
        LOG.info("Re-running model test #{} (same data / determinism) — {} locations",
                previousRunId, locationMap.size());

        ModelTestRunEntity testRun = testRunRepository.save(ModelTestRunEntity.builder()
                .startedAt(now)
                .targetDate(previousRun.getTargetDate())
                .targetType(previousRun.getTargetType())
                .regionsCount(0)
                .succeeded(0)
                .failed(0)
                .totalCostPence(0)
                .exchangeRateGbpPerUsd(exchangeRate)
                .parentRunId(previousRunId)
                .rerunType(RerunType.SAME_DATA)
                .build());

        int succeeded = 0;
        int failed = 0;
        int totalCostPence = 0;
        long totalCostMicroDollars = 0;
        int locationsProcessed = 0;

        for (ModelTestResultEntity ref : locationMap.values()) {
            AtmosphericData atmosphericData;
            try {
                atmosphericData = objectMapper.readValue(
                        ref.getAtmosphericDataJson(), AtmosphericData.class);
            } catch (JsonProcessingException e) {
                LOG.error("Failed to deserialise atmospheric data for location '{}': {}",
                        ref.getLocationName(), e.getMessage());
                for (EvaluationModel model : TEST_MODELS) {
                    failed++;
                    testResultRepository.save(buildFailedResultFromRef(testRun, ref, model,
                            "Atmospheric data deserialisation failed: " + e.getMessage()));
                }
                continue;
            }

            locationsProcessed++;

            for (EvaluationModel model : TEST_MODELS) {
                try {
                    EvaluationDetail detail = evaluationService.evaluateWithDetails(
                            atmosphericData, model, null);
                    TokenUsage tokenUsage = detail.tokenUsage() != null
                            ? detail.tokenUsage() : TokenUsage.EMPTY;
                    int costPence = costCalculator.calculateCost(
                            com.gregochr.goldenhour.entity.ServiceName.ANTHROPIC, model);
                    long costMicroDollars = costCalculator.calculateCostMicroDollars(
                            model, tokenUsage);
                    totalCostPence += costPence;
                    totalCostMicroDollars += costMicroDollars;
                    succeeded++;

                    ModelTestResultEntity result = ModelTestResultEntity.builder()
                            .testRunId(testRun.getId())
                            .regionId(ref.getRegionId())
                            .regionName(ref.getRegionName())
                            .locationId(ref.getLocationId())
                            .locationName(ref.getLocationName())
                            .targetDate(ref.getTargetDate())
                            .targetType(ref.getTargetType())
                            .evaluationModel(model)
                            .rating(detail.evaluation().rating())
                            .fierySkyPotential(detail.evaluation().fierySkyPotential())
                            .goldenHourPotential(detail.evaluation().goldenHourPotential())
                            .summary(detail.evaluation().summary())
                            .promptSent(detail.promptSent())
                            .responseJson(detail.rawResponse())
                            .durationMs(detail.durationMs())
                            .costPence(costPence)
                            .inputTokens(tokenUsage.inputTokens())
                            .outputTokens(tokenUsage.outputTokens())
                            .cacheCreationInputTokens(tokenUsage.cacheCreationInputTokens())
                            .cacheReadInputTokens(tokenUsage.cacheReadInputTokens())
                            .costMicroDollars(costMicroDollars)
                            .succeeded(true)
                            .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                            .build();
                    populateAtmosphericData(result, atmosphericData);
                    testResultRepository.save(result);
                } catch (Exception e) {
                    LOG.error("Model {} failed for '{}' (determinism rerun): {}",
                            model, ref.getLocationName(), e.getMessage());
                    failed++;

                    testResultRepository.save(buildFailedResultFromRef(testRun, ref, model,
                            e.getMessage()));
                }
            }
        }

        return completeRun(testRun, now, locationsProcessed, succeeded, failed,
                totalCostPence, totalCostMicroDollars);
    }

    /**
     * Returns recent test runs (last 20).
     *
     * @return list of recent test runs ordered by start time descending
     */
    public List<ModelTestRunEntity> getRecentRuns() {
        return testRunRepository.findTop20ByOrderByStartedAtDesc();
    }

    /**
     * Returns results for a specific test run.
     *
     * @param testRunId the test run ID
     * @return results ordered by region name then model
     */
    public List<ModelTestResultEntity> getResults(Long testRunId) {
        return testResultRepository.findByTestRunIdOrderByRegionNameAscEvaluationModelAsc(testRunId);
    }

    /**
     * Finds the first enabled colour location in a region (alphabetically by name).
     *
     * @param allLocations all enabled locations
     * @param region       the region to find a location for
     * @return the representative location, or null if none found
     */
    LocationEntity findRepresentativeLocation(List<LocationEntity> allLocations,
            RegionEntity region) {
        return allLocations.stream()
                .filter(loc -> loc.getRegion() != null
                        && loc.getRegion().getId().equals(region.getId()))
                .filter(this::hasColourTypes)
                .findFirst()
                .orElse(null);
    }

    /**
     * Determines the target type based on current UTC time relative to today's sunset.
     *
     * @param now           current UTC time
     * @param allLocations  all enabled locations (uses first to estimate sunset)
     * @return SUNSET if before today's sunset, SUNRISE otherwise
     */
    TargetType resolveTargetType(LocalDateTime now, List<LocationEntity> allLocations) {
        if (allLocations.isEmpty()) {
            return TargetType.SUNSET;
        }
        LocationEntity first = allLocations.get(0);
        LocalDateTime todaySunset = solarService.sunsetUtc(first.getLat(), first.getLon(),
                now.toLocalDate());
        return now.isBefore(todaySunset) ? TargetType.SUNSET : TargetType.SUNRISE;
    }

    /**
     * Determines the target date based on the resolved target type.
     *
     * @param now           current UTC time
     * @param targetType    the resolved target type
     * @param allLocations  all enabled locations
     * @return today if targeting today's sunset, tomorrow if targeting tomorrow's sunrise
     */
    LocalDate resolveTargetDate(LocalDateTime now, TargetType targetType,
            List<LocationEntity> allLocations) {
        if (targetType == TargetType.SUNSET) {
            return now.toLocalDate();
        }
        return now.toLocalDate().plusDays(1);
    }

    /**
     * Resolves which target types to evaluate for a given date.
     *
     * <p>For future dates, both SUNRISE and SUNSET are returned. For today, only events
     * that have not yet passed are included, using the first location as a time reference.
     *
     * @param date          the target date
     * @param now           current UTC time
     * @param allLocations  all enabled locations (first is used as solar time reference)
     * @return list of applicable target types for the date
     */
    List<TargetType> resolveTargetTypesForDate(LocalDate date, LocalDateTime now,
            List<LocationEntity> allLocations) {
        if (!date.equals(now.toLocalDate()) || allLocations.isEmpty()) {
            return List.of(TargetType.SUNRISE, TargetType.SUNSET);
        }
        LocationEntity ref = allLocations.get(0);
        List<TargetType> types = new ArrayList<>();
        LocalDateTime sunrise = solarService.sunriseUtc(ref.getLat(), ref.getLon(), date);
        LocalDateTime sunset = solarService.sunsetUtc(ref.getLat(), ref.getLon(), date);
        if (now.isBefore(sunrise)) {
            types.add(TargetType.SUNRISE);
        }
        if (now.isBefore(sunset)) {
            types.add(TargetType.SUNSET);
        }
        return types;
    }

    private boolean locationSupportsTargetType(LocationEntity location, TargetType targetType) {
        Set<SolarEventType> solarTypes = location.getSolarEventType();
        if (solarTypes == null || solarTypes.isEmpty()
                || solarTypes.contains(SolarEventType.ALLDAY)) {
            return true;
        }
        return switch (targetType) {
            case SUNRISE -> solarTypes.contains(SolarEventType.SUNRISE);
            case SUNSET -> solarTypes.contains(SolarEventType.SUNSET);
            case HOURLY -> true;
        };
    }

    private ModelTestRunEntity completeRun(ModelTestRunEntity testRun, LocalDateTime startedAt,
            int regionsProcessed, int succeeded, int failed, int totalCostPence,
            long totalCostMicroDollars) {
        LocalDateTime completedAt = LocalDateTime.now(ZoneOffset.UTC);
        testRun.setCompletedAt(completedAt);
        testRun.setDurationMs(java.time.Duration.between(startedAt, completedAt).toMillis());
        testRun.setRegionsCount(regionsProcessed);
        testRun.setSucceeded(succeeded);
        testRun.setFailed(failed);
        testRun.setTotalCostPence(totalCostPence);
        testRun.setTotalCostMicroDollars(totalCostMicroDollars);
        testRun = testRunRepository.save(testRun);

        LOG.info("Model test complete — {} regions, {} succeeded, {} failed, {}µ$ cost",
                regionsProcessed, succeeded, failed, totalCostMicroDollars);

        return testRun;
    }

    private Double fetchExchangeRate() {
        try {
            return exchangeRateService.getCurrentRate();
        } catch (Exception e) {
            LOG.warn("Failed to fetch exchange rate for model test: {}", e.getMessage());
            return null;
        }
    }

    private boolean hasColourTypes(LocationEntity loc) {
        return loc.getLocationType().contains(LocationType.LANDSCAPE)
                || loc.getLocationType().contains(LocationType.SEASCAPE)
                || loc.getLocationType().isEmpty();
    }

    private AtmosphericData fetchAtmosphericData(LocationEntity location, LocalDate targetDate,
            TargetType targetType) {
        double lat = location.getLat();
        double lon = location.getLon();
        LocalDateTime eventTime = targetType == TargetType.SUNRISE
                ? solarService.sunriseUtc(lat, lon, targetDate)
                : solarService.sunsetUtc(lat, lon, targetDate);

        ForecastRequest request = new ForecastRequest(lat, lon, location.getName(),
                targetDate, targetType);
        AtmosphericData baseData = openMeteoService.getAtmosphericData(request, eventTime);
        return forecastService.augmentWithTideData(baseData, location.getId(),
                eventTime, location.getTideType());
    }

    private ModelTestResultEntity buildFailedResult(ModelTestRunEntity testRun,
            RegionEntity region, LocationEntity location, LocalDate targetDate,
            TargetType targetType, EvaluationModel model, long durationMs,
            String errorMessage) {
        return ModelTestResultEntity.builder()
                .testRunId(testRun.getId())
                .regionId(region.getId())
                .regionName(region.getName())
                .locationId(location.getId())
                .locationName(location.getName())
                .targetDate(targetDate)
                .targetType(targetType)
                .evaluationModel(model)
                .durationMs(durationMs)
                .costPence(0)
                .succeeded(false)
                .errorMessage(errorMessage != null && errorMessage.length() > 500
                        ? errorMessage.substring(0, 500) : errorMessage)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private ModelTestResultEntity buildFailedResultFromRef(ModelTestRunEntity testRun,
            ModelTestResultEntity ref, EvaluationModel model, String errorMessage) {
        return ModelTestResultEntity.builder()
                .testRunId(testRun.getId())
                .regionId(ref.getRegionId())
                .regionName(ref.getRegionName())
                .locationId(ref.getLocationId())
                .locationName(ref.getLocationName())
                .targetDate(ref.getTargetDate())
                .targetType(ref.getTargetType())
                .evaluationModel(model)
                .durationMs(0L)
                .costPence(0)
                .succeeded(false)
                .errorMessage(errorMessage != null && errorMessage.length() > 500
                        ? errorMessage.substring(0, 500) : errorMessage)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private void populateAtmosphericData(ModelTestResultEntity result, AtmosphericData data) {
        try {
            result.setAtmosphericDataJson(objectMapper.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialise atmospheric data for {}: {}",
                    result.getLocationName(), e.getMessage());
        }
        result.setLowCloudPercent(data.lowCloudPercent());
        result.setMidCloudPercent(data.midCloudPercent());
        result.setHighCloudPercent(data.highCloudPercent());
        result.setVisibilityMetres(data.visibilityMetres());
        result.setWindSpeedMs(data.windSpeedMs());
        result.setWindDirectionDegrees(data.windDirectionDegrees());
        result.setPrecipitationMm(data.precipitationMm());
        result.setHumidityPercent(data.humidityPercent());
        result.setWeatherCode(data.weatherCode());
        result.setPm25(data.pm25());
        result.setDustUgm3(data.dustUgm3());
        result.setAerosolOpticalDepth(data.aerosolOpticalDepth());
        result.setTemperatureCelsius(data.temperatureCelsius());
        result.setApparentTemperatureCelsius(data.apparentTemperatureCelsius());
        result.setPrecipitationProbability(data.precipitationProbability());
        result.setTideState(data.tideState() != null ? data.tideState().name() : null);
        result.setTideAligned(data.tideAligned());
    }
}
