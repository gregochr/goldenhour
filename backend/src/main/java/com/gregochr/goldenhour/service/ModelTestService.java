package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.ModelTestResultEntity;
import com.gregochr.goldenhour.entity.ModelTestRunEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.ForecastRequest;
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
import java.util.List;

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
     */
    public ModelTestService(RegionRepository regionRepository,
            LocationRepository locationRepository,
            ModelTestRunRepository testRunRepository,
            ModelTestResultRepository testResultRepository,
            OpenMeteoService openMeteoService,
            ForecastService forecastService,
            EvaluationService evaluationService,
            SolarService solarService,
            CostCalculator costCalculator) {
        this.regionRepository = regionRepository;
        this.locationRepository = locationRepository;
        this.testRunRepository = testRunRepository;
        this.testResultRepository = testResultRepository;
        this.openMeteoService = openMeteoService;
        this.forecastService = forecastService;
        this.evaluationService = evaluationService;
        this.solarService = solarService;
        this.costCalculator = costCalculator;
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
        TargetType targetType = resolveTargetType(now, allLocations);
        LocalDate targetDate = resolveTargetDate(now, targetType, allLocations);

        LOG.info("Model test started — {} regions, target: {} {}", regions.size(), targetDate, targetType);

        ModelTestRunEntity testRun = testRunRepository.save(ModelTestRunEntity.builder()
                .startedAt(now)
                .targetDate(targetDate)
                .targetType(targetType)
                .regionsCount(0)
                .succeeded(0)
                .failed(0)
                .totalCostPence(0)
                .build());

        int succeeded = 0;
        int failed = 0;
        int totalCostPence = 0;
        int regionsProcessed = 0;

        for (RegionEntity region : regions) {
            LocationEntity location = findRepresentativeLocation(allLocations, region);
            if (location == null) {
                LOG.info("Region '{}' has no enabled colour location — skipping", region.getName());
                continue;
            }

            regionsProcessed++;
            LOG.info("Testing region '{}' with location '{}'", region.getName(), location.getName());

            // Fetch atmospheric data once for all three models
            AtmosphericData atmosphericData;
            try {
                atmosphericData = fetchAtmosphericData(location, targetDate, targetType);
            } catch (Exception e) {
                LOG.error("Failed to fetch weather data for {} — skipping region '{}'",
                        location.getName(), region.getName(), e);
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
                    int costPence = costCalculator.calculateCost(
                            com.gregochr.goldenhour.entity.ServiceName.ANTHROPIC, model);
                    totalCostPence += costPence;
                    succeeded++;

                    testResultRepository.save(ModelTestResultEntity.builder()
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
                            .succeeded(true)
                            .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                            .build());
                } catch (Exception e) {
                    LOG.error("Model {} failed for {} in region '{}': {}",
                            model, location.getName(), region.getName(), e.getMessage());
                    failed++;

                    testResultRepository.save(buildFailedResult(testRun, region, location,
                            targetDate, targetType, model, 0, e.getMessage()));
                }
            }
        }

        // Complete the run
        LocalDateTime completedAt = LocalDateTime.now(ZoneOffset.UTC);
        testRun.setCompletedAt(completedAt);
        testRun.setDurationMs(java.time.Duration.between(now, completedAt).toMillis());
        testRun.setRegionsCount(regionsProcessed);
        testRun.setSucceeded(succeeded);
        testRun.setFailed(failed);
        testRun.setTotalCostPence(totalCostPence);
        testRun = testRunRepository.save(testRun);

        LOG.info("Model test complete — {} regions, {} succeeded, {} failed, {}p cost",
                regionsProcessed, succeeded, failed, totalCostPence);

        return testRun;
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
}
