package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.notification.EmailNotificationService;
import com.gregochr.goldenhour.service.notification.MacOsToastNotificationService;
import com.gregochr.goldenhour.service.notification.PushoverNotificationService;
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

    private final SolarService solarService;
    private final OpenMeteoService openMeteoService;
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
     * @param evaluationService calls Claude to evaluate colour potential
     * @param repository        persists forecast evaluation results
     * @param emailService      email notification channel
     * @param pushoverService   Pushover notification channel
     * @param toastService      macOS toast notification channel
     */
    public ForecastService(SolarService solarService, OpenMeteoService openMeteoService,
            EvaluationService evaluationService, ForecastEvaluationRepository repository,
            EmailNotificationService emailService, PushoverNotificationService pushoverService,
            MacOsToastNotificationService toastService) {
        this.solarService = solarService;
        this.openMeteoService = openMeteoService;
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
     * @return the two saved entities (sunrise first, then sunset)
     */
    public List<ForecastEvaluationEntity> runForecasts(String locationName, double lat, double lon,
            LocalDate date) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int daysAhead = (int) ChronoUnit.DAYS.between(today, date);
        List<ForecastEvaluationEntity> results = new ArrayList<>();

        for (TargetType targetType : TargetType.values()) {
            LocalDateTime eventTime = targetType == TargetType.SUNRISE
                    ? solarService.sunriseUtc(lat, lon, date)
                    : solarService.sunsetUtc(lat, lon, date);

            ForecastRequest request = new ForecastRequest(lat, lon, locationName, date, targetType);
            AtmosphericData forecastData = openMeteoService.getAtmosphericData(request, eventTime);
            SunsetEvaluation evaluation = evaluationService.evaluate(forecastData);

            ForecastEvaluationEntity entity = ForecastEvaluationEntity.builder()
                    .locationLat(BigDecimal.valueOf(lat))
                    .locationLon(BigDecimal.valueOf(lon))
                    .locationName(locationName)
                    .targetDate(date)
                    .targetType(targetType)
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
                    .rating(evaluation.rating())
                    .summary(evaluation.summary())
                    .solarEventTime(eventTime)
                    .build();

            results.add(repository.save(entity));
            emailService.notify(evaluation, locationName, targetType, date);
            pushoverService.notify(evaluation, locationName, targetType, date);
            toastService.notify(evaluation, locationName, targetType, date);
        }
        return results;
    }
}
