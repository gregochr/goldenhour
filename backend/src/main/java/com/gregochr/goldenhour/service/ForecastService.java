package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.exception.WeatherDataFetchException;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.notification.EmailNotificationService;
import com.gregochr.goldenhour.service.notification.MacOsToastNotificationService;
import com.gregochr.goldenhour.service.notification.PushoverNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * Constructs a {@code ForecastService} with all required dependencies.
     *
     * @param solarService      calculates solar event times
     * @param openMeteoService  retrieves Open-Meteo forecast data
     * @param augmentor         enriches atmospheric data with directional cloud and tide information
     * @param evaluationService calls Claude to evaluate colour potential
     * @param repository        persists forecast evaluation results
     * @param emailService      email notification channel
     * @param pushoverService   Pushover notification channel
     * @param toastService      macOS toast notification channel
     */
    public ForecastService(SolarService solarService, OpenMeteoService openMeteoService,
            ForecastDataAugmentor augmentor, EvaluationService evaluationService,
            ForecastEvaluationRepository repository, EmailNotificationService emailService,
            PushoverNotificationService pushoverService, MacOsToastNotificationService toastService) {
        this.solarService = solarService;
        this.openMeteoService = openMeteoService;
        this.augmentor = augmentor;
        this.evaluationService = evaluationService;
        this.repository = repository;
        this.emailService = emailService;
        this.pushoverService = pushoverService;
        this.toastService = toastService;
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
            LocalDateTime eventTime = type == TargetType.SUNRISE
                    ? solarService.sunriseUtc(lat, lon, date)
                    : solarService.sunsetUtc(lat, lon, date);

            ForecastRequest request = new ForecastRequest(lat, lon, locationName, date, type);

            // Fetch weather data with explicit error handling
            AtmosphericData baseData;
            try {
                baseData = openMeteoService.getAtmosphericData(request, eventTime, jobRun);
            } catch (Exception e) {
                String msg = "Weather data fetch failed for " + locationName + " " + type + ": " + e.getMessage();
                LOG.error(msg);
                throw new WeatherDataFetchException(msg, locationName, type.name(), e);
            }

            // Validate weather data was successfully retrieved (null can be returned without exception)
            if (baseData == null) {
                String msg = "Weather service returned null for " + locationName + " " + type;
                LOG.error(msg);
                throw new WeatherDataFetchException(msg, locationName, type.name(), null);
            }

            int azimuth = type == TargetType.SUNRISE
                    ? solarService.sunriseAzimuthDeg(lat, lon, date)
                    : solarService.sunsetAzimuthDeg(lat, lon, date);

            AtmosphericData withDirectional = augmentor.augmentWithDirectionalCloud(
                    baseData, lat, lon, azimuth, eventTime, jobRun);
            AtmosphericData forecastData = augmentor.augmentWithTideData(
                    withDirectional, locationId, eventTime, tideTypes);

            SunsetEvaluation evaluation = evaluationService.evaluate(forecastData, model, jobRun);

            ForecastEvaluationEntity entity = buildEntity(
                    location, lat, lon, date, type, daysAhead, eventTime, azimuth,
                    forecastData, evaluation, model);

            results.add(repository.save(entity));
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
    private ForecastEvaluationEntity buildEntity(LocationEntity location, double lat, double lon,
            LocalDate date, TargetType type, int daysAhead, LocalDateTime eventTime, Integer azimuth,
            AtmosphericData data, SunsetEvaluation evaluation, EvaluationModel model) {
        var dc = data.directionalCloud();
        var tide = data.tide();
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
                .build();
    }
}
