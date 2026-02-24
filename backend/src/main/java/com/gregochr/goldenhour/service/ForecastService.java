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
            AtmosphericData forecastData = augmentWithTideData(baseData, lat, lon, eventTime, tideTypes);
            SunsetEvaluation evaluation = evaluationService.evaluate(forecastData);

            int azimuth = type == TargetType.SUNRISE
                    ? solarService.sunriseAzimuthDeg(lat, lon, date)
                    : solarService.sunsetAzimuthDeg(lat, lon, date);

            ForecastEvaluationEntity entity = buildEntity(
                    locationName, lat, lon, date, type, daysAhead, eventTime, azimuth, forecastData, evaluation);

            results.add(repository.save(entity));
            LOG.info("Forecast saved: {} {} {} (T+{}) — rating {}/5",
                    locationName, type, date, daysAhead, evaluation.rating());
            emailService.notify(evaluation, locationName, type, date);
            pushoverService.notify(evaluation, locationName, type, date);
            toastService.notify(evaluation, locationName, type, date);
        }
        return results;
    }

    /**
     * Returns a copy of {@code base} augmented with tide fields for coastal locations.
     *
     * <p>If the location is not coastal (empty or NOT_COASTAL tide types), the tide
     * fields in the returned record are {@code null} and the original data is otherwise
     * unchanged.
     *
     * @param base       atmospheric data without tide fields
     * @param lat        latitude in decimal degrees
     * @param lon        longitude in decimal degrees
     * @param eventTime  UTC time of the solar event
     * @param tideTypes  tide preferences for this location (empty if inland)
     * @return a new {@link AtmosphericData} with tide fields populated where applicable
     */
    private AtmosphericData augmentWithTideData(AtmosphericData base, double lat, double lon,
            LocalDateTime eventTime, java.util.Set<TideType> tideTypes) {
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
     * @return the unsaved entity
     */
    private ForecastEvaluationEntity buildEntity(String locationName, double lat, double lon,
            LocalDate date, TargetType type, int daysAhead, LocalDateTime eventTime, int azimuth,
            AtmosphericData data, SunsetEvaluation evaluation) {
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
                .tideState(data.tideState())
                .nextHighTideTime(data.nextHighTideTime())
                .nextHighTideHeightMetres(data.nextHighTideHeightMetres())
                .nextLowTideTime(data.nextLowTideTime())
                .nextLowTideHeightMetres(data.nextLowTideHeightMetres())
                .tideAligned(data.tideAligned())
                .rating(evaluation.rating())
                .summary(evaluation.summary())
                .solarEventTime(eventTime)
                .azimuthDeg(azimuth)
                .build();
    }
}
