package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.exception.WeatherDataFetchException;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TriageResult;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.notification.EmailNotificationService;
import com.gregochr.goldenhour.service.notification.MacOsToastNotificationService;
import com.gregochr.goldenhour.service.notification.PushoverNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates a full forecast run for a single location and date.
 *
 * <p>For each target type (sunrise and sunset), retrieves the solar event time,
 * fetches Open-Meteo forecast data, requests a Claude evaluation, persists the result,
 * and dispatches notifications.
 */
@Service
public class ForecastService {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastService.class);

    private final SolarService solarService;
    private final OpenMeteoService openMeteoService;
    private final ForecastDataAugmentor augmentor;
    private final EvaluationService evaluationService;
    private final ForecastEvaluationRepository repository;
    private final EmailNotificationService emailService;
    private final PushoverNotificationService pushoverService;
    private final MacOsToastNotificationService toastService;
    private final ApplicationEventPublisher eventPublisher;
    private final WeatherTriageEvaluator weatherTriageEvaluator;

    /**
     * Constructs a {@code ForecastService} with all required dependencies.
     *
     * @param solarService           calculates solar event times
     * @param openMeteoService       retrieves Open-Meteo forecast data
     * @param augmentor              enriches atmospheric data with directional cloud and tide information
     * @param evaluationService      calls Claude to evaluate colour potential
     * @param repository             persists forecast evaluation results
     * @param emailService           email notification channel
     * @param pushoverService        Pushover notification channel
     * @param toastService           macOS toast notification channel
     * @param eventPublisher         publishes location task state transition events
     * @param weatherTriageEvaluator heuristic triage evaluator for skipping unsuitable conditions
     */
    public ForecastService(SolarService solarService, OpenMeteoService openMeteoService,
            ForecastDataAugmentor augmentor, EvaluationService evaluationService,
            ForecastEvaluationRepository repository, EmailNotificationService emailService,
            PushoverNotificationService pushoverService, MacOsToastNotificationService toastService,
            ApplicationEventPublisher eventPublisher, WeatherTriageEvaluator weatherTriageEvaluator) {
        this.solarService = solarService;
        this.openMeteoService = openMeteoService;
        this.augmentor = augmentor;
        this.evaluationService = evaluationService;
        this.repository = repository;
        this.emailService = emailService;
        this.pushoverService = pushoverService;
        this.toastService = toastService;
        this.eventPublisher = eventPublisher;
        this.weatherTriageEvaluator = weatherTriageEvaluator;
    }

    /**
     * Runs forecasts for the given location and date, optionally limited to a single target type.
     *
     * <p>Persists one {@link ForecastEvaluationEntity} per evaluated target type and sends
     * notifications via all enabled channels.
     *
     * @param location   the location entity
     * @param date       the calendar date to forecast
     * @param targetType the target type to evaluate, or {@code null} to evaluate both
     * @param tideTypes  tide preferences for this location (empty if inland)
     * @param model      which Claude model to use for evaluation
     * @param jobRun     the parent job run for metrics tracking, or {@code null} if called from controller
     * @return the saved entities in evaluation order
     */
    @ConcurrencyLimit(8)
    public List<ForecastEvaluationEntity> runForecasts(LocationEntity location,
            LocalDate date, TargetType targetType, Set<TideType> tideTypes,
            EvaluationModel model, JobRunEntity jobRun) {
        String locationName = location.getName();
        double lat = location.getLat();
        double lon = location.getLon();
        Long locationId = location.getId();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int daysAhead = (int) ChronoUnit.DAYS.between(today, date);

        if (model == EvaluationModel.WILDLIFE) {
            return runWildlifeHourly(location, date, daysAhead, tideTypes, jobRun);
        }

        List<ForecastEvaluationEntity> results = new ArrayList<>();
        List<TargetType> types = (targetType != null)
                ? List.of(targetType)
                : List.of(TargetType.SUNRISE, TargetType.SUNSET);

        for (TargetType type : types) {
            String taskKey = locationName + "|" + date + "|" + type;
            Long runId = jobRun != null ? jobRun.getId() : null;

            LocalDateTime eventTime = type == TargetType.SUNRISE
                    ? solarService.sunriseUtc(lat, lon, date)
                    : solarService.sunsetUtc(lat, lon, date);

            ForecastRequest request = new ForecastRequest(lat, lon, locationName, date, type);

            // Fetch weather data with explicit error handling
            publishEvent(runId, taskKey, locationName, date.toString(), type.name(),
                    LocationTaskState.FETCHING_WEATHER);
            AtmosphericData baseData;
            try {
                baseData = openMeteoService.getAtmosphericData(request, eventTime, jobRun);
            } catch (Exception e) {
                String msg = "Weather data fetch failed for " + locationName + " " + type + ": " + e.getMessage();
                LOG.error(msg);
                publishEvent(runId, taskKey, locationName, date.toString(), type.name(),
                        LocationTaskState.FAILED, msg, "FETCHING_WEATHER");
                throw new WeatherDataFetchException(msg, locationName, type.name(), e);
            }

            // Validate weather data was successfully retrieved (null can be returned without exception)
            if (baseData == null) {
                String msg = "Weather service returned null for " + locationName + " " + type;
                LOG.error(msg);
                publishEvent(runId, taskKey, locationName, date.toString(), type.name(),
                        LocationTaskState.FAILED, msg, "FETCHING_WEATHER");
                throw new WeatherDataFetchException(msg, locationName, type.name(), null);
            }

            int azimuth = type == TargetType.SUNRISE
                    ? solarService.sunriseAzimuthDeg(lat, lon, date)
                    : solarService.sunsetAzimuthDeg(lat, lon, date);

            publishEvent(runId, taskKey, locationName, date.toString(), type.name(),
                    LocationTaskState.FETCHING_CLOUD);
            AtmosphericData withDirectional = augmentor.augmentWithDirectionalCloud(
                    baseData, lat, lon, azimuth, eventTime, jobRun);
            AtmosphericData withApproach = augmentor.augmentWithCloudApproach(
                    withDirectional, lat, lon, azimuth, eventTime,
                    LocalDateTime.now(ZoneOffset.UTC), jobRun);

            publishEvent(runId, taskKey, locationName, date.toString(), type.name(),
                    LocationTaskState.FETCHING_TIDES);
            AtmosphericData forecastData = augmentor.augmentWithTideData(
                    withApproach, locationId, eventTime, tideTypes);

            publishEvent(runId, taskKey, locationName, date.toString(), type.name(),
                    LocationTaskState.EVALUATING);
            SunsetEvaluation evaluation = evaluationService.evaluate(forecastData, model, jobRun);

            ForecastEvaluationEntity entity = buildEntity(
                    location, lat, lon, date, type, daysAhead, eventTime, azimuth,
                    forecastData, evaluation, model);

            results.add(repository.save(entity));
            publishEvent(runId, taskKey, locationName, date.toString(), type.name(),
                    LocationTaskState.COMPLETE);
            if (evaluation.rating() != null) {
                LOG.info("Forecast saved: {} {} {} (T+{}) [{}] — rating={}/5",
                        locationName, type, date, daysAhead, model, evaluation.rating());
            } else {
                LOG.info("Forecast saved: {} {} {} (T+{}) [{}] — fiery={}/100 golden={}/100",
                        locationName, type, date, daysAhead, model,
                        evaluation.fierySkyPotential(), evaluation.goldenHourPotential());
            }
            try {
                emailService.notify(evaluation, locationName, type, date);
                pushoverService.notify(evaluation, locationName, type, date);
                toastService.notify(evaluation, locationName, type, date);
            } catch (Exception e) {
                LOG.warn("Notification failed for {} {} {} — forecast was saved successfully: {}",
                        locationName, type, date, e.getMessage());
            }
        }
        return results;
    }

    /**
     * Fetches weather data and applies heuristic triage for a single location/date/targetType.
     *
     * <p>If triage determines conditions are unsuitable, a canned entity (rating=1) is persisted
     * and the result is marked as triaged. Otherwise, the atmospheric data is returned ready for
     * Claude evaluation.
     *
     * @param location   the location entity
     * @param date       the forecast date
     * @param targetType SUNRISE or SUNSET
     * @param tideTypes  tide preferences
     * @param model      evaluation model
     * @param jobRun     parent job run for metrics
     * @return the pre-evaluation result
     */
    @ConcurrencyLimit(8)
    public ForecastPreEvalResult fetchWeatherAndTriage(LocationEntity location,
            LocalDate date, TargetType targetType, Set<TideType> tideTypes,
            EvaluationModel model, JobRunEntity jobRun) {
        String locationName = location.getName();
        double lat = location.getLat();
        double lon = location.getLon();
        Long locationId = location.getId();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int daysAhead = (int) ChronoUnit.DAYS.between(today, date);
        String taskKey = locationName + "|" + date + "|" + targetType;
        Long runId = jobRun != null ? jobRun.getId() : null;

        LocalDateTime eventTime = targetType == TargetType.SUNRISE
                ? solarService.sunriseUtc(lat, lon, date)
                : solarService.sunsetUtc(lat, lon, date);

        ForecastRequest request = new ForecastRequest(lat, lon, locationName, date, targetType);

        // Fetch weather data
        publishEvent(runId, taskKey, locationName, date.toString(), targetType.name(),
                LocationTaskState.FETCHING_WEATHER);
        AtmosphericData baseData;
        try {
            baseData = openMeteoService.getAtmosphericData(request, eventTime, jobRun);
        } catch (Exception e) {
            String msg = "Weather data fetch failed for " + locationName + " " + targetType
                    + ": " + e.getMessage();
            LOG.error(msg);
            publishEvent(runId, taskKey, locationName, date.toString(), targetType.name(),
                    LocationTaskState.FAILED, msg, "FETCHING_WEATHER");
            throw new WeatherDataFetchException(msg, locationName, targetType.name(), e);
        }

        if (baseData == null) {
            String msg = "Weather service returned null for " + locationName + " " + targetType;
            LOG.error(msg);
            publishEvent(runId, taskKey, locationName, date.toString(), targetType.name(),
                    LocationTaskState.FAILED, msg, "FETCHING_WEATHER");
            throw new WeatherDataFetchException(msg, locationName, targetType.name(), null);
        }

        int azimuth = targetType == TargetType.SUNRISE
                ? solarService.sunriseAzimuthDeg(lat, lon, date)
                : solarService.sunsetAzimuthDeg(lat, lon, date);

        // Augment with directional cloud and cloud approach
        publishEvent(runId, taskKey, locationName, date.toString(), targetType.name(),
                LocationTaskState.FETCHING_CLOUD);
        AtmosphericData withDirectional = augmentor.augmentWithDirectionalCloud(
                baseData, lat, lon, azimuth, eventTime, jobRun);
        AtmosphericData withApproach = augmentor.augmentWithCloudApproach(
                withDirectional, lat, lon, azimuth, eventTime,
                LocalDateTime.now(ZoneOffset.UTC), jobRun);

        // Augment with tide data
        publishEvent(runId, taskKey, locationName, date.toString(), targetType.name(),
                LocationTaskState.FETCHING_TIDES);
        AtmosphericData forecastData = augmentor.augmentWithTideData(
                withApproach, locationId, eventTime, tideTypes);

        // Apply triage heuristic
        Optional<TriageResult> triageResult = weatherTriageEvaluator.evaluate(forecastData);
        if (triageResult.isPresent()) {
            String reason = triageResult.get().reason();
            SunsetEvaluation cannedEval = new SunsetEvaluation(
                    1, 5, 5, "Conditions unsuitable — " + reason);
            ForecastEvaluationEntity entity = buildEntity(
                    location, lat, lon, date, targetType, daysAhead, eventTime, azimuth,
                    forecastData, cannedEval, model);
            repository.save(entity);
            publishEvent(runId, taskKey, locationName, date.toString(), targetType.name(),
                    LocationTaskState.TRIAGED);
            LOG.info("Forecast triaged: {} {} {} (T+{}) — {}", locationName, targetType,
                    date, daysAhead, reason);
            return new ForecastPreEvalResult(true, reason, forecastData, location, date,
                    targetType, eventTime, azimuth, daysAhead, model, tideTypes, taskKey);
        }

        return new ForecastPreEvalResult(false, null, forecastData, location, date,
                targetType, eventTime, azimuth, daysAhead, model, tideTypes, taskKey);
    }

    /**
     * Evaluates atmospheric data with Claude and persists the result.
     *
     * @param preEval the pre-evaluation result from the triage phase
     * @param jobRun  parent job run for metrics
     * @return the saved evaluation entity
     */
    public ForecastEvaluationEntity evaluateAndPersist(ForecastPreEvalResult preEval,
            JobRunEntity jobRun) {
        Long runId = jobRun != null ? jobRun.getId() : null;

        publishEvent(runId, preEval.taskKey(), preEval.location().getName(),
                preEval.date().toString(), preEval.targetType().name(),
                LocationTaskState.EVALUATING);
        SunsetEvaluation evaluation = evaluationService.evaluate(
                preEval.atmosphericData(), preEval.model(), jobRun);

        ForecastEvaluationEntity entity = buildEntity(
                preEval.location(), preEval.location().getLat(), preEval.location().getLon(),
                preEval.date(), preEval.targetType(), preEval.daysAhead(), preEval.eventTime(),
                preEval.azimuth(), preEval.atmosphericData(), evaluation, preEval.model());

        ForecastEvaluationEntity saved = repository.save(entity);
        publishEvent(runId, preEval.taskKey(), preEval.location().getName(),
                preEval.date().toString(), preEval.targetType().name(),
                LocationTaskState.COMPLETE);

        String locationName = preEval.location().getName();
        if (evaluation.rating() != null) {
            LOG.info("Forecast saved: {} {} {} (T+{}) [{}] — rating={}/5",
                    locationName, preEval.targetType(), preEval.date(), preEval.daysAhead(),
                    preEval.model(), evaluation.rating());
        } else {
            LOG.info("Forecast saved: {} {} {} (T+{}) [{}] — fiery={}/100 golden={}/100",
                    locationName, preEval.targetType(), preEval.date(), preEval.daysAhead(),
                    preEval.model(), evaluation.fierySkyPotential(),
                    evaluation.goldenHourPotential());
        }

        try {
            emailService.notify(evaluation, locationName, preEval.targetType(), preEval.date());
            pushoverService.notify(evaluation, locationName, preEval.targetType(), preEval.date());
            toastService.notify(evaluation, locationName, preEval.targetType(), preEval.date());
        } catch (Exception e) {
            LOG.warn("Notification failed for {} {} {} — forecast was saved successfully: {}",
                    locationName, preEval.targetType(), preEval.date(), e.getMessage());
        }

        return saved;
    }

    /**
     * Persists a canned result for a task skipped by sentinel sampling.
     *
     * @param preEval the pre-evaluation result with atmospheric data
     * @param reason  human-readable reason for the skip
     * @param jobRun  parent job run for metrics
     * @return the saved canned entity
     */
    public ForecastEvaluationEntity persistCannedResult(ForecastPreEvalResult preEval,
            String reason, JobRunEntity jobRun) {
        SunsetEvaluation cannedEval = new SunsetEvaluation(
                1, 5, 5, "Conditions unsuitable — " + reason);
        ForecastEvaluationEntity entity = buildEntity(
                preEval.location(), preEval.location().getLat(), preEval.location().getLon(),
                preEval.date(), preEval.targetType(), preEval.daysAhead(), preEval.eventTime(),
                preEval.azimuth(), preEval.atmosphericData(), cannedEval, preEval.model());
        ForecastEvaluationEntity saved = repository.save(entity);

        Long runId = jobRun != null ? jobRun.getId() : null;
        publishEvent(runId, preEval.taskKey(), preEval.location().getName(),
                preEval.date().toString(), preEval.targetType().name(),
                LocationTaskState.SKIPPED);
        LOG.info("Forecast sentinel-skipped: {} {} {} — {}", preEval.location().getName(),
                preEval.targetType(), preEval.date(), reason);
        return saved;
    }

    /**
     * Runs comfort-only hourly forecasts for a WILDLIFE location, covering every full UTC
     * hour between sunrise and sunset on the given date.
     *
     * <p>Makes a single Open-Meteo API call for the day and extracts data for each slot.
     * No Claude evaluation is performed. Notifications are not sent for WILDLIFE rows.
     *
     * @param location   the location entity
     * @param date       the calendar date to forecast
     * @param daysAhead  number of days from today to {@code date}
     * @param tideTypes  tide preferences for the location (empty if inland)
     * @param jobRun     the parent job run for metrics tracking, or {@code null}
     * @return the saved entities, one per full UTC hour from sunrise to sunset
     */
    private List<ForecastEvaluationEntity> runWildlifeHourly(LocationEntity location,
            LocalDate date, int daysAhead, Set<TideType> tideTypes, JobRunEntity jobRun) {
        String locationName = location.getName();
        double lat = location.getLat();
        double lon = location.getLon();
        Long locationId = location.getId();

        LocalDateTime sunriseTime = solarService.sunriseUtc(lat, lon, date);
        LocalDateTime sunsetTime = solarService.sunsetUtc(lat, lon, date);

        ForecastRequest request = new ForecastRequest(lat, lon, locationName, date, TargetType.SUNRISE);
        List<AtmosphericData> hourlyData =
                openMeteoService.getHourlyAtmosphericData(request, sunriseTime, sunsetTime, jobRun);

        List<ForecastEvaluationEntity> results = new ArrayList<>();
        SunsetEvaluation noEval = new SunsetEvaluation(null, null, null, null);

        for (AtmosphericData baseData : hourlyData) {
            AtmosphericData forecastData = augmentor.augmentWithTideData(
                    baseData, locationId, baseData.solarEventTime(), tideTypes);
            ForecastEvaluationEntity entity = buildEntity(
                    location, lat, lon, date, TargetType.HOURLY, daysAhead,
                    forecastData.solarEventTime(), null,
                    forecastData, noEval, EvaluationModel.WILDLIFE);
            results.add(repository.save(entity));
        }
        LOG.info("Forecast saved (WILDLIFE hourly comfort): {} {} (T+{}) — {} slot(s)",
                locationName, date, daysAhead, results.size());
        return results;
    }

    /**
     * Builds a {@link ForecastEvaluationEntity} from the evaluated forecast data.
     *
     * @param location   the location entity
     * @param lat        latitude in decimal degrees
     * @param lon        longitude in decimal degrees
     * @param date       the calendar date of the forecast
     * @param type       sunrise or sunset
     * @param daysAhead  number of days from today to {@code date}
     * @param eventTime  UTC time of the solar event
     * @param azimuth    solar azimuth in degrees
     * @param data       atmospheric data (with tide fields if coastal)
     * @param evaluation Claude's rating and summary
     * @param model      which Claude model produced the evaluation
     * @return the unsaved entity
     */
    private void publishEvent(Long runId, String taskKey, String locationName,
            String targetDate, String targetType, LocationTaskState state) {
        publishEvent(runId, taskKey, locationName, targetDate, targetType, state, null, null);
    }

    private void publishEvent(Long runId, String taskKey, String locationName,
            String targetDate, String targetType, LocationTaskState state,
            String errorMessage, String failedStep) {
        if (runId == null) {
            return;
        }
        eventPublisher.publishEvent(new LocationTaskEvent(
                this, runId, taskKey, locationName, targetDate, targetType,
                state, errorMessage, failedStep));
    }

    private ForecastEvaluationEntity buildEntity(LocationEntity location, double lat, double lon,
            LocalDate date, TargetType type, int daysAhead, LocalDateTime eventTime, Integer azimuth,
            AtmosphericData data, SunsetEvaluation evaluation, EvaluationModel model) {
        var dc = data.directionalCloud();
        var tide = data.tide();
        var ca = data.cloudApproach();
        return ForecastEvaluationEntity.builder()
                .locationLat(BigDecimal.valueOf(lat))
                .locationLon(BigDecimal.valueOf(lon))
                .location(location)
                .targetDate(date)
                .targetType(type)
                .forecastRunAt(LocalDateTime.now(ZoneOffset.UTC))
                .daysAhead(daysAhead)
                .lowCloud(data.cloud().lowCloudPercent())
                .midCloud(data.cloud().midCloudPercent())
                .highCloud(data.cloud().highCloudPercent())
                .visibility(data.weather().visibilityMetres())
                .windSpeed(data.weather().windSpeedMs())
                .windDirection(data.weather().windDirectionDegrees())
                .precipitation(data.weather().precipitationMm())
                .humidity(data.weather().humidityPercent())
                .weatherCode(data.weather().weatherCode())
                .boundaryLayerHeight(data.aerosol().boundaryLayerHeightMetres())
                .shortwaveRadiation(data.weather().shortwaveRadiationWm2())
                .pm25(data.aerosol().pm25())
                .dust(data.aerosol().dustUgm3())
                .aerosolOpticalDepth(data.aerosol().aerosolOpticalDepth())
                .temperatureCelsius(data.comfort().temperatureCelsius())
                .apparentTemperatureCelsius(data.comfort().apparentTemperatureCelsius())
                .precipitationProbabilityPercent(data.comfort().precipitationProbability())
                .tideState(tide != null ? tide.tideState() : null)
                .nextHighTideTime(tide != null ? tide.nextHighTideTime() : null)
                .nextHighTideHeightMetres(tide != null ? tide.nextHighTideHeightMetres() : null)
                .nextLowTideTime(tide != null ? tide.nextLowTideTime() : null)
                .nextLowTideHeightMetres(tide != null ? tide.nextLowTideHeightMetres() : null)
                .tideAligned(tide != null ? tide.tideAligned() : null)
                .solarLowCloud(dc != null ? dc.solarLowCloudPercent() : null)
                .solarMidCloud(dc != null ? dc.solarMidCloudPercent() : null)
                .solarHighCloud(dc != null ? dc.solarHighCloudPercent() : null)
                .antisolarLowCloud(dc != null ? dc.antisolarLowCloudPercent() : null)
                .antisolarMidCloud(dc != null ? dc.antisolarMidCloudPercent() : null)
                .antisolarHighCloud(dc != null ? dc.antisolarHighCloudPercent() : null)
                .evaluationModel(model)
                .rating(evaluation.rating())
                .fierySkyPotential(evaluation.fierySkyPotential())
                .goldenHourPotential(evaluation.goldenHourPotential())
                .summary(evaluation.summary())
                .basicFierySkyPotential(evaluation.basicFierySkyPotential())
                .basicGoldenHourPotential(evaluation.basicGoldenHourPotential())
                .basicSummary(evaluation.basicSummary())
                .solarEventTime(eventTime)
                .azimuthDeg(azimuth)
                .solarTrendEventLowCloud(ca != null && ca.solarTrend() != null
                        && !ca.solarTrend().slots().isEmpty()
                        ? ca.solarTrend().slots().getLast().lowCloudPercent() : null)
                .solarTrendEarliestLowCloud(ca != null && ca.solarTrend() != null
                        && !ca.solarTrend().slots().isEmpty()
                        ? ca.solarTrend().slots().getFirst().lowCloudPercent() : null)
                .solarTrendBuilding(ca != null && ca.solarTrend() != null
                        ? ca.solarTrend().isBuilding() : null)
                .upwindCurrentLowCloud(ca != null && ca.upwindSample() != null
                        ? ca.upwindSample().currentLowCloudPercent() : null)
                .upwindEventLowCloud(ca != null && ca.upwindSample() != null
                        ? ca.upwindSample().eventLowCloudPercent() : null)
                .upwindDistanceKm(ca != null && ca.upwindSample() != null
                        ? ca.upwindSample().distanceKm() : null)
                .build();
    }
}
