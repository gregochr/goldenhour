package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.exception.WeatherDataFetchException;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.notification.EmailNotificationService;
import com.gregochr.goldenhour.service.notification.MacOsToastNotificationService;
import com.gregochr.goldenhour.service.notification.PushoverNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ForecastService}.
 */
@ExtendWith(MockitoExtension.class)
class ForecastServiceTest {

    private static final double DURHAM_LAT = 54.7753;
    private static final double DURHAM_LON = -1.5849;
    private static final String DURHAM = "Durham UK";
    private static final LocationEntity DURHAM_LOCATION = LocationEntity.builder()
            .id(1L).name(DURHAM).lat(DURHAM_LAT).lon(DURHAM_LON).build();

    @Mock
    private SolarService solarService;
    @Mock
    private OpenMeteoService openMeteoService;
    @Mock
    private ForecastDataAugmentor augmentor;
    @Mock
    private EvaluationService evaluationService;
    @Mock
    private ForecastEvaluationRepository repository;
    @Mock
    private EmailNotificationService emailService;
    @Mock
    private PushoverNotificationService pushoverService;
    @Mock
    private MacOsToastNotificationService toastService;

    @InjectMocks
    private ForecastService forecastService;

    @BeforeEach
    void setUp() {
        // Augmentor acts as pass-through by default — returns the input data unchanged.
        // Lenient because not every test exercises both augmentation paths.
        lenient().when(augmentor.augmentWithDirectionalCloud(any(), anyDouble(), anyDouble(),
                anyInt(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithTideData(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("runForecasts() evaluates sunrise and sunset and saves two entities")
    void runForecasts_savesOneEntityPerTargetType() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunrise = LocalDateTime.of(2026, 6, 21, 3, 30);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);

        AtmosphericData forecastData = buildAtmosphericData(sunrise, TargetType.SUNRISE);
        SunsetEvaluation evaluation = new SunsetEvaluation(null, 70, 75, "Good conditions.");
        ForecastEvaluationEntity savedEntity = ForecastEvaluationEntity.builder().id(1L).build();

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getAtmosphericData(any(ForecastRequest.class), any(), any()))
                .thenReturn(forecastData);
        when(evaluationService.evaluate(eq(forecastData), any(EvaluationModel.class), any()))
                .thenReturn(evaluation);
        when(repository.save(any())).thenReturn(savedEntity);

        List<ForecastEvaluationEntity> results = forecastService.runForecasts(
                DURHAM_LOCATION, date, null, Set.of(), EvaluationModel.SONNET, null);

        assertThat(results).hasSize(2);
        verify(repository, times(2)).save(any());
    }

    @Test
    @DisplayName("runForecasts() dispatches notifications for each target type")
    void runForecasts_sendsNotificationsForEachTargetType() {
        LocalDate date = LocalDate.of(2026, 2, 20);
        LocalDateTime sunrise = LocalDateTime.of(2026, 2, 20, 7, 30);
        LocalDateTime sunset = LocalDateTime.of(2026, 2, 20, 17, 10);

        AtmosphericData forecastData = buildAtmosphericData(sunrise, TargetType.SUNRISE);
        SunsetEvaluation evaluation = new SunsetEvaluation(null, 50, 60, "Moderate potential.");
        ForecastEvaluationEntity savedEntity = ForecastEvaluationEntity.builder().id(2L).build();

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getAtmosphericData(any(ForecastRequest.class), any(), any()))
                .thenReturn(forecastData);
        when(evaluationService.evaluate(eq(forecastData), any(EvaluationModel.class), any()))
                .thenReturn(evaluation);
        when(repository.save(any())).thenReturn(savedEntity);

        forecastService.runForecasts(DURHAM_LOCATION, date, null, Set.of(),
                EvaluationModel.SONNET, null);

        verify(emailService, times(2)).notify(eq(evaluation), eq(DURHAM), any(), eq(date));
        verify(pushoverService, times(2)).notify(eq(evaluation), eq(DURHAM), any(), eq(date));
        verify(toastService, times(2)).notify(eq(evaluation), eq(DURHAM), any(), eq(date));
    }

    @Test
    @DisplayName("runForecasts() persists entity with correct evaluation fields")
    void runForecasts_entityHasCorrectEvaluationFields() {
        LocalDate date = LocalDate.of(2026, 2, 20);
        LocalDateTime sunrise = LocalDateTime.of(2026, 2, 20, 7, 30);
        LocalDateTime sunset = LocalDateTime.of(2026, 2, 20, 17, 10);

        AtmosphericData forecastData = buildAtmosphericData(sunrise, TargetType.SUNRISE);
        SunsetEvaluation evaluation = new SunsetEvaluation(null, 88, 82, "Exceptional setup.");

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getAtmosphericData(any(ForecastRequest.class), any(), any()))
                .thenReturn(forecastData);
        when(evaluationService.evaluate(eq(forecastData), any(EvaluationModel.class), any()))
                .thenReturn(evaluation);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        forecastService.runForecasts(DURHAM_LOCATION, date, null, Set.of(),
                EvaluationModel.SONNET, null);

        ArgumentCaptor<ForecastEvaluationEntity> captor =
                ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
        verify(repository, times(2)).save(captor.capture());

        ForecastEvaluationEntity first = captor.getAllValues().get(0);
        assertThat(first.getFierySkyPotential()).isEqualTo(88);
        assertThat(first.getGoldenHourPotential()).isEqualTo(82);
        assertThat(first.getRating()).isNull();
        assertThat(first.getEvaluationModel()).isEqualTo(EvaluationModel.SONNET);
        assertThat(first.getSummary()).isEqualTo("Exceptional setup.");
        assertThat(first.getLocationName()).isEqualTo(DURHAM);
        assertThat(first.getTargetDate()).isEqualTo(date);
        assertThat(first.getSolarEventTime()).isEqualTo(sunrise);

        ForecastEvaluationEntity second = captor.getAllValues().get(1);
        assertThat(second.getSolarEventTime()).isEqualTo(sunset);
    }

    @Test
    @DisplayName("runForecasts() persists Haiku entity with rating and null scores")
    void runForecasts_haikuEntity_hasRatingAndNullScores() {
        LocalDate date = LocalDate.of(2026, 2, 20);
        LocalDateTime sunrise = LocalDateTime.of(2026, 2, 20, 7, 30);
        LocalDateTime sunset = LocalDateTime.of(2026, 2, 20, 17, 10);

        AtmosphericData forecastData = buildAtmosphericData(sunrise, TargetType.SUNRISE);
        SunsetEvaluation evaluation = new SunsetEvaluation(4, null, null, "Good conditions.");

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getAtmosphericData(any(ForecastRequest.class), any(), any()))
                .thenReturn(forecastData);
        when(evaluationService.evaluate(eq(forecastData), eq(EvaluationModel.HAIKU), any()))
                .thenReturn(evaluation);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        forecastService.runForecasts(DURHAM_LOCATION, date, null, Set.of(),
                EvaluationModel.HAIKU, null);

        ArgumentCaptor<ForecastEvaluationEntity> captor =
                ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
        verify(repository, times(2)).save(captor.capture());

        ForecastEvaluationEntity first = captor.getAllValues().get(0);
        assertThat(first.getRating()).isEqualTo(4);
        assertThat(first.getFierySkyPotential()).isNull();
        assertThat(first.getGoldenHourPotential()).isNull();
        assertThat(first.getEvaluationModel()).isEqualTo(EvaluationModel.HAIKU);
    }

    @Test
    @DisplayName("runForecasts() with WILDLIFE model skips Claude, saves hourly entities with no notifications")
    void runForecasts_wildlifeModel_skipsClaudeAndNotifications() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunrise = LocalDateTime.of(2026, 6, 21, 3, 30);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);

        AtmosphericData slot1 = buildAtmosphericData(LocalDateTime.of(2026, 6, 21, 3, 0), TargetType.SUNRISE);
        AtmosphericData slot2 = buildAtmosphericData(LocalDateTime.of(2026, 6, 21, 4, 0), TargetType.SUNRISE);
        ForecastEvaluationEntity savedEntity = ForecastEvaluationEntity.builder().id(5L).build();

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getHourlyAtmosphericData(any(ForecastRequest.class), any(), any(), any()))
                .thenReturn(java.util.List.of(slot1, slot2));
        when(repository.save(any())).thenReturn(savedEntity);

        List<ForecastEvaluationEntity> results = forecastService.runForecasts(
                DURHAM_LOCATION, date, null, Set.of(), EvaluationModel.WILDLIFE, null);

        assertThat(results).hasSize(2);
        verify(repository, times(2)).save(any());
        verify(evaluationService, org.mockito.Mockito.never())
                .evaluate(any(), any(EvaluationModel.class), any());
        verify(emailService, org.mockito.Mockito.never()).notify(any(), any(), any(), any());
        verify(pushoverService, org.mockito.Mockito.never()).notify(any(), any(), any(), any());
        verify(toastService, org.mockito.Mockito.never()).notify(any(), any(), any(), any());
    }

    @Test
    @DisplayName("runForecasts() with WILDLIFE model persists HOURLY entities with null scores")
    void runForecasts_wildlifeModel_entityHasHourlyTargetTypeAndNullScores() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunrise = LocalDateTime.of(2026, 6, 21, 3, 30);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);

        AtmosphericData slot = buildAtmosphericData(LocalDateTime.of(2026, 6, 21, 7, 0), TargetType.SUNRISE);

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getHourlyAtmosphericData(any(ForecastRequest.class), any(), any(), any()))
                .thenReturn(java.util.List.of(slot));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        forecastService.runForecasts(DURHAM_LOCATION, date, null, Set.of(),
                EvaluationModel.WILDLIFE, null);

        ArgumentCaptor<ForecastEvaluationEntity> captor =
                ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
        verify(repository, times(1)).save(captor.capture());

        ForecastEvaluationEntity saved = captor.getValue();
        assertThat(saved.getEvaluationModel()).isEqualTo(EvaluationModel.WILDLIFE);
        assertThat(saved.getTargetType()).isEqualTo(TargetType.HOURLY);
        assertThat(saved.getRating()).isNull();
        assertThat(saved.getFierySkyPotential()).isNull();
        assertThat(saved.getGoldenHourPotential()).isNull();
        assertThat(saved.getSummary()).isNull();
    }

    @Test
    @DisplayName("runForecasts() when weather fetch fails with exception throws WeatherDataFetchException")
    void runForecasts_weatherFetchFails_throwsWeatherDataFetchException() {
        LocalDate date = LocalDate.of(2026, 2, 20);
        LocalDateTime sunrise = LocalDateTime.of(2026, 2, 20, 7, 30);

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(openMeteoService.getAtmosphericData(any(ForecastRequest.class), any(), any()))
                .thenThrow(new RuntimeException("Network error: API timeout"));

        assertThatThrownBy(() -> forecastService.runForecasts(
                DURHAM_LOCATION, date, null, Set.of(), EvaluationModel.SONNET, null))
                .isInstanceOf(WeatherDataFetchException.class)
                .hasMessageContaining("Weather data fetch failed for " + DURHAM + " SUNRISE");
    }

    @Test
    @DisplayName("runForecasts() when weather fetch fails, EvaluationService is never called")
    void runForecasts_weatherFetchFails_neverCallsEvaluationService() {
        LocalDate date = LocalDate.of(2026, 2, 20);
        LocalDateTime sunrise = LocalDateTime.of(2026, 2, 20, 7, 30);

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(openMeteoService.getAtmosphericData(any(ForecastRequest.class), any(), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        assertThatThrownBy(() -> forecastService.runForecasts(
                DURHAM_LOCATION, date, null, Set.of(), EvaluationModel.SONNET, null))
                .isInstanceOf(WeatherDataFetchException.class);

        verify(evaluationService, never()).evaluate(any(), any(EvaluationModel.class), any());
    }

    @Test
    @DisplayName("runForecasts() when weather fetch returns null throws WeatherDataFetchException")
    void runForecasts_weatherFetchReturnsNull_throwsWeatherDataFetchException() {
        LocalDate date = LocalDate.of(2026, 2, 20);
        LocalDateTime sunrise = LocalDateTime.of(2026, 2, 20, 7, 30);

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(openMeteoService.getAtmosphericData(any(ForecastRequest.class), any(), any()))
                .thenReturn(null);

        assertThatThrownBy(() -> forecastService.runForecasts(
                DURHAM_LOCATION, date, null, Set.of(), EvaluationModel.SONNET, null))
                .isInstanceOf(WeatherDataFetchException.class)
                .hasMessageContaining("Weather service returned null");

        verify(evaluationService, never()).evaluate(any(), any(EvaluationModel.class), any());
    }

    private AtmosphericData buildAtmosphericData(LocalDateTime eventTime, TargetType targetType) {
        return TestAtmosphericData.builder()
                .solarEventTime(eventTime)
                .targetType(targetType)
                .lowCloud(15)
                .midCloud(55)
                .visibility(22000)
                .windSpeed(new BigDecimal("4.20"))
                .temperature(12.5)
                .apparentTemperature(9.8)
                .precipProbability(20)
                .build();
    }
}
