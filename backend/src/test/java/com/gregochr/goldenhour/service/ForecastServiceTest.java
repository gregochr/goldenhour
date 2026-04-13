package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.exception.WeatherDataFetchException;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.SolarCloudTrend.SolarCloudSlot;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TideRiskLevel;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.TriageResult;
import com.gregochr.goldenhour.model.TriageRule;
import com.gregochr.goldenhour.model.UpwindCloudSample;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.notification.EmailNotificationService;
import com.gregochr.goldenhour.service.notification.MacOsToastNotificationService;
import com.gregochr.goldenhour.service.notification.PushoverNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private WeatherTriageEvaluator weatherTriageEvaluator;
    @Mock
    private TideAlignmentEvaluator tideAlignmentEvaluator;

    @InjectMocks
    private ForecastService forecastService;

    @BeforeEach
    void setUp() {
        // Augmentor acts as pass-through by default — returns the input data unchanged.
        // Lenient because not every test exercises both augmentation paths.
        lenient().when(augmentor.augmentWithDirectionalCloud(any(), anyDouble(), anyDouble(),
                anyInt(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithDirectionalCloud(any(), anyDouble(), anyDouble(),
                anyInt(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithCloudApproach(any(), anyDouble(), anyDouble(),
                anyInt(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithCloudApproach(any(), anyDouble(), anyDouble(),
                anyInt(), any(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithTideData(
                any(), any(), any(), any(), anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithLocationOrientation(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithStormSurge(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithInversionScore(any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        // Tide alignment passes by default (non-SEASCAPE locations in most tests)
        lenient().when(tideAlignmentEvaluator.evaluate(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
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
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(forecastData, null));
        when(evaluationService.evaluate(eq(forecastData), any(EvaluationModel.class), any()))
                .thenReturn(evaluation);
        when(repository.save(any())).thenReturn(savedEntity);

        List<ForecastEvaluationEntity> results = forecastService.runForecasts(
                DURHAM_LOCATION, date, null, Set.of(), EvaluationModel.SONNET, null);

        assertThat(results).hasSize(2);
        ArgumentCaptor<ForecastEvaluationEntity> saveCaptor =
                ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
        verify(repository, times(2)).save(saveCaptor.capture());
        assertThat(saveCaptor.getAllValues())
                .extracting(ForecastEvaluationEntity::getLocationName)
                .containsOnly(DURHAM);
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
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(forecastData, null));
        when(evaluationService.evaluate(eq(forecastData), any(EvaluationModel.class), any()))
                .thenReturn(evaluation);
        when(repository.save(any())).thenReturn(savedEntity);

        forecastService.runForecasts(DURHAM_LOCATION, date, null, Set.of(),
                EvaluationModel.SONNET, null);

        verify(emailService).notify(eq(evaluation), eq(DURHAM), eq(TargetType.SUNRISE), eq(date));
        verify(emailService).notify(eq(evaluation), eq(DURHAM), eq(TargetType.SUNSET), eq(date));
        verify(pushoverService).notify(eq(evaluation), eq(DURHAM), eq(TargetType.SUNRISE), eq(date));
        verify(pushoverService).notify(eq(evaluation), eq(DURHAM), eq(TargetType.SUNSET), eq(date));
        verify(toastService).notify(eq(evaluation), eq(DURHAM), eq(TargetType.SUNRISE), eq(date));
        verify(toastService).notify(eq(evaluation), eq(DURHAM), eq(TargetType.SUNSET), eq(date));
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
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(forecastData, null));
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
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(forecastData, null));
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
        ArgumentCaptor<ForecastEvaluationEntity> wildlifeCaptor =
                ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
        verify(repository, times(2)).save(wildlifeCaptor.capture());
        assertThat(wildlifeCaptor.getAllValues())
                .extracting(ForecastEvaluationEntity::getEvaluationModel)
                .containsOnly(EvaluationModel.WILDLIFE);
        verify(evaluationService, never())
                .evaluate(any(), any(EvaluationModel.class), any());
        verify(emailService, never()).notify(any(), any(), any(), any());
        verify(pushoverService, never()).notify(any(), any(), any(), any());
        verify(toastService, never()).notify(any(), any(), any(), any());
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
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
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
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
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
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(null, null));

        assertThatThrownBy(() -> forecastService.runForecasts(
                DURHAM_LOCATION, date, null, Set.of(), EvaluationModel.SONNET, null))
                .isInstanceOf(WeatherDataFetchException.class)
                .hasMessageContaining("Weather service returned null");

        verify(evaluationService, never()).evaluate(any(), any(EvaluationModel.class), any());
    }

    // --- fetchWeatherAndTriage tests ---

    @Test
    @DisplayName("fetchWeatherAndTriage() returns non-triaged result when conditions are suitable")
    void fetchWeatherAndTriage_suitableConditions_returnsNonTriagedResult() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

        ForecastPreEvalResult result = forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, true, null);

        assertThat(result.triaged()).isFalse();
        assertThat(result.triageReason()).isNull();
        assertThat(result.atmosphericData()).isNotNull();
        assertThat(result.location()).isEqualTo(DURHAM_LOCATION);
        assertThat(result.targetType()).isEqualTo(TargetType.SUNSET);
        assertThat(result.azimuth()).isEqualTo(310);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() triages and persists canned entity when conditions unsuitable")
    void fetchWeatherAndTriage_unsuitableConditions_triagesAndPersists() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunrise = LocalDateTime.of(2026, 6, 21, 3, 30);
        AtmosphericData data = buildAtmosphericData(sunrise, TargetType.SUNRISE);
        ForecastEvaluationEntity savedEntity = ForecastEvaluationEntity.builder().id(10L).build();

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(solarService.sunriseAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(65);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any()))
                .thenReturn(Optional.of(new TriageResult("Low cloud 85%", TriageRule.HIGH_CLOUD)));
        when(repository.save(any())).thenReturn(savedEntity);

        ForecastPreEvalResult result = forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNRISE, Set.of(),
                EvaluationModel.SONNET, true, null);

        assertThat(result.triaged()).isTrue();
        assertThat(result.triageReason()).isEqualTo("Low cloud 85%");
        ArgumentCaptor<ForecastEvaluationEntity> triageCaptor =
                ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
        verify(repository).save(triageCaptor.capture());
        ForecastEvaluationEntity triaged = triageCaptor.getValue();
        assertThat(triaged.getLocationName()).isEqualTo(DURHAM);
        assertThat(triaged.getTargetType()).isEqualTo(TargetType.SUNRISE);
        assertThat(triaged.getSummary()).contains("Low cloud 85%");
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() throws WeatherDataFetchException when weather fetch fails")
    void fetchWeatherAndTriage_weatherFetchFails_throwsException() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenThrow(new RuntimeException("API timeout"));

        assertThatThrownBy(() -> forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, true, null))
                .isInstanceOf(WeatherDataFetchException.class)
                .hasMessageContaining("Weather data fetch failed");
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() throws WeatherDataFetchException when weather returns null")
    void fetchWeatherAndTriage_weatherReturnsNull_throwsException() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(null, null));

        assertThatThrownBy(() -> forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, true, null))
                .isInstanceOf(WeatherDataFetchException.class)
                .hasMessageContaining("Weather service returned null");
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() publishes events when jobRun is provided")
    void fetchWeatherAndTriage_withJobRun_publishesEvents() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setId(42L);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

        forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, true, jobRun);

        // Should publish FETCHING_WEATHER, FETCHING_CLOUD (no FETCHING_TIDES — empty tideTypes)
        ArgumentCaptor<LocationTaskEvent> eventCaptor =
                ArgumentCaptor.forClass(LocationTaskEvent.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
        List<LocationTaskEvent> events = eventCaptor.getAllValues();
        assertThat(events.get(0).getState()).isEqualTo(LocationTaskState.FETCHING_WEATHER);
        assertThat(events.get(0).getJobRunId()).isEqualTo(42L);
        assertThat(events.get(0).getLocationName()).isEqualTo(DURHAM);
        assertThat(events.get(1).getState()).isEqualTo(LocationTaskState.FETCHING_CLOUD);
    }

    // --- tide alignment optimisation strategy tests ---

    @Test
    @DisplayName("fetchWeatherAndTriage() triages SEASCAPE when tideAlignmentEnabled=true and tide misaligned")
    void fetchWeatherAndTriage_seascapeTideAlignmentEnabled_misaligned_triages() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        LocationEntity seascape = LocationEntity.builder()
                .id(2L).name("Bamburgh").lat(55.6).lon(-1.7)
                .locationType(new java.util.HashSet<>(Set.of(LocationType.SEASCAPE)))
                .tideType(new java.util.HashSet<>(Set.of(TideType.HIGH)))
                .build();
        ForecastEvaluationEntity savedEntity = ForecastEvaluationEntity.builder().id(10L).build();

        when(solarService.sunsetUtc(55.6, -1.7, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(55.6, -1.7, date)).thenReturn(300);
        when(solarService.civilDuskUtc(55.6, -1.7, date))
                .thenReturn(sunset.plusMinutes(45));
        when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());
        when(tideAlignmentEvaluator.evaluate(any(), any(), any(), any()))
                .thenReturn(Optional.of(new TriageResult(
                        "No high tide aligned with golden/blue hour window",
                        TriageRule.TIDE_MISALIGNED)));
        when(repository.save(any())).thenReturn(savedEntity);

        ForecastPreEvalResult result = forecastService.fetchWeatherAndTriage(
                seascape, date, TargetType.SUNSET, Set.of(TideType.HIGH),
                EvaluationModel.SONNET, true, null);

        assertThat(result.triaged()).isTrue();
        assertThat(result.triageReason()).contains("tide");

        ArgumentCaptor<ForecastEvaluationEntity> tideCaptor =
                ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
        verify(repository).save(tideCaptor.capture());
        ForecastEvaluationEntity saved = tideCaptor.getValue();
        assertThat(saved.getLocationName()).isEqualTo("Bamburgh");
        assertThat(saved.getTargetType()).isEqualTo(TargetType.SUNSET);
        assertThat(saved.getRating()).isEqualTo(1);
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() skips tide triage when tideAlignmentEnabled=false even for SEASCAPE")
    void fetchWeatherAndTriage_seascapeTideAlignmentDisabled_doesNotTriage() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        LocationEntity seascape = LocationEntity.builder()
                .id(2L).name("Bamburgh").lat(55.6).lon(-1.7)
                .locationType(new java.util.HashSet<>(Set.of(LocationType.SEASCAPE)))
                .tideType(new java.util.HashSet<>(Set.of(TideType.HIGH)))
                .build();

        when(solarService.sunsetUtc(55.6, -1.7, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(55.6, -1.7, date)).thenReturn(300);
        when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

        ForecastPreEvalResult result = forecastService.fetchWeatherAndTriage(
                seascape, date, TargetType.SUNSET, Set.of(TideType.HIGH),
                EvaluationModel.SONNET, false, null);

        assertThat(result.triaged()).isFalse();
        verify(tideAlignmentEvaluator, never()).evaluate(any(), any(), any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() does not apply tide alignment for non-SEASCAPE locations")
    void fetchWeatherAndTriage_landscapeLocation_tideAlignmentNotApplied() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
        when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

        ForecastPreEvalResult result = forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, true, null);

        assertThat(result.triaged()).isFalse();
        verify(tideAlignmentEvaluator, never()).evaluate(any(), any(), any(), any());
    }

    // --- evaluateAndPersist tests ---

    @Test
    @DisplayName("evaluateAndPersist() evaluates with Claude and saves entity")
    void evaluateAndPersist_evaluatesAndSaves() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        SunsetEvaluation evaluation = new SunsetEvaluation(null, 85, 78, "Great sunset.");
        ForecastEvaluationEntity savedEntity = ForecastEvaluationEntity.builder().id(20L).build();

        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, DURHAM_LOCATION, date,
                TargetType.SUNSET, sunset, 310, 0, EvaluationModel.SONNET, Set.of(),
                DURHAM + "|" + date + "|SUNSET", null);

        when(evaluationService.evaluate(eq(data), eq(EvaluationModel.SONNET), any()))
                .thenReturn(evaluation);
        when(repository.save(any())).thenReturn(savedEntity);

        ForecastEvaluationEntity result = forecastService.evaluateAndPersist(preEval, null);

        assertThat(result).isEqualTo(savedEntity);
        verify(evaluationService).evaluate(eq(data), eq(EvaluationModel.SONNET), eq(null));

        ArgumentCaptor<ForecastEvaluationEntity> evalCaptor =
                ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
        verify(repository).save(evalCaptor.capture());
        ForecastEvaluationEntity saved = evalCaptor.getValue();
        assertThat(saved.getLocationName()).isEqualTo(DURHAM);
        assertThat(saved.getTargetType()).isEqualTo(TargetType.SUNSET);
        assertThat(saved.getEvaluationModel()).isEqualTo(EvaluationModel.SONNET);
        assertThat(saved.getFierySkyPotential()).isEqualTo(85);
        assertThat(saved.getGoldenHourPotential()).isEqualTo(78);
    }

    @Test
    @DisplayName("evaluateAndPersist() sends notifications after saving")
    void evaluateAndPersist_sendsNotifications() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        SunsetEvaluation evaluation = new SunsetEvaluation(4, null, null, "Good.");

        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, DURHAM_LOCATION, date,
                TargetType.SUNSET, sunset, 310, 0, EvaluationModel.HAIKU, Set.of(),
                DURHAM + "|" + date + "|SUNSET", null);

        when(evaluationService.evaluate(any(), any(), any())).thenReturn(evaluation);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        forecastService.evaluateAndPersist(preEval, null);

        verify(emailService).notify(eq(evaluation), eq(DURHAM), eq(TargetType.SUNSET), eq(date));
        verify(pushoverService).notify(eq(evaluation), eq(DURHAM), eq(TargetType.SUNSET), eq(date));
        verify(toastService).notify(eq(evaluation), eq(DURHAM), eq(TargetType.SUNSET), eq(date));
    }

    @Test
    @DisplayName("evaluateAndPersist() swallows notification exceptions without failing")
    void evaluateAndPersist_notificationFails_doesNotThrow() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        SunsetEvaluation evaluation = new SunsetEvaluation(null, 50, 60, "Moderate.");

        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, DURHAM_LOCATION, date,
                TargetType.SUNSET, sunset, 310, 0, EvaluationModel.SONNET, Set.of(),
                DURHAM + "|" + date + "|SUNSET", null);

        when(evaluationService.evaluate(any(), any(), any())).thenReturn(evaluation);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("SMTP down")).when(emailService).notify(any(), any(), any(), any());

        ForecastEvaluationEntity result = forecastService.evaluateAndPersist(preEval, null);

        assertThat(result).isNotNull();

        ArgumentCaptor<ForecastEvaluationEntity> notifCaptor =
                ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
        verify(repository).save(notifCaptor.capture());
        ForecastEvaluationEntity saved = notifCaptor.getValue();
        assertThat(saved.getLocationName()).isEqualTo(DURHAM);
        assertThat(saved.getTargetType()).isEqualTo(TargetType.SUNSET);
        assertThat(saved.getEvaluationModel()).isEqualTo(EvaluationModel.SONNET);
    }

    // --- persistCannedResult tests ---

    @Test
    @DisplayName("persistCannedResult() saves a canned rating=1 entity with the given reason")
    void persistCannedResult_savesCannedEntity() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);

        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, DURHAM_LOCATION, date,
                TargetType.SUNSET, sunset, 310, 0, EvaluationModel.SONNET, Set.of(),
                DURHAM + "|" + date + "|SUNSET", null);

        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ForecastEvaluationEntity result = forecastService.persistCannedResult(
                preEval, "Sentinel skip — region poor", null);

        assertThat(result).isNotNull();

        ArgumentCaptor<ForecastEvaluationEntity> captor =
                ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
        verify(repository).save(captor.capture());

        ForecastEvaluationEntity saved = captor.getValue();
        assertThat(saved.getRating()).isEqualTo(1);
        assertThat(saved.getFierySkyPotential()).isEqualTo(5);
        assertThat(saved.getGoldenHourPotential()).isEqualTo(5);
        assertThat(saved.getSummary()).contains("Conditions unsuitable");
        assertThat(saved.getSummary()).contains("Sentinel skip — region poor");
    }

    @Test
    @DisplayName("persistCannedResult() does not call evaluationService")
    void persistCannedResult_doesNotCallClaude() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);

        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, DURHAM_LOCATION, date,
                TargetType.SUNSET, sunset, 310, 0, EvaluationModel.SONNET, Set.of(),
                DURHAM + "|" + date + "|SUNSET", null);

        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        forecastService.persistCannedResult(preEval, "region poor", null);

        verify(evaluationService, never()).evaluate(any(), any(), any());
    }

    // --- Batch prefetch cache integration tests ---

    @Test
    @DisplayName("fetchWeatherAndTriage() with non-null prefetched map uses cache, not individual API")
    void fetchWeatherAndTriage_withPrefetched_usesCache() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        WeatherExtractionResult cached = new WeatherExtractionResult(data, null);

        java.util.Map<String, WeatherExtractionResult> prefetched = new java.util.HashMap<>();
        prefetched.put(DURHAM_LAT + "," + DURHAM_LON, cached);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
        ForecastRequest expectedRequest = new ForecastRequest(
                DURHAM_LAT, DURHAM_LON, DURHAM, date, TargetType.SUNSET);
        when(openMeteoService.getAtmosphericDataFromCache(
                eq(expectedRequest), eq(sunset), eq(prefetched)))
                .thenReturn(cached);
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(java.util.Optional.empty());

        forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, true, null, prefetched, null);

        verify(openMeteoService).getAtmosphericDataFromCache(
                eq(expectedRequest), eq(sunset), eq(prefetched));
        verify(openMeteoService, never()).getAtmosphericDataWithResponse(any(), any(), any());
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() with null prefetched map falls back to individual API")
    void fetchWeatherAndTriage_withNullPrefetched_fallsToIndividual() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(java.util.Optional.empty());

        forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, true, null, null, null);

        verify(openMeteoService).getAtmosphericDataWithResponse(
                any(ForecastRequest.class), any(), any());
        verify(openMeteoService, never()).getAtmosphericDataFromCache(any(), any(), any());
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() throws when cache miss occurs")
    void fetchWeatherAndTriage_cacheMiss_throwsWeatherDataFetchException() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);

        java.util.Map<String, WeatherExtractionResult> prefetched = new java.util.HashMap<>();

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getAtmosphericDataFromCache(any(), any(), eq(prefetched)))
                .thenReturn(null);

        assertThatThrownBy(() -> forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, true, null, prefetched, null))
                .isInstanceOf(WeatherDataFetchException.class)
                .hasMessageContaining("Weather data fetch failed");
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() continues when SSE event publish throws")
    void fetchWeatherAndTriage_ssePublishThrows_continuesNormally() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setId(99L);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

        // Simulate SSE emitter closed — publishEvent throws on every call
        doThrow(new IllegalStateException("ResponseBodyEmitter has already completed"))
                .when(eventPublisher).publishEvent(any(LocationTaskEvent.class));

        ForecastPreEvalResult result = forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, true, jobRun);

        // Triage should complete successfully despite SSE failures
        assertThat(result).isNotNull();
        assertThat(result.triaged()).isFalse();
        assertThat(result.location().getName()).isEqualTo(DURHAM);
        assertThat(result.atmosphericData()).isEqualTo(data);
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() does not publish events when jobRun is null")
    void fetchWeatherAndTriage_nullJobRun_noEvents() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

        forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, true, null);

        verify(eventPublisher, never()).publishEvent(any(LocationTaskEvent.class));
    }

    // --- runForecasts: single target type ---

    @Test
    @DisplayName("runForecasts() with SUNRISE targetType evaluates only sunrise, not sunset")
    void runForecasts_sunriseOnly_evaluatesOnlySunrise() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunrise = LocalDateTime.of(2026, 6, 21, 3, 30);
        AtmosphericData data = buildAtmosphericData(sunrise, TargetType.SUNRISE);
        SunsetEvaluation evaluation = new SunsetEvaluation(null, 70, 75, "Good.");

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(evaluationService.evaluate(eq(data), eq(EvaluationModel.SONNET), any()))
                .thenReturn(evaluation);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<ForecastEvaluationEntity> results = forecastService.runForecasts(
                DURHAM_LOCATION, date, TargetType.SUNRISE, Set.of(),
                EvaluationModel.SONNET, null);

        assertThat(results).hasSize(1);
        verify(solarService, never()).sunsetUtc(anyDouble(), anyDouble(), any());
        ArgumentCaptor<ForecastEvaluationEntity> captor =
                ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTargetType()).isEqualTo(TargetType.SUNRISE);
    }

    @Test
    @DisplayName("runForecasts() with SUNSET targetType evaluates only sunset, not sunrise")
    void runForecasts_sunsetOnly_evaluatesOnlySunset() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        SunsetEvaluation evaluation = new SunsetEvaluation(null, 60, 55, "Moderate.");

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(evaluationService.evaluate(eq(data), eq(EvaluationModel.SONNET), any()))
                .thenReturn(evaluation);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<ForecastEvaluationEntity> results = forecastService.runForecasts(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, null);

        assertThat(results).hasSize(1);
        verify(solarService, never()).sunriseUtc(anyDouble(), anyDouble(), any());
        ArgumentCaptor<ForecastEvaluationEntity> captor =
                ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTargetType()).isEqualTo(TargetType.SUNSET);
    }

    // --- evaluateAndPersist: event publishing ---

    @Test
    @DisplayName("evaluateAndPersist() publishes EVALUATING then COMPLETE events with correct fields")
    void evaluateAndPersist_withJobRun_publishesEvaluatingAndComplete() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        SunsetEvaluation evaluation = new SunsetEvaluation(null, 85, 78, "Great.");
        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setId(77L);

        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, DURHAM_LOCATION, date,
                TargetType.SUNSET, sunset, 310, 1, EvaluationModel.SONNET, Set.of(),
                DURHAM + "|" + date + "|SUNSET", null);

        when(evaluationService.evaluate(eq(data), eq(EvaluationModel.SONNET), eq(jobRun)))
                .thenReturn(evaluation);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        forecastService.evaluateAndPersist(preEval, jobRun);

        ArgumentCaptor<LocationTaskEvent> eventCaptor =
                ArgumentCaptor.forClass(LocationTaskEvent.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
        List<LocationTaskEvent> events = eventCaptor.getAllValues();

        assertThat(events.get(0).getState()).isEqualTo(LocationTaskState.EVALUATING);
        assertThat(events.get(0).getJobRunId()).isEqualTo(77L);
        assertThat(events.get(0).getLocationName()).isEqualTo(DURHAM);
        assertThat(events.get(0).getTargetDate()).isEqualTo(date.toString());
        assertThat(events.get(0).getTargetType()).isEqualTo("SUNSET");

        assertThat(events.get(1).getState()).isEqualTo(LocationTaskState.COMPLETE);
        assertThat(events.get(1).getJobRunId()).isEqualTo(77L);
    }

    // --- evaluateAndPersist: entity field preservation ---

    @Test
    @DisplayName("evaluateAndPersist() persists entity with correct preEval fields")
    void evaluateAndPersist_entityPreservesPreEvalFields() {
        LocalDate date = LocalDate.of(2026, 7, 15);
        LocalDateTime sunset = LocalDateTime.of(2026, 7, 15, 21, 12);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        SunsetEvaluation evaluation = new SunsetEvaluation(null, 72, 68, "Decent.");

        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, DURHAM_LOCATION, date,
                TargetType.SUNSET, sunset, 295, 2, EvaluationModel.HAIKU, Set.of(),
                DURHAM + "|" + date + "|SUNSET", null);

        when(evaluationService.evaluate(eq(data), eq(EvaluationModel.HAIKU), any()))
                .thenReturn(evaluation);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ForecastEvaluationEntity result = forecastService.evaluateAndPersist(preEval, null);

        assertThat(result.getLocationName()).isEqualTo(DURHAM);
        assertThat(result.getLocationLat()).isEqualByComparingTo(BigDecimal.valueOf(DURHAM_LAT));
        assertThat(result.getLocationLon()).isEqualByComparingTo(BigDecimal.valueOf(DURHAM_LON));
        assertThat(result.getTargetDate()).isEqualTo(date);
        assertThat(result.getTargetType()).isEqualTo(TargetType.SUNSET);
        assertThat(result.getDaysAhead()).isEqualTo(2);
        assertThat(result.getSolarEventTime()).isEqualTo(sunset);
        assertThat(result.getAzimuthDeg()).isEqualTo(295);
        assertThat(result.getEvaluationModel()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(result.getFierySkyPotential()).isEqualTo(72);
        assertThat(result.getGoldenHourPotential()).isEqualTo(68);
    }

    // --- persistCannedResult: event publishing ---

    @Test
    @DisplayName("persistCannedResult() publishes SKIPPED event with correct fields")
    void persistCannedResult_withJobRun_publishesSkippedEvent() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setId(55L);

        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, DURHAM_LOCATION, date,
                TargetType.SUNSET, sunset, 310, 0, EvaluationModel.SONNET, Set.of(),
                DURHAM + "|" + date + "|SUNSET", null);

        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        forecastService.persistCannedResult(preEval, "Sentinel: region poor", jobRun);

        ArgumentCaptor<LocationTaskEvent> eventCaptor =
                ArgumentCaptor.forClass(LocationTaskEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        LocationTaskEvent event = eventCaptor.getValue();
        assertThat(event.getState()).isEqualTo(LocationTaskState.SKIPPED);
        assertThat(event.getJobRunId()).isEqualTo(55L);
        assertThat(event.getLocationName()).isEqualTo(DURHAM);
        assertThat(event.getTargetType()).isEqualTo("SUNSET");
    }

    // --- fetchWeatherAndTriage: sunrise path uses civilDawn ---

    @Test
    @DisplayName("fetchWeatherAndTriage() SUNRISE uses sunriseUtc/sunriseAzimuthDeg (not sunset)")
    void fetchWeatherAndTriage_sunrise_usesSunriseService() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunrise = LocalDateTime.of(2026, 6, 21, 3, 30);
        AtmosphericData data = buildAtmosphericData(sunrise, TargetType.SUNRISE);

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(solarService.sunriseAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(52);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

        ForecastPreEvalResult result = forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNRISE, Set.of(),
                EvaluationModel.SONNET, true, null);

        assertThat(result.targetType()).isEqualTo(TargetType.SUNRISE);
        assertThat(result.azimuth()).isEqualTo(52);
        assertThat(result.eventTime()).isEqualTo(sunrise);
        verify(solarService).sunriseUtc(DURHAM_LAT, DURHAM_LON, date);
        verify(solarService).sunriseAzimuthDeg(DURHAM_LAT, DURHAM_LON, date);
        verify(solarService, never()).sunsetUtc(anyDouble(), anyDouble(), any());
        verify(solarService, never()).sunsetAzimuthDeg(anyDouble(), anyDouble(), any());
    }

    // --- fetchWeatherAndTriage: weather passes but tide fails ---

    @Test
    @DisplayName("fetchWeatherAndTriage() weather passes but tide alignment triages SEASCAPE")
    void fetchWeatherAndTriage_weatherPassesTideFails_triages() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        LocationEntity seascape = LocationEntity.builder()
                .id(3L).name("Bamburgh").lat(55.6).lon(-1.7)
                .locationType(new java.util.HashSet<>(Set.of(LocationType.SEASCAPE)))
                .tideType(new java.util.HashSet<>(Set.of(TideType.HIGH)))
                .build();

        when(solarService.sunsetUtc(55.6, -1.7, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(55.6, -1.7, date)).thenReturn(300);
        when(solarService.civilDuskUtc(55.6, -1.7, date))
                .thenReturn(LocalDateTime.of(2026, 6, 21, 21, 30));
        when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());
        when(tideAlignmentEvaluator.evaluate(any(), eq(Set.of(TideType.HIGH)), any(), any()))
                .thenReturn(Optional.of(new TriageResult("No high tide in window",
                        TriageRule.TIDE_MISALIGNED)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ForecastPreEvalResult result = forecastService.fetchWeatherAndTriage(
                seascape, date, TargetType.SUNSET, Set.of(TideType.HIGH),
                EvaluationModel.SONNET, true, null);

        assertThat(result.triaged()).isTrue();
        assertThat(result.triageReason()).contains("No high tide in window");
        ArgumentCaptor<ForecastEvaluationEntity> captor =
                ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSummary()).contains("tide not aligned");
        assertThat(captor.getValue().getRating()).isEqualTo(1);
    }

    // --- fetchWeatherAndTriage: result carries daysAhead and forecastResponse ---

    @Test
    @DisplayName("fetchWeatherAndTriage() result carries correct daysAhead and forecastResponse")
    void fetchWeatherAndTriage_resultCarriesDaysAheadAndResponse() {
        LocalDate tomorrow = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1);
        LocalDateTime sunset = tomorrow.atTime(20, 30);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        OpenMeteoForecastResponse forecastResp = new OpenMeteoForecastResponse();

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, tomorrow)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, tomorrow)).thenReturn(310);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, forecastResp));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

        ForecastPreEvalResult result = forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, tomorrow, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, true, null);

        assertThat(result.daysAhead()).isEqualTo(1);
        assertThat(result.forecastResponse()).isSameAs(forecastResp);
    }

    // --- runForecasts() event publishing and notification resilience ---

    @Test
    @DisplayName("runForecasts() swallows notification failure and still returns saved entities")
    void runForecasts_notificationFails_stillReturnsSavedEntities() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunrise = LocalDateTime.of(2026, 6, 21, 3, 30);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunrise, TargetType.SUNRISE);
        SunsetEvaluation evaluation = new SunsetEvaluation(null, 70, 65, "Good.");

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(evaluationService.evaluate(eq(data), any(EvaluationModel.class), any()))
                .thenReturn(evaluation);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("SMTP down"))
                .when(emailService).notify(any(), any(), any(), any());

        List<ForecastEvaluationEntity> results = forecastService.runForecasts(
                DURHAM_LOCATION, date, null, Set.of(), EvaluationModel.SONNET, null);

        assertThat(results).hasSize(2);
        verify(repository, times(2)).save(any());
    }

    @Test
    @DisplayName("runForecasts() publishes FAILED event before throwing on weather fetch error")
    void runForecasts_weatherFetchFails_publishesFailedEvent() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunrise = LocalDateTime.of(2026, 6, 21, 3, 30);
        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setId(88L);

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenThrow(new RuntimeException("Network error"));

        assertThatThrownBy(() -> forecastService.runForecasts(
                DURHAM_LOCATION, date, null, Set.of(), EvaluationModel.SONNET, jobRun))
                .isInstanceOf(WeatherDataFetchException.class);

        ArgumentCaptor<LocationTaskEvent> eventCaptor =
                ArgumentCaptor.forClass(LocationTaskEvent.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce())
                .publishEvent(eventCaptor.capture());

        List<LocationTaskEvent> events = eventCaptor.getAllValues();
        LocationTaskEvent failedEvent = events.stream()
                .filter(e -> e.getState() == LocationTaskState.FAILED)
                .findFirst()
                .orElse(null);
        assertThat(failedEvent).isNotNull();
        assertThat(failedEvent.getJobRunId()).isEqualTo(88L);
        assertThat(failedEvent.getLocationName()).isEqualTo(DURHAM);
        assertThat(failedEvent.getErrorMessage()).contains("Network error");
    }

    @Test
    @DisplayName("runForecasts() publishes FETCHING_WEATHER → FETCHING_CLOUD → EVALUATING → COMPLETE events")
    void runForecasts_success_publishesLifecycleEvents() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        SunsetEvaluation evaluation = new SunsetEvaluation(null, 70, 65, "Good.");
        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setId(99L);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(evaluationService.evaluate(eq(data), any(EvaluationModel.class), any()))
                .thenReturn(evaluation);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        forecastService.runForecasts(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, jobRun);

        ArgumentCaptor<LocationTaskEvent> eventCaptor =
                ArgumentCaptor.forClass(LocationTaskEvent.class);
        verify(eventPublisher, org.mockito.Mockito.atLeast(3))
                .publishEvent(eventCaptor.capture());

        List<LocationTaskState> states = eventCaptor.getAllValues().stream()
                .map(LocationTaskEvent::getState)
                .toList();
        assertThat(states).contains(
                LocationTaskState.FETCHING_WEATHER,
                LocationTaskState.FETCHING_CLOUD,
                LocationTaskState.EVALUATING,
                LocationTaskState.COMPLETE);
    }

    // --- Notification failure cascade ---

    @Test
    @DisplayName("evaluateAndPersist() saves entity even when all three notification services fail")
    void evaluateAndPersist_allNotificationsFail_entityStillSaved() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        SunsetEvaluation evaluation = new SunsetEvaluation(null, 90, 85, "Spectacular.");

        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, DURHAM_LOCATION, date,
                TargetType.SUNSET, sunset, 310, 0, EvaluationModel.SONNET, Set.of(),
                DURHAM + "|" + date + "|SUNSET", null);

        when(evaluationService.evaluate(eq(data), eq(EvaluationModel.SONNET), any()))
                .thenReturn(evaluation);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("SMTP down"))
                .when(emailService).notify(eq(evaluation), eq(DURHAM), eq(TargetType.SUNSET), eq(date));

        ForecastEvaluationEntity result = forecastService.evaluateAndPersist(preEval, null);

        assertThat(result).isNotNull();
        assertThat(result.getFierySkyPotential()).isEqualTo(90);
        verify(repository).save(any(ForecastEvaluationEntity.class));
    }

    // --- Event publishing — mutation kill tests ---

    @Nested
    @DisplayName("Event publishing")
    class EventPublishingTests {

        @Test
        @DisplayName("runForecasts() publishes FAILED event when weather data is null and jobRun is set")
        void runForecasts_nullWeather_withJobRun_publishesFailedEvent() {
            LocalDate date = LocalDate.of(2026, 6, 21);
            LocalDateTime sunrise = LocalDateTime.of(2026, 6, 21, 3, 30);
            JobRunEntity jobRun = new JobRunEntity();
            jobRun.setId(11L);

            when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
            when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), eq(jobRun)))
                    .thenReturn(new WeatherExtractionResult(null, null));

            assertThatThrownBy(() -> forecastService.runForecasts(
                    DURHAM_LOCATION, date, TargetType.SUNRISE, Set.of(), EvaluationModel.SONNET, jobRun))
                    .isInstanceOf(WeatherDataFetchException.class);

            ArgumentCaptor<LocationTaskEvent> captor = ArgumentCaptor.forClass(LocationTaskEvent.class);
            verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
            assertThat(captor.getAllValues())
                    .anyMatch(e -> e.getState() == LocationTaskState.FAILED);
        }

        @Test
        @DisplayName("runForecasts() publishes FETCHING_TIDES event in the success path")
        void runForecasts_success_publishesFetchingTidesEvent() {
            LocalDate date = LocalDate.of(2026, 6, 21);
            LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
            AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
            SunsetEvaluation evaluation = new SunsetEvaluation(null, 70, 65, "Good.");
            JobRunEntity jobRun = new JobRunEntity();
            jobRun.setId(22L);

            when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
            when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), any()))
                    .thenReturn(new WeatherExtractionResult(data, null));
            when(evaluationService.evaluate(any(), any(), any())).thenReturn(evaluation);
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            forecastService.runForecasts(DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                    EvaluationModel.SONNET, jobRun);

            ArgumentCaptor<LocationTaskEvent> captor = ArgumentCaptor.forClass(LocationTaskEvent.class);
            verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
            assertThat(captor.getAllValues())
                    .anyMatch(e -> e.getState() == LocationTaskState.FETCHING_TIDES);
        }

        @Test
        @DisplayName("fetchWeatherAndTriage() publishes FAILED event when weather fetch throws and jobRun is set")
        void fetchWeatherAndTriage_weatherThrows_withJobRun_publishesFailedEvent() {
            LocalDate date = LocalDate.of(2026, 6, 21);
            LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
            JobRunEntity jobRun = new JobRunEntity();
            jobRun.setId(33L);

            when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
            when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), any()))
                    .thenThrow(new RuntimeException("API timeout"));

            assertThatThrownBy(() -> forecastService.fetchWeatherAndTriage(
                    DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                    EvaluationModel.SONNET, true, jobRun))
                    .isInstanceOf(WeatherDataFetchException.class);

            ArgumentCaptor<LocationTaskEvent> captor = ArgumentCaptor.forClass(LocationTaskEvent.class);
            verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
            assertThat(captor.getAllValues())
                    .anyMatch(e -> e.getState() == LocationTaskState.FAILED);
        }

        @Test
        @DisplayName("fetchWeatherAndTriage() publishes FAILED event when weather data is null and jobRun is set")
        void fetchWeatherAndTriage_nullWeather_withJobRun_publishesFailedEvent() {
            LocalDate date = LocalDate.of(2026, 6, 21);
            LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
            JobRunEntity jobRun = new JobRunEntity();
            jobRun.setId(44L);

            when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
            when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), any()))
                    .thenReturn(new WeatherExtractionResult(null, null));

            assertThatThrownBy(() -> forecastService.fetchWeatherAndTriage(
                    DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                    EvaluationModel.SONNET, true, jobRun))
                    .isInstanceOf(WeatherDataFetchException.class);

            ArgumentCaptor<LocationTaskEvent> captor = ArgumentCaptor.forClass(LocationTaskEvent.class);
            verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
            assertThat(captor.getAllValues())
                    .anyMatch(e -> e.getState() == LocationTaskState.FAILED);
        }

        @Test
        @DisplayName("fetchWeatherAndTriage() publishes FETCHING_TIDES event when tideTypes is non-empty")
        void fetchWeatherAndTriage_nonEmptyTideTypes_withJobRun_publishesFetchingTidesEvent() {
            LocalDate date = LocalDate.of(2026, 6, 21);
            LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
            AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
            JobRunEntity jobRun = new JobRunEntity();
            jobRun.setId(55L);

            when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
            when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(300);
            when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), any()))
                    .thenReturn(new WeatherExtractionResult(data, null));
            when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

            forecastService.fetchWeatherAndTriage(
                    DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(TideType.HIGH),
                    EvaluationModel.SONNET, false, jobRun);

            ArgumentCaptor<LocationTaskEvent> captor = ArgumentCaptor.forClass(LocationTaskEvent.class);
            verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
            assertThat(captor.getAllValues())
                    .anyMatch(e -> e.getState() == LocationTaskState.FETCHING_TIDES);
        }

        @Test
        @DisplayName("fetchWeatherAndTriage() publishes TRIAGED event when weather triage fires and jobRun is set")
        void fetchWeatherAndTriage_weatherTriaged_withJobRun_publishesTriagedEvent() {
            LocalDate date = LocalDate.of(2026, 6, 21);
            LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
            AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
            JobRunEntity jobRun = new JobRunEntity();
            jobRun.setId(66L);

            when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
            when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(300);
            when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), any()))
                    .thenReturn(new WeatherExtractionResult(data, null));
            when(weatherTriageEvaluator.evaluate(any()))
                    .thenReturn(Optional.of(new TriageResult("100% low cloud", TriageRule.HIGH_CLOUD)));
            when(repository.save(any())).thenReturn(ForecastEvaluationEntity.builder().id(100L).build());

            forecastService.fetchWeatherAndTriage(
                    DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                    EvaluationModel.SONNET, false, jobRun);

            ArgumentCaptor<LocationTaskEvent> captor = ArgumentCaptor.forClass(LocationTaskEvent.class);
            verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
            assertThat(captor.getAllValues())
                    .anyMatch(e -> e.getState() == LocationTaskState.TRIAGED);
        }

        @Test
        @DisplayName("fetchWeatherAndTriage() publishes TRIAGED event when tide alignment fires and jobRun is set")
        void fetchWeatherAndTriage_tideTriaged_withJobRun_publishesTriagedEvent() {
            LocalDate date = LocalDate.of(2026, 6, 21);
            LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
            AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
            JobRunEntity jobRun = new JobRunEntity();
            jobRun.setId(77L);
            LocationEntity seascape = LocationEntity.builder()
                    .id(5L).name("Seaham").lat(54.8).lon(-1.3)
                    .locationType(new java.util.HashSet<>(Set.of(LocationType.SEASCAPE)))
                    .tideType(new java.util.HashSet<>(Set.of(TideType.HIGH)))
                    .build();

            when(solarService.sunsetUtc(54.8, -1.3, date)).thenReturn(sunset);
            when(solarService.sunsetAzimuthDeg(54.8, -1.3, date)).thenReturn(280);
            when(solarService.civilDuskUtc(54.8, -1.3, date)).thenReturn(sunset.plusMinutes(40));
            when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), any()))
                    .thenReturn(new WeatherExtractionResult(data, null));
            when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());
            when(tideAlignmentEvaluator.evaluate(any(), any(), any(), any()))
                    .thenReturn(Optional.of(new TriageResult("No high tide in window",
                            TriageRule.TIDE_MISALIGNED)));
            when(repository.save(any())).thenReturn(ForecastEvaluationEntity.builder().id(101L).build());

            forecastService.fetchWeatherAndTriage(
                    seascape, date, TargetType.SUNSET, Set.of(TideType.HIGH),
                    EvaluationModel.SONNET, true, jobRun);

            ArgumentCaptor<LocationTaskEvent> captor = ArgumentCaptor.forClass(LocationTaskEvent.class);
            verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
            assertThat(captor.getAllValues())
                    .anyMatch(e -> e.getState() == LocationTaskState.TRIAGED);
        }
    }

    // --- Azimuth selection — mutation kill tests ---

    @Nested
    @DisplayName("Azimuth selection in runForecasts()")
    class AzimuthSelectionTests {

        @Test
        @DisplayName("runForecasts() SUNRISE path stores sunriseAzimuthDeg value in entity")
        void runForecasts_sunrise_entityHasSunriseAzimuth() {
            LocalDate date = LocalDate.of(2026, 6, 21);
            LocalDateTime sunrise = LocalDateTime.of(2026, 6, 21, 3, 30);
            AtmosphericData data = buildAtmosphericData(sunrise, TargetType.SUNRISE);
            SunsetEvaluation evaluation = new SunsetEvaluation(null, 70, 75, "Good.");

            when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
            when(solarService.sunriseAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(52);
            when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), any()))
                    .thenReturn(new WeatherExtractionResult(data, null));
            when(evaluationService.evaluate(any(), any(), any())).thenReturn(evaluation);
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            List<ForecastEvaluationEntity> results = forecastService.runForecasts(
                    DURHAM_LOCATION, date, TargetType.SUNRISE, Set.of(), EvaluationModel.SONNET, null);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getAzimuthDeg()).isEqualTo(52);
            verify(solarService).sunriseAzimuthDeg(DURHAM_LAT, DURHAM_LON, date);
            verify(solarService, never()).sunsetAzimuthDeg(anyDouble(), anyDouble(), any());
        }

        @Test
        @DisplayName("runForecasts() SUNSET path stores sunsetAzimuthDeg value in entity")
        void runForecasts_sunset_entityHasSunsetAzimuth() {
            LocalDate date = LocalDate.of(2026, 6, 21);
            LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
            AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
            SunsetEvaluation evaluation = new SunsetEvaluation(null, 60, 55, "Moderate.");

            when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
            when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(285);
            when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), any()))
                    .thenReturn(new WeatherExtractionResult(data, null));
            when(evaluationService.evaluate(any(), any(), any())).thenReturn(evaluation);
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            List<ForecastEvaluationEntity> results = forecastService.runForecasts(
                    DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(), EvaluationModel.SONNET, null);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getAzimuthDeg()).isEqualTo(285);
            verify(solarService).sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date);
            verify(solarService, never()).sunriseAzimuthDeg(anyDouble(), anyDouble(), any());
        }
    }

    // --- Tide alignment window — mutation kill tests ---

    @Nested
    @DisplayName("Tide alignment window boundaries in fetchWeatherAndTriage()")
    class TideAlignmentWindowTests {

        private static final double SEA_LAT = 54.8;
        private static final double SEA_LON = -1.3;
        private static final String SEAHAM = "Seaham";

        private LocationEntity seahamSeascape() {
            return LocationEntity.builder()
                    .id(5L).name(SEAHAM).lat(SEA_LAT).lon(SEA_LON)
                    .locationType(new java.util.HashSet<>(Set.of(LocationType.SEASCAPE)))
                    .tideType(new java.util.HashSet<>(Set.of(TideType.HIGH)))
                    .build();
        }

        @Test
        @DisplayName("SUNRISE window: civilDawn is used as start and eventTime+1h as end (not civilDusk)")
        void fetchWeatherAndTriage_sunrise_tideWindow_usesCivilDawnStartAndEventPlusOneEnd() {
            LocalDate date = LocalDate.of(2026, 6, 21);
            LocalDateTime sunrise = LocalDateTime.of(2026, 6, 21, 3, 30);
            LocalDateTime civilDawn = LocalDateTime.of(2026, 6, 21, 2, 55);
            AtmosphericData data = buildAtmosphericData(sunrise, TargetType.SUNRISE);

            when(solarService.sunriseUtc(SEA_LAT, SEA_LON, date)).thenReturn(sunrise);
            when(solarService.sunriseAzimuthDeg(SEA_LAT, SEA_LON, date)).thenReturn(52);
            when(solarService.civilDawnUtc(SEA_LAT, SEA_LON, date)).thenReturn(civilDawn);
            when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), any()))
                    .thenReturn(new WeatherExtractionResult(data, null));
            when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());
            when(tideAlignmentEvaluator.evaluate(any(), any(), any(), any()))
                    .thenReturn(Optional.empty());

            forecastService.fetchWeatherAndTriage(
                    seahamSeascape(), date, TargetType.SUNRISE, Set.of(TideType.HIGH),
                    EvaluationModel.SONNET, true, null);

            verify(tideAlignmentEvaluator).evaluate(
                    any(), eq(Set.of(TideType.HIGH)), eq(civilDawn), eq(sunrise.plusHours(1)));
            verify(solarService, never()).civilDuskUtc(anyDouble(), anyDouble(), any());
        }

        @Test
        @DisplayName("SUNSET window: eventTime-1h is used as start and civilDusk as end (not civilDawn)")
        void fetchWeatherAndTriage_sunset_tideWindow_usesEventMinusOneStartAndCivilDuskEnd() {
            LocalDate date = LocalDate.of(2026, 6, 21);
            LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
            LocalDateTime civilDusk = LocalDateTime.of(2026, 6, 21, 21, 30);
            AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);

            when(solarService.sunsetUtc(SEA_LAT, SEA_LON, date)).thenReturn(sunset);
            when(solarService.sunsetAzimuthDeg(SEA_LAT, SEA_LON, date)).thenReturn(300);
            when(solarService.civilDuskUtc(SEA_LAT, SEA_LON, date)).thenReturn(civilDusk);
            when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), any()))
                    .thenReturn(new WeatherExtractionResult(data, null));
            when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());
            when(tideAlignmentEvaluator.evaluate(any(), any(), any(), any()))
                    .thenReturn(Optional.empty());

            forecastService.fetchWeatherAndTriage(
                    seahamSeascape(), date, TargetType.SUNSET, Set.of(TideType.HIGH),
                    EvaluationModel.SONNET, true, null);

            verify(tideAlignmentEvaluator).evaluate(
                    any(), eq(Set.of(TideType.HIGH)), eq(sunset.minusHours(1)), eq(civilDusk));
            verify(solarService, never()).civilDawnUtc(anyDouble(), anyDouble(), any());
        }
    }

    // --- buildEntity field mapping — mutation kill tests ---

    @Nested
    @DisplayName("buildEntity() field mapping via evaluateAndPersist()")
    class BuildEntityFieldMappingTests {

        private static final LocalDate DATE = LocalDate.of(2026, 6, 21);
        private static final LocalDateTime SUNSET = LocalDateTime.of(2026, 6, 21, 20, 47);

        private ForecastPreEvalResult preEvalWith(AtmosphericData data) {
            return new ForecastPreEvalResult(
                    false, null, data, DURHAM_LOCATION, DATE,
                    TargetType.SUNSET, SUNSET, 310, 0, EvaluationModel.SONNET, Set.of(),
                    DURHAM + "|" + DATE + "|SUNSET", null);
        }

        @Test
        @DisplayName("non-null TideSnapshot maps tide fields into entity")
        void evaluateAndPersist_withTide_tideFieldsMapped() {
            LocalDateTime highTideTime = LocalDateTime.of(2026, 6, 21, 18, 30);
            LocalDateTime lowTideTime = LocalDateTime.of(2026, 6, 22, 0, 45);
            TideSnapshot tide = new TideSnapshot(
                    TideState.HIGH, highTideTime, new BigDecimal("4.5"),
                    lowTideTime, new BigDecimal("0.8"), true,
                    null, null, null, null, null, null);
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(SUNSET).targetType(TargetType.SUNSET).tide(tide).build();

            when(evaluationService.evaluate(any(), any(), any()))
                    .thenReturn(new SunsetEvaluation(null, 75, 70, "Good."));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ForecastEvaluationEntity result = forecastService.evaluateAndPersist(preEvalWith(data), null);

            assertThat(result.getTideState()).isEqualTo(TideState.HIGH);
            assertThat(result.getTideAligned()).isTrue();
            assertThat(result.getNextHighTideTime()).isEqualTo(highTideTime);
            assertThat(result.getNextHighTideHeightMetres()).isEqualByComparingTo(new BigDecimal("4.5"));
        }

        @Test
        @DisplayName("null TideSnapshot leaves all tide fields null in entity")
        void evaluateAndPersist_withNullTide_tideFieldsNull() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(SUNSET).targetType(TargetType.SUNSET).build();

            when(evaluationService.evaluate(any(), any(), any()))
                    .thenReturn(new SunsetEvaluation(null, 75, 70, "Good."));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ForecastEvaluationEntity result = forecastService.evaluateAndPersist(preEvalWith(data), null);

            assertThat(result.getTideState()).isNull();
            assertThat(result.getTideAligned()).isNull();
            assertThat(result.getNextHighTideTime()).isNull();
        }

        @Test
        @DisplayName("non-null DirectionalCloudData maps solar cloud fields into entity")
        void evaluateAndPersist_withDirectionalCloud_solarCloudFieldsMapped() {
            // solarLow=35, solarMid=45, solarHigh=20, antisolarLow=40, antisolarMid=50, antisolarHigh=15,
            // farSolarLow=55
            DirectionalCloudData dc = new DirectionalCloudData(35, 45, 20, 40, 50, 15, 55);
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(SUNSET).targetType(TargetType.SUNSET)
                    .directionalCloud(dc).build();

            when(evaluationService.evaluate(any(), any(), any()))
                    .thenReturn(new SunsetEvaluation(null, 75, 70, "Good."));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ForecastEvaluationEntity result = forecastService.evaluateAndPersist(preEvalWith(data), null);

            assertThat(result.getSolarLowCloud()).isEqualTo(35);
            assertThat(result.getSolarMidCloud()).isEqualTo(45);
            assertThat(result.getFarSolarLowCloud()).isEqualTo(55);
        }

        @Test
        @DisplayName("null DirectionalCloudData leaves all solar cloud fields null in entity")
        void evaluateAndPersist_withNullDirectionalCloud_solarCloudFieldsNull() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(SUNSET).targetType(TargetType.SUNSET).build();

            when(evaluationService.evaluate(any(), any(), any()))
                    .thenReturn(new SunsetEvaluation(null, 75, 70, "Good."));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ForecastEvaluationEntity result = forecastService.evaluateAndPersist(preEvalWith(data), null);

            assertThat(result.getSolarLowCloud()).isNull();
            assertThat(result.getFarSolarLowCloud()).isNull();
        }

        @Test
        @DisplayName("non-null CloudApproachData with slots maps trend and upwind fields into entity")
        void evaluateAndPersist_withCloudApproach_trendAndUpwindFieldsMapped() {
            SolarCloudTrend trend = new SolarCloudTrend(List.of(
                    new SolarCloudSlot(3, 10),
                    new SolarCloudSlot(2, 25),
                    new SolarCloudSlot(0, 40)));
            UpwindCloudSample upwind = new UpwindCloudSample(80, 225, 65, 35);
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(SUNSET).targetType(TargetType.SUNSET)
                    .cloudApproach(new CloudApproachData(trend, upwind)).build();

            when(evaluationService.evaluate(any(), any(), any()))
                    .thenReturn(new SunsetEvaluation(null, 75, 70, "Good."));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ForecastEvaluationEntity result = forecastService.evaluateAndPersist(preEvalWith(data), null);

            // solarTrend: first slot=10 (earliest), last slot=40 (event time)
            // isBuilding: peak(40) - earliest(10) = 30 ≥ 20 → true
            assertThat(result.getSolarTrendEarliestLowCloud()).isEqualTo(10);
            assertThat(result.getSolarTrendEventLowCloud()).isEqualTo(40);
            assertThat(result.getSolarTrendBuilding()).isTrue();
            assertThat(result.getUpwindCurrentLowCloud()).isEqualTo(65);
            assertThat(result.getUpwindEventLowCloud()).isEqualTo(35);
            assertThat(result.getUpwindDistanceKm()).isEqualTo(80);
        }

        @Test
        @DisplayName("null CloudApproachData leaves all trend and upwind fields null in entity")
        void evaluateAndPersist_withNullCloudApproach_trendFieldsNull() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(SUNSET).targetType(TargetType.SUNSET).build();

            when(evaluationService.evaluate(any(), any(), any()))
                    .thenReturn(new SunsetEvaluation(null, 75, 70, "Good."));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ForecastEvaluationEntity result = forecastService.evaluateAndPersist(preEvalWith(data), null);

            assertThat(result.getSolarTrendBuilding()).isNull();
            assertThat(result.getUpwindCurrentLowCloud()).isNull();
        }

        @Test
        @DisplayName("non-null StormSurgeBreakdown maps surge fields into entity")
        void evaluateAndPersist_withSurge_surgeFieldsMapped() {
            StormSurgeBreakdown surge = new StormSurgeBreakdown(
                    0.08, 0.05, 0.13, 1000.0, 8.0, 270.0, 0.9, TideRiskLevel.LOW, "Low surge");
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(SUNSET).targetType(TargetType.SUNSET)
                    .build().withSurge(surge, 3.5, 3.3);

            when(evaluationService.evaluate(any(), any(), any()))
                    .thenReturn(new SunsetEvaluation(null, 75, 70, "Good."));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ForecastEvaluationEntity result = forecastService.evaluateAndPersist(preEvalWith(data), null);

            assertThat(result.getSurgeTotalMetres()).isEqualTo(0.13);
            assertThat(result.getSurgePressureMetres()).isEqualTo(0.08);
            assertThat(result.getSurgeWindMetres()).isEqualTo(0.05);
            assertThat(result.getSurgeRiskLevel()).isEqualTo("LOW");
            assertThat(result.getSurgeAdjustedRangeMetres()).isEqualTo(3.5);
        }

        @Test
        @DisplayName("null surge data leaves all surge fields null in entity")
        void evaluateAndPersist_withNullSurge_surgeFieldsNull() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(SUNSET).targetType(TargetType.SUNSET).build();

            when(evaluationService.evaluate(any(), any(), any()))
                    .thenReturn(new SunsetEvaluation(null, 75, 70, "Good."));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ForecastEvaluationEntity result = forecastService.evaluateAndPersist(preEvalWith(data), null);

            assertThat(result.getSurgeTotalMetres()).isNull();
            assertThat(result.getSurgeRiskLevel()).isNull();
            assertThat(result.getSurgeAdjustedRangeMetres()).isNull();
        }

        @Test
        @DisplayName("non-null inversionScore maps inversion fields from evaluation into entity")
        void evaluateAndPersist_withInversionScore_inversionFieldsFromEvaluation() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(SUNSET).targetType(TargetType.SUNSET)
                    .inversionScore(7.5).build();
            SunsetEvaluation evaluation = new SunsetEvaluation(
                    null, 75, 70, "Good.", null, null, null, 7, "STRONG");

            when(evaluationService.evaluate(any(), any(), any())).thenReturn(evaluation);
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ForecastEvaluationEntity result = forecastService.evaluateAndPersist(preEvalWith(data), null);

            assertThat(result.getInversionScore()).isEqualTo(7);
            assertThat(result.getInversionPotential()).isEqualTo("STRONG");
        }

        @Test
        @DisplayName("null inversionScore leaves both inversion fields null in entity regardless of evaluation")
        void evaluateAndPersist_withNullInversionScore_inversionFieldsNull() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(SUNSET).targetType(TargetType.SUNSET).build(); // inversionScore defaults to null
            SunsetEvaluation evaluation = new SunsetEvaluation(
                    null, 75, 70, "Good.", null, null, null, 8, "STRONG");

            when(evaluationService.evaluate(any(), any(), any())).thenReturn(evaluation);
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ForecastEvaluationEntity result = forecastService.evaluateAndPersist(preEvalWith(data), null);

            assertThat(result.getInversionScore()).isNull();
            assertThat(result.getInversionPotential()).isNull();
        }
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
