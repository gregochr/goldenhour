package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.exception.WeatherDataFetchException;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TriageResult;
import com.gregochr.goldenhour.model.TriageRule;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
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
        lenient().when(augmentor.augmentWithCloudApproach(any(), anyDouble(), anyDouble(),
                anyInt(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithTideData(any(), any(), any(), any()))
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
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(forecastData, null));
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
        verify(repository).save(any());
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
        verify(eventPublisher, times(2)).publishEvent(any());
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
        verify(repository).save(any());
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
        verify(evaluationService).evaluate(eq(data), eq(EvaluationModel.SONNET), any());
        verify(repository).save(any());
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
        verify(repository).save(any());
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
        when(openMeteoService.getAtmosphericDataFromCache(any(), any(), eq(prefetched)))
                .thenReturn(cached);
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(java.util.Optional.empty());

        forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, true, null, prefetched);

        verify(openMeteoService).getAtmosphericDataFromCache(any(), any(), eq(prefetched));
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
                EvaluationModel.SONNET, true, null, null);

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
                EvaluationModel.SONNET, true, null, prefetched))
                .isInstanceOf(WeatherDataFetchException.class)
                .hasMessageContaining("Weather data fetch failed");
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
