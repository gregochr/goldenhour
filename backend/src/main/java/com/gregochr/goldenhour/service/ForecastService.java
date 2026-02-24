package com.gregochr.goldenhour.service;

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
     * Runs sunrise and sunset forecasts for the given location and date.
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
        return runForecasts(locationName, lat, lon, date, null, java.util.Set.of());
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
     * @param date         the calendar date to forecast
     * @param targetType   the target type to evaluate, or {@code null} to evaluate both
     * @param tideTypes    tide preferences for this location (empty if inland)
     * @return the saved entities in evaluation order
     */
    public List<ForecastEvaluationEntity> runForecasts(String locationName, double lat, double lon,
            LocalDate date, TargetType targetType, java.util.Set<TideType> tideTypes) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int daysAhead = (int) ChronoUnit.DAYS.between(today, date);
        List<ForecastEvaluationEntity> results = new ArrayList<>();

        List<TargetType> types = (targetType != null)
                ? List.of(targetType)
                : List.of(TargetType.values());

        for (TargetType type : types) {
            LocalDateTime eventTime = type == TargetType.SUNRISE
                    ? solarService.sunriseUtc(lat, lon, date)
                    : solarService.sunsetUtc(lat, lon, date);

            ForecastRequest request = new ForecastRequest(lat, lon, locationName, date, type);
            AtmosphericData baseData = openMeteoService.getAtmosphericData(request, eventTime);

            // Fetch tide data only for coastal locations (non-empty tideTypes set)
            TideData tideData = null;
            Boolean tideAligned = null;
            boolean isCoastal = tideTypes != null && !tideTypes.isEmpty()
                    && !tideTypes.equals(java.util.Set.of(TideType.NOT_COASTAL));
            if (isCoastal) {
                var tideMaybe = tideService.getTideData(lat, lon, eventTime);
                if (tideMaybe.isPresent()) {
                    tideData = tideMaybe.get();
                    tideAligned = tideService.calculateTideAligned(tideData, tideTypes);
                }
            }

            // Create augmented atmospheric data with tide information
            AtmosphericData forecastData = new AtmosphericData(
                    baseData.locationName(),
                    baseData.solarEventTime(),
                    baseData.targetType(),
                    baseData.lowCloudPercent(),
                    baseData.midCloudPercent(),
                    baseData.highCloudPercent(),
                    baseData.visibilityMetres(),
                    baseData.windSpeedMs(),
                    baseData.windDirectionDegrees(),
                    baseData.precipitationMm(),
                    baseData.humidityPercent(),
                    baseData.weatherCode(),
                    baseData.boundaryLayerHeightMetres(),
                    baseData.shortwaveRadiationWm2(),
                    baseData.pm25(),
                    baseData.dustUgm3(),
                    baseData.aerosolOpticalDepth(),
                    tideData != null ? tideData.tideState() : null,
                    tideData != null ? tideData.nextHighTideTime() : null,
                    tideData != null ? tideData.nextHighTideHeightMetres() : null,
                    tideData != null ? tideData.nextLowTideTime() : null,
                    tideData != null ? tideData.nextLowTideHeightMetres() : null,
                    tideAligned);

            SunsetEvaluation evaluation = evaluationService.evaluate(forecastData);

            int azimuth = type == TargetType.SUNRISE
                    ? solarService.sunriseAzimuthDeg(lat, lon, date)
                    : solarService.sunsetAzimuthDeg(lat, lon, date);

            ForecastEvaluationEntity entity = ForecastEvaluationEntity.builder()
                    .locationLat(BigDecimal.valueOf(lat))
                    .locationLon(BigDecimal.valueOf(lon))
                    .locationName(locationName)
                    .targetDate(date)
                    .targetType(type)
                    .forecastRunAt(LocalDateTime.now(ZoneOffset.UTC))
                    .daysAhead(daysAhead)
                    .lowCloud(forecastData.lowCloudPercent())
                    .midCloud(forecastData.midCloudPercent())
                    .highCloud(forecastData.highCloudPercent())
                    .visibility(forecastData.visibilityMetres())
                    .windSpeed(forecastData.windSpeedMs())
                    .windDirection(forecastData.windDirectionDegrees())
                    .precipitation(forecastData.precipitationMm())
                    .humidity(forecastData.humidityPercent())
                    .weatherCode(forecastData.weatherCode())
                    .boundaryLayerHeight(forecastData.boundaryLayerHeightMetres())
                    .shortwaveRadiation(forecastData.shortwaveRadiationWm2())
                    .pm25(forecastData.pm25())
                    .dust(forecastData.dustUgm3())
                    .aerosolOpticalDepth(forecastData.aerosolOpticalDepth())
                    .tideState(forecastData.tideState())
                    .nextHighTideTime(forecastData.nextHighTideTime())
                    .nextHighTideHeightMetres(forecastData.nextHighTideHeightMetres())
                    .nextLowTideTime(forecastData.nextLowTideTime())
                    .nextLowTideHeightMetres(forecastData.nextLowTideHeightMetres())
                    .tideAligned(forecastData.tideAligned())
                    .rating(evaluation.rating())
                    .summary(evaluation.summary())
                    .solarEventTime(eventTime)
                    .azimuthDeg(azimuth)
                    .build();

            results.add(repository.save(entity));
            LOG.info("Forecast saved: {} {} {} (T+{}) — rating {}/5",
                    locationName, type, date, daysAhead, evaluation.rating());
            emailService.notify(evaluation, locationName, type, date);
            pushoverService.notify(evaluation, locationName, type, date);
            toastService.notify(evaluation, locationName, type, date);
        }
        return results;
    }
}
