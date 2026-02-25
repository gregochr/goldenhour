package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TideData;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.notification.EmailNotificationService;
import com.gregochr.goldenhour.service.notification.MacOsToastNotificationService;
import com.gregochr.goldenhour.service.notification.PushoverNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

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
    private final TideService tideService;
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
     * @param tideService       fetches tide data for coastal locations
     * @param evaluationService calls Claude to evaluate colour potential
     * @param repository        persists forecast evaluation results
     * @param emailService      email notification channel
     * @param pushoverService   Pushover notification channel
     * @param toastService      macOS toast notification channel
     */
    public ForecastService(SolarService solarService, OpenMeteoService openMeteoService,
            TideService tideService, EvaluationService evaluationService,
            ForecastEvaluationRepository repository, EmailNotificationService emailService,
            PushoverNotificationService pushoverService, MacOsToastNotificationService toastService) {
        this.solarService = solarService;
        this.openMeteoService = openMeteoService;
        this.tideService = tideService;
        this.evaluationService = evaluationService;
        this.repository = repository;
        this.emailService = emailService;
        this.pushoverService = pushoverService;
        this.toastService = toastService;
    }

    /**
     * Runs sunrise and sunset forecasts for the given location and date using Sonnet.
     *
     * <p>Persists one {@link ForecastEvaluationEntity} per target type and sends
     * notifications via all enabled channels.
     *
     * @param locationName human-readable location name
     * @param lat          latitude in decimal degrees
     * @param lon          longitude in decimal degrees
     * @param date         the calendar date to forecast
     * @return the saved entities (sunrise first, then sunset)
     */
    public List<ForecastEvaluationEntity> runForecasts(String locationName, double lat, double lon,
            LocalDate date) {
        return runForecasts(locationName, lat, lon, null, date, null, java.util.Set.of(),
                EvaluationModel.SONNET);
    }

    /**
     * Runs forecasts for the given location and date, optionally limited to a single target type.
     *
     * <p>Persists one {@link ForecastEvaluationEntity} per evaluated target type and sends
     * notifications via all enabled channels.
     *
     * @param locationName human-readable location name
     * @param lat          latitude in decimal degrees
     * @param lon          longitude in decimal degrees
     * @param locationId   the location primary key (for tide DB lookup), or {@code null} if not coastal
     * @param date         the calendar date to forecast
     * @param targetType   the target type to evaluate, or {@code null} to evaluate both
     * @param tideTypes    tide preferences for this location (empty if inland)
     * @param model        which Claude model to use for evaluation
     * @return the saved entities in evaluation order
     */
    public List<ForecastEvaluationEntity> runForecasts(String locationName, double lat, double lon,
            Long locationId, LocalDate date, TargetType targetType, java.util.Set<TideType> tideTypes,
            EvaluationModel model) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int daysAhead = (int) ChronoUnit.DAYS.between(today, date);

        if (model == EvaluationModel.WILDLIFE) {
            // No Claude call — run hourly comfort rows from sunrise to sunset
            return runWildlifeHourly(locationName, lat, lon, locationId, date, daysAhead, tideTypes);
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
            AtmosphericData baseData = openMeteoService.getAtmosphericData(request, eventTime);
            AtmosphericData forecastData = augmentWithTideData(baseData, locationId, eventTime, tideTypes);

            int azimuth = type == TargetType.SUNRISE
                    ? solarService.sunriseAzimuthDeg(lat, lon, date)
                    : solarService.sunsetAzimuthDeg(lat, lon, date);

            SunsetEvaluation evaluation = evaluationService.evaluate(forecastData, model);

            ForecastEvaluationEntity entity = buildEntity(
                    locationName, lat, lon, date, type, daysAhead, eventTime, azimuth,
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
            emailService.notify(evaluation, locationName, type, date);
            pushoverService.notify(evaluation, locationName, type, date);
            toastService.notify(evaluation, locationName, type, date);
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
     * @param locationName human-readable location name
     * @param lat          latitude in decimal degrees
     * @param lon          longitude in decimal degrees
     * @param locationId   location primary key for tide lookup, or {@code null}
     * @param date         the calendar date to forecast
     * @param daysAhead    number of days from today to {@code date}
     * @param tideTypes    tide preferences for the location (empty if inland)
     * @return the saved entities, one per full UTC hour from sunrise to sunset
     */
    private List<ForecastEvaluationEntity> runWildlifeHourly(String locationName, double lat, double lon,
            Long locationId, LocalDate date, int daysAhead, java.util.Set<TideType> tideTypes) {
        LocalDateTime sunriseTime = solarService.sunriseUtc(lat, lon, date);
        LocalDateTime sunsetTime = solarService.sunsetUtc(lat, lon, date);

        ForecastRequest request = new ForecastRequest(lat, lon, locationName, date, TargetType.SUNRISE);
        List<AtmosphericData> hourlyData =
                openMeteoService.getHourlyAtmosphericData(request, sunriseTime, sunsetTime);

        List<ForecastEvaluationEntity> results = new ArrayList<>();
        SunsetEvaluation noEval = new SunsetEvaluation(null, null, null, null);

        for (AtmosphericData baseData : hourlyData) {
            AtmosphericData forecastData = augmentWithTideData(
                    baseData, locationId, baseData.solarEventTime(), tideTypes);
            ForecastEvaluationEntity entity = buildEntity(
                    locationName, lat, lon, date, TargetType.HOURLY, daysAhead,
                    forecastData.solarEventTime(), null,
                    forecastData, noEval, EvaluationModel.WILDLIFE);
            results.add(repository.save(entity));
        }
        LOG.info("Forecast saved (WILDLIFE hourly comfort): {} {} (T+{}) — {} slot(s)",
                locationName, date, daysAhead, results.size());
        return results;
    }

    /**
     * Returns a copy of {@code base} augmented with tide fields for coastal locations.
     *
     * <p>If the location is not coastal (empty or NOT_COASTAL tide types), the tide
     * fields in the returned record are {@code null} and the original data is otherwise
     * unchanged.
     *
     * @param base        atmospheric data without tide fields
     * @param locationId  the location primary key used for DB tide lookup, or {@code null} if inland
     * @param eventTime   UTC time of the solar event
     * @param tideTypes   tide preferences for this location (empty if inland)
     * @return a new {@link AtmosphericData} with tide fields populated where applicable
     */
    private AtmosphericData augmentWithTideData(AtmosphericData base, Long locationId,
            LocalDateTime eventTime, java.util.Set<TideType> tideTypes) {
        TideData tideData = null;
        Boolean tideAligned = null;
        boolean isCoastal = locationId != null && tideTypes != null && !tideTypes.isEmpty()
                && !tideTypes.equals(java.util.Set.of(TideType.NOT_COASTAL));
        if (isCoastal) {
            var tideMaybe = tideService.deriveTideData(locationId, eventTime);
            if (tideMaybe.isPresent()) {
                tideData = tideMaybe.get();
                tideAligned = tideService.calculateTideAligned(tideData, tideTypes);
            }
        }
        return new AtmosphericData(
                base.locationName(),
                base.solarEventTime(),
                base.targetType(),
                base.lowCloudPercent(),
                base.midCloudPercent(),
                base.highCloudPercent(),
                base.visibilityMetres(),
                base.windSpeedMs(),
                base.windDirectionDegrees(),
                base.precipitationMm(),
                base.humidityPercent(),
                base.weatherCode(),
                base.boundaryLayerHeightMetres(),
                base.shortwaveRadiationWm2(),
                base.pm25(),
                base.dustUgm3(),
                base.aerosolOpticalDepth(),
                base.temperatureCelsius(),
                base.apparentTemperatureCelsius(),
                base.precipitationProbability(),
                tideData != null ? tideData.tideState() : null,
                tideData != null ? tideData.nextHighTideTime() : null,
                tideData != null ? tideData.nextHighTideHeightMetres() : null,
                tideData != null ? tideData.nextLowTideTime() : null,
                tideData != null ? tideData.nextLowTideHeightMetres() : null,
                tideAligned);
    }

    /**
     * Builds a {@link ForecastEvaluationEntity} from the evaluated forecast data.
     *
     * @param locationName human-readable location name
     * @param lat          latitude in decimal degrees
     * @param lon          longitude in decimal degrees
     * @param date         the calendar date of the forecast
     * @param type         sunrise or sunset
     * @param daysAhead    number of days from today to {@code date}
     * @param eventTime    UTC time of the solar event
     * @param azimuth      solar azimuth in degrees
     * @param data         atmospheric data (with tide fields if coastal)
     * @param evaluation   Claude's rating and summary
     * @param model        which Claude model produced the evaluation
     * @return the unsaved entity
     */
    private ForecastEvaluationEntity buildEntity(String locationName, double lat, double lon,
            LocalDate date, TargetType type, int daysAhead, LocalDateTime eventTime, Integer azimuth,
            AtmosphericData data, SunsetEvaluation evaluation, EvaluationModel model) {
        return ForecastEvaluationEntity.builder()
                .locationLat(BigDecimal.valueOf(lat))
                .locationLon(BigDecimal.valueOf(lon))
                .locationName(locationName)
                .targetDate(date)
                .targetType(type)
                .forecastRunAt(LocalDateTime.now(ZoneOffset.UTC))
                .daysAhead(daysAhead)
                .lowCloud(data.lowCloudPercent())
                .midCloud(data.midCloudPercent())
                .highCloud(data.highCloudPercent())
                .visibility(data.visibilityMetres())
                .windSpeed(data.windSpeedMs())
                .windDirection(data.windDirectionDegrees())
                .precipitation(data.precipitationMm())
                .humidity(data.humidityPercent())
                .weatherCode(data.weatherCode())
                .boundaryLayerHeight(data.boundaryLayerHeightMetres())
                .shortwaveRadiation(data.shortwaveRadiationWm2())
                .pm25(data.pm25())
                .dust(data.dustUgm3())
                .aerosolOpticalDepth(data.aerosolOpticalDepth())
                .temperatureCelsius(data.temperatureCelsius())
                .apparentTemperatureCelsius(data.apparentTemperatureCelsius())
                .precipitationProbabilityPercent(data.precipitationProbability())
                .tideState(data.tideState())
                .nextHighTideTime(data.nextHighTideTime())
                .nextHighTideHeightMetres(data.nextHighTideHeightMetres())
                .nextLowTideTime(data.nextLowTideTime())
                .nextLowTideHeightMetres(data.nextLowTideHeightMetres())
                .tideAligned(data.tideAligned())
                .evaluationModel(model)
                .rating(evaluation.rating())
                .fierySkyPotential(evaluation.fierySkyPotential())
                .goldenHourPotential(evaluation.goldenHourPotential())
                .summary(evaluation.summary())
                .solarEventTime(eventTime)
                .azimuthDeg(azimuth)
                .build();
    }
}
