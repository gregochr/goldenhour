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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    private SolarService solarService;
    @Mock
    private OpenMeteoService openMeteoService;
    @Mock
    private TideService tideService;
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

    @Test
    @DisplayName("runForecasts() evaluates sunrise and sunset and saves two entities")
    void runForecasts_savesOneEntityPerTargetType() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunrise = LocalDateTime.of(2026, 6, 21, 3, 30);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);

        AtmosphericData forecastData = buildAtmosphericData(sunrise, TargetType.SUNRISE);
        SunsetEvaluation evaluation = new SunsetEvaluation(4, "Good conditions.");
        ForecastEvaluationEntity savedEntity = ForecastEvaluationEntity.builder().id(1L).build();

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getAtmosphericData(any(ForecastRequest.class), any()))
                .thenReturn(forecastData);
        when(evaluationService.evaluate(forecastData)).thenReturn(evaluation);
        when(repository.save(any())).thenReturn(savedEntity);

        List<ForecastEvaluationEntity> results = forecastService.runForecasts(
                DURHAM, DURHAM_LAT, DURHAM_LON, date, null, java.util.Set.of());

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
        SunsetEvaluation evaluation = new SunsetEvaluation(3, "Moderate potential.");
        ForecastEvaluationEntity savedEntity = ForecastEvaluationEntity.builder().id(2L).build();

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getAtmosphericData(any(ForecastRequest.class), any()))
                .thenReturn(forecastData);
        when(evaluationService.evaluate(forecastData)).thenReturn(evaluation);
        when(repository.save(any())).thenReturn(savedEntity);

        forecastService.runForecasts(DURHAM, DURHAM_LAT, DURHAM_LON, date);

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
        SunsetEvaluation evaluation = new SunsetEvaluation(5, "Exceptional setup.");

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getAtmosphericData(any(ForecastRequest.class), any()))
                .thenReturn(forecastData);
        when(evaluationService.evaluate(forecastData)).thenReturn(evaluation);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        forecastService.runForecasts(DURHAM, DURHAM_LAT, DURHAM_LON, date);

        ArgumentCaptor<ForecastEvaluationEntity> captor =
                ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
        verify(repository, times(2)).save(captor.capture());

        ForecastEvaluationEntity first = captor.getAllValues().get(0);
        assertThat(first.getRating()).isEqualTo(5);
        assertThat(first.getSummary()).isEqualTo("Exceptional setup.");
        assertThat(first.getLocationName()).isEqualTo(DURHAM);
        assertThat(first.getTargetDate()).isEqualTo(date);
        assertThat(first.getSolarEventTime()).isEqualTo(sunrise);

        ForecastEvaluationEntity second = captor.getAllValues().get(1);
        assertThat(second.getSolarEventTime()).isEqualTo(sunset);
    }

    private AtmosphericData buildAtmosphericData(LocalDateTime eventTime, TargetType targetType) {
        return new AtmosphericData("Durham UK", eventTime, targetType,
                15, 55, 30, 22000, new BigDecimal("4.20"), 225, new BigDecimal("0.00"),
                62, 3, 1200, new BigDecimal("180.00"),
                new BigDecimal("8.50"), new BigDecimal("2.10"), new BigDecimal("0.120"),
                null, null, null, null, null, null);
    }
}
