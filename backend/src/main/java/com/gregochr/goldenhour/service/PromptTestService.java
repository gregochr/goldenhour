package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.PromptTestResultEntity;
import com.gregochr.goldenhour.entity.PromptTestRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.PromptTestResultRepository;
import com.gregochr.goldenhour.repository.PromptTestRunRepository;
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
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates prompt regression tests across all colour locations.
 *
 * <p>Evaluates all enabled colour locations with a single chosen model, stores atmospheric
 * data for replay. Re-running with stored data allows measuring the impact of prompt changes
 * by comparing scores between runs at different git commits.
 */
@Service
public class PromptTestService {

    private static final Logger LOG = LoggerFactory.getLogger(PromptTestService.class);

    private final LocationRepository locationRepository;
    private final PromptTestRunRepository testRunRepository;
    private final PromptTestResultRepository testResultRepository;
    private final OpenMeteoService openMeteoService;
    private final ForecastDataAugmentor augmentor;
    private final EvaluationService evaluationService;
    private final SolarService solarService;
    private final CostCalculator costCalculator;
    private final ExchangeRateService exchangeRateService;
    private final GitInfoService gitInfoService;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a {@code PromptTestService}.
     *
     * @param locationRepository    location repository
     * @param testRunRepository     prompt test run repository
     * @param testResultRepository  prompt test result repository
     * @param openMeteoService      Open-Meteo weather data service
     * @param augmentor             enriches atmospheric data with tide information
     * @param evaluationService     evaluation service (delegates to strategies)
     * @param solarService          solar calculation service
     * @param costCalculator        API cost calculator
     * @param exchangeRateService   exchange rate service for GBP conversion
     * @param gitInfoService        git commit info service
     */
    public PromptTestService(LocationRepository locationRepository,
            PromptTestRunRepository testRunRepository,
            PromptTestResultRepository testResultRepository,
            OpenMeteoService openMeteoService,
            ForecastDataAugmentor augmentor,
            EvaluationService evaluationService,
            SolarService solarService,
            CostCalculator costCalculator,
            ExchangeRateService exchangeRateService,
            GitInfoService gitInfoService) {
        this.locationRepository = locationRepository;
        this.testRunRepository = testRunRepository;
        this.testResultRepository = testResultRepository;
        this.openMeteoService = openMeteoService;
        this.augmentor = augmentor;
        this.evaluationService = evaluationService;
        this.solarService = solarService;
        this.costCalculator = costCalculator;
        this.exchangeRateService = exchangeRateService;
        this.gitInfoService = gitInfoService;
        this.objectMapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    private static final int FORECAST_HORIZON_DAYS = 5;

    /**
     * Creates a prompt test run entity and returns it immediately (for async use).
     *
     * @param model   the evaluation model to use (e.g. HAIKU, SONNET, OPUS)
     * @param runType the run type controlling the date range
     * @return the saved but not-yet-completed test run entity
     */
    public PromptTestRunEntity startRun(EvaluationModel model, RunType runType) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<LocationEntity> allLocations = locationRepository.findAllByEnabledTrueOrderByNameAsc();
        TargetType primaryTargetType = resolveTargetType(now, allLocations);
        List<LocalDate> targetDates = resolveDates(runType);
        Double exchangeRate = fetchExchangeRate();

        return testRunRepository.save(PromptTestRunEntity.builder()
                .startedAt(now)
                .targetDate(targetDates.getFirst())
                .targetType(primaryTargetType)
                .evaluationModel(model)
                .runType(runType)
                .locationsCount(0)
                .succeeded(0)
                .failed(0)
                .totalCostPence(0)
                .exchangeRateGbpPerUsd(exchangeRate)
                .gitCommitHash(gitInfoService.getCommitHash())
                .gitCommitDate(gitInfoService.getCommitDate())
                .gitDirty(gitInfoService.isDirty())
                .gitBranch(gitInfoService.getBranch())
                .build());
    }

    /**
     * Executes the evaluation loop for a prompt test run (intended to be called async).
     *
     * @param runId   the ID of the previously started run
     * @param model   the evaluation model to use
     * @param runType the run type controlling the date range
     */
    public void executeRun(Long runId, EvaluationModel model, RunType runType) {
        try {
            PromptTestRunEntity testRun = testRunRepository.findById(runId)
                    .orElseThrow(() -> new NoSuchElementException(
                            "Prompt test run not found: " + runId));

            LocalDateTime now = testRun.getStartedAt();
            List<LocationEntity> allLocations =
                    locationRepository.findAllByEnabledTrueOrderByNameAsc();
            List<LocationEntity> colourLocations = allLocations.stream()
                    .filter(this::hasColourTypes)
                    .toList();
            List<LocalDate> targetDates = resolveDates(runType);

            LOG.info("Prompt test #{} started — {} colour locations × {} dates"
                            + " (SUNRISE+SUNSET per date), model={}, runType={}",
                    runId, colourLocations.size(), targetDates.size(), model, runType);

            int succeeded = 0;
            int failed = 0;
            int totalCostPence = 0;
            long totalCostMicroDollars = 0;
            int totalSlots = 0;

            for (LocalDate date : targetDates) {
                List<TargetType> targetTypes =
                        resolveTargetTypesForDate(date, now, allLocations);
                for (TargetType targetType : targetTypes) {
                    for (LocationEntity location : colourLocations) {
                        if (!locationSupportsTargetType(location, targetType)) {
                            continue;
                        }
                        totalSlots++;
                        AtmosphericData atmosphericData;
                        try {
                            atmosphericData =
                                    fetchAtmosphericData(location, date, targetType);
                        } catch (Exception e) {
                            LOG.error("Failed to fetch weather data for '{}' on {} {}: {}",
                                    location.getName(), date, targetType,
                                    e.getMessage(), e);
                            failed++;
                            testResultRepository.save(buildFailedResult(testRun, location,
                                    date, targetType, model, e.getMessage()));
                            updateRunProgress(testRun, totalSlots, succeeded, failed);
                            continue;
                        }

                        try {
                            EvaluationDetail detail = evaluationService.evaluateWithDetails(
                                    atmosphericData, model, null);
                            TokenUsage tokenUsage = detail.tokenUsage() != null
                                    ? detail.tokenUsage() : TokenUsage.EMPTY;
                            int costPence = costCalculator.calculateCost(
                                    com.gregochr.goldenhour.entity.ServiceName.ANTHROPIC,
                                    model);
                            long costMicroDollars =
                                    costCalculator.calculateCostMicroDollars(
                                            model, tokenUsage);
                            totalCostPence += costPence;
                            totalCostMicroDollars += costMicroDollars;
                            succeeded++;

                            PromptTestResultEntity result = buildSuccessResult(testRun,
                                    location, date, targetType, model, detail, costPence,
                                    costMicroDollars, tokenUsage);
                            populateAtmosphericData(result, atmosphericData);
                            testResultRepository.save(result);
                        } catch (Exception e) {
                            LOG.error("Evaluation failed for '{}' on {} {} with model {}: {}",
                                    location.getName(), date, targetType, model,
                                    e.getMessage());
                            failed++;
                            testResultRepository.save(buildFailedResult(testRun, location,
                                    date, targetType, model, e.getMessage()));
                        }
                        updateRunProgress(testRun, totalSlots, succeeded, failed);
                    }
                }
            }

            completeRun(testRun, testRun.getStartedAt(), totalSlots, succeeded, failed,
                    totalCostPence, totalCostMicroDollars);
        } catch (NoSuchElementException | IllegalArgumentException e) {
            LOG.error("Prompt test run #{} failed: {}", runId, e.getMessage(), e);
        }
    }

    /**
     * Runs a prompt test synchronously (convenience method for testing).
     *
     * @param model   the evaluation model to use (e.g. HAIKU, SONNET, OPUS)
     * @param runType the run type controlling the date range
     * @return the completed test run with summary metrics
     */
    public PromptTestRunEntity runTest(EvaluationModel model, RunType runType) {
        PromptTestRunEntity run = startRun(model, runType);
        executeRun(run.getId(), model, runType);
        return testRunRepository.findById(run.getId()).orElse(run);
    }

    /**
     * Creates a replay test run entity and returns it immediately (for async use).
     *
     * @param parentRunId the ID of the previous test run to replay
     * @return the saved but not-yet-completed test run entity
     * @throws NoSuchElementException  if the parent run does not exist or has no results
     * @throws IllegalStateException   if the parent run's results lack stored atmospheric data
     */
    public PromptTestRunEntity startReplay(Long parentRunId) {
        PromptTestRunEntity parentRun = testRunRepository.findById(parentRunId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Prompt test run not found: " + parentRunId));

        List<PromptTestResultEntity> parentResults = testResultRepository
                .findByTestRunIdOrderByLocationNameAsc(parentRunId);
        if (parentResults.isEmpty()) {
            throw new NoSuchElementException(
                    "No results found for prompt test run: " + parentRunId);
        }

        // Validate that at least one result has atmospheric data
        boolean hasData = parentResults.stream()
                .anyMatch(r -> r.getSucceeded() && r.getAtmosphericDataJson() != null);
        if (!hasData) {
            throw new IllegalStateException(
                    "No atmospheric data stored on results of prompt test run " + parentRunId
                            + " — cannot replay.");
        }

        EvaluationModel model = parentRun.getEvaluationModel();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Double exchangeRate = fetchExchangeRate();

        return testRunRepository.save(PromptTestRunEntity.builder()
                .startedAt(now)
                .targetDate(parentRun.getTargetDate())
                .targetType(parentRun.getTargetType())
                .evaluationModel(model)
                .locationsCount(0)
                .succeeded(0)
                .failed(0)
                .totalCostPence(0)
                .exchangeRateGbpPerUsd(exchangeRate)
                .parentRunId(parentRunId)
                .gitCommitHash(gitInfoService.getCommitHash())
                .gitCommitDate(gitInfoService.getCommitDate())
                .gitDirty(gitInfoService.isDirty())
                .gitBranch(gitInfoService.getBranch())
                .build());
    }

    /**
     * Executes a replay using stored atmospheric data (intended to be called async).
     *
     * @param runId       the ID of the previously started replay run
     * @param parentRunId the ID of the parent run whose data to replay
     */
    public void executeReplay(Long runId, Long parentRunId) {
        try {
            PromptTestRunEntity testRun = testRunRepository.findById(runId)
                    .orElseThrow(() -> new NoSuchElementException(
                            "Prompt test run not found: " + runId));

            List<PromptTestResultEntity> parentResults = testResultRepository
                    .findByTestRunIdOrderByLocationNameAsc(parentRunId);

            // Extract distinct locations with stored atmospheric data
            Map<Long, PromptTestResultEntity> locationMap = new LinkedHashMap<>();
            for (PromptTestResultEntity r : parentResults) {
                if (r.getSucceeded() && r.getAtmosphericDataJson() != null
                        && !locationMap.containsKey(r.getLocationId())) {
                    locationMap.put(r.getLocationId(), r);
                }
            }

            EvaluationModel model = testRun.getEvaluationModel();

            LOG.info("Replaying prompt test #{} (same data from #{}) — {} locations, model={}",
                    runId, parentRunId, locationMap.size(), model);

            int succeeded = 0;
            int failed = 0;
            int totalCostPence = 0;
            long totalCostMicroDollars = 0;
            int locationsProcessed = 0;

            for (PromptTestResultEntity ref : locationMap.values()) {
                AtmosphericData atmosphericData;
                try {
                    atmosphericData = objectMapper.readValue(
                            ref.getAtmosphericDataJson(), AtmosphericData.class);
                } catch (JsonProcessingException e) {
                    LOG.error("Failed to deserialise atmospheric data for '{}': {}",
                            ref.getLocationName(), e.getMessage());
                    failed++;
                    testResultRepository.save(buildFailedResultFromRef(testRun, ref, model,
                            "Atmospheric data deserialisation failed: " + e.getMessage()));
                    locationsProcessed++;
                    updateRunProgress(testRun, locationsProcessed, succeeded, failed);
                    continue;
                }

                locationsProcessed++;

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

                    PromptTestResultEntity result = PromptTestResultEntity.builder()
                            .testRunId(testRun.getId())
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
                    LOG.error("Evaluation failed for '{}' (replay, model {}): {}",
                            ref.getLocationName(), model, e.getMessage());
                    failed++;
                    testResultRepository.save(buildFailedResultFromRef(testRun, ref, model,
                            e.getMessage()));
                }
                updateRunProgress(testRun, locationsProcessed, succeeded, failed);
            }

            completeRun(testRun, testRun.getStartedAt(), locationsProcessed, succeeded,
                    failed, totalCostPence, totalCostMicroDollars);
        } catch (NoSuchElementException | IllegalStateException e) {
            LOG.error("Prompt test replay #{} failed: {}", runId, e.getMessage(), e);
        }
    }

    /**
     * Replays a previous test synchronously (convenience method for testing).
     *
     * @param parentRunId the ID of the previous test run to replay
     * @return the completed new test run
     */
    public PromptTestRunEntity replayTest(Long parentRunId) {
        PromptTestRunEntity run = startReplay(parentRunId);
        executeReplay(run.getId(), parentRunId);
        return testRunRepository.findById(run.getId()).orElse(run);
    }

    /**
     * Returns a single prompt test run by ID.
     *
     * @param runId the run ID
     * @return the run entity, or empty if not found
     */
    public Optional<PromptTestRunEntity> getRun(Long runId) {
        return testRunRepository.findById(runId);
    }

    /**
     * Returns recent prompt test runs (last 20).
     *
     * @return list of recent test runs ordered by start time descending
     */
    public List<PromptTestRunEntity> getRecentRuns() {
        return testRunRepository.findTop20ByOrderByStartedAtDesc();
    }

    /**
     * Returns results for a specific test run.
     *
     * @param testRunId the test run ID
     * @return results ordered by location name, then date, then target type
     */
    public List<PromptTestResultEntity> getResults(Long testRunId) {
        return testResultRepository
                .findByTestRunIdOrderByLocationNameAscTargetDateAscTargetTypeAsc(testRunId);
    }

    // --- Target resolution ---

    /**
     * Resolves the date range for a given run type.
     *
     * @param runType the run type controlling the date range
     * @return ordered list of target dates
     */
    List<LocalDate> resolveDates(RunType runType) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return switch (runType) {
            case VERY_SHORT_TERM -> List.of(today, today.plusDays(1));
            case SHORT_TERM -> List.of(today, today.plusDays(1), today.plusDays(2));
            case LONG_TERM -> {
                List<LocalDate> dates = new ArrayList<>();
                for (int i = 3; i <= FORECAST_HORIZON_DAYS + 2; i++) {
                    dates.add(today.plusDays(i));
                }
                yield List.copyOf(dates);
            }
            case WEATHER, TIDE -> throw new IllegalArgumentException(
                    "RunType " + runType + " is not supported for prompt tests");
        };
    }

    TargetType resolveTargetType(LocalDateTime now, List<LocationEntity> allLocations) {
        if (allLocations.isEmpty()) {
            return TargetType.SUNSET;
        }
        LocationEntity first = allLocations.get(0);
        LocalDateTime todaySunset = solarService.sunsetUtc(first.getLat(), first.getLon(),
                now.toLocalDate());
        return now.isBefore(todaySunset) ? TargetType.SUNSET : TargetType.SUNRISE;
    }

    LocalDate resolveTargetDate(LocalDateTime now, TargetType targetType) {
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

    // --- Private helpers ---

    private void updateRunProgress(PromptTestRunEntity testRun, int locationsCount,
            int succeeded, int failed) {
        testRun.setLocationsCount(locationsCount);
        testRun.setSucceeded(succeeded);
        testRun.setFailed(failed);
        testRunRepository.save(testRun);
    }

    private PromptTestRunEntity completeRun(PromptTestRunEntity testRun, LocalDateTime startedAt,
            int locationsProcessed, int succeeded, int failed, int totalCostPence,
            long totalCostMicroDollars) {
        LocalDateTime completedAt = LocalDateTime.now(ZoneOffset.UTC);
        testRun.setCompletedAt(completedAt);
        testRun.setDurationMs(java.time.Duration.between(startedAt, completedAt).toMillis());
        testRun.setLocationsCount(locationsProcessed);
        testRun.setSucceeded(succeeded);
        testRun.setFailed(failed);
        testRun.setTotalCostPence(totalCostPence);
        testRun.setTotalCostMicroDollars(totalCostMicroDollars);
        testRun = testRunRepository.save(testRun);

        LOG.info("Prompt test complete — {} locations, {} succeeded, {} failed, {}µ$ cost",
                locationsProcessed, succeeded, failed, totalCostMicroDollars);

        return testRun;
    }

    private Double fetchExchangeRate() {
        try {
            return exchangeRateService.getCurrentRate();
        } catch (Exception e) {
            LOG.warn("Failed to fetch exchange rate for prompt test: {}", e.getMessage());
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
        return augmentor.augmentWithTideData(baseData, location.getId(),
                eventTime, location.getTideType());
    }

    private PromptTestResultEntity buildSuccessResult(PromptTestRunEntity testRun,
            LocationEntity location, LocalDate targetDate, TargetType targetType,
            EvaluationModel model, EvaluationDetail detail, int costPence,
            long costMicroDollars, TokenUsage tokenUsage) {
        return PromptTestResultEntity.builder()
                .testRunId(testRun.getId())
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
    }

    private PromptTestResultEntity buildFailedResult(PromptTestRunEntity testRun,
            LocationEntity location, LocalDate targetDate, TargetType targetType,
            EvaluationModel model, String errorMessage) {
        return PromptTestResultEntity.builder()
                .testRunId(testRun.getId())
                .locationId(location.getId())
                .locationName(location.getName())
                .targetDate(targetDate)
                .targetType(targetType)
                .evaluationModel(model)
                .durationMs(0L)
                .costPence(0)
                .succeeded(false)
                .errorMessage(errorMessage != null && errorMessage.length() > 500
                        ? errorMessage.substring(0, 500) : errorMessage)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private PromptTestResultEntity buildFailedResultFromRef(PromptTestRunEntity testRun,
            PromptTestResultEntity ref, EvaluationModel model, String errorMessage) {
        return PromptTestResultEntity.builder()
                .testRunId(testRun.getId())
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

    private void populateAtmosphericData(PromptTestResultEntity result, AtmosphericData data) {
        try {
            result.setAtmosphericDataJson(objectMapper.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialise atmospheric data for {}: {}",
                    result.getLocationName(), e.getMessage());
        }
        result.setLowCloudPercent(data.cloud().lowCloudPercent());
        result.setMidCloudPercent(data.cloud().midCloudPercent());
        result.setHighCloudPercent(data.cloud().highCloudPercent());
        result.setVisibilityMetres(data.weather().visibilityMetres());
        result.setWindSpeedMs(data.weather().windSpeedMs());
        result.setWindDirectionDegrees(data.weather().windDirectionDegrees());
        result.setPrecipitationMm(data.weather().precipitationMm());
        result.setHumidityPercent(data.weather().humidityPercent());
        result.setWeatherCode(data.weather().weatherCode());
        result.setPm25(data.aerosol().pm25());
        result.setDustUgm3(data.aerosol().dustUgm3());
        result.setAerosolOpticalDepth(data.aerosol().aerosolOpticalDepth());
        result.setTemperatureCelsius(data.comfort().temperatureCelsius());
        result.setApparentTemperatureCelsius(data.comfort().apparentTemperatureCelsius());
        result.setPrecipitationProbability(data.comfort().precipitationProbability());
        var tide = data.tide();
        result.setTideState(tide != null && tide.tideState() != null
                ? tide.tideState().name() : null);
        result.setTideAligned(tide != null ? tide.tideAligned() : null);
    }
}
