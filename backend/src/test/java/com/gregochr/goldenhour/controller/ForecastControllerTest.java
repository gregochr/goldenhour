package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.ForecastService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link ForecastController}.
 *
 * <p>Loads the full application context with test configuration and mocks only
 * the direct service dependencies.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ForecastControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ForecastEvaluationRepository forecastEvaluationRepository;

    @MockBean
    private ForecastService forecastService;

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast returns 200 with SONNET evaluations for a standard user")
    void getForecasts_returnsEvaluationsForConfiguredLocations() throws Exception {
        ForecastEvaluationEntity entity = buildEntity("Durham UK", LocalDate.of(2026, 2, 20));
        when(forecastEvaluationRepository
                .findByLocationAndDateRangeAndModel(
                        eq("Durham UK"), any(LocalDate.class), any(LocalDate.class),
                        eq(EvaluationModel.SONNET)))
                .thenReturn(List.of(entity));

        mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationName").value("Durham UK"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast/history returns 200 for a valid date range with location filter")
    void getHistory_validRange_returnsEvaluations() throws Exception {
        ForecastEvaluationEntity entity = buildEntity("Durham UK", LocalDate.of(2026, 1, 15));
        when(forecastEvaluationRepository
                .findByLocationNameAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                        eq("Durham UK"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(entity));

        mockMvc.perform(get("/api/forecast/history")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31")
                        .param("location", "Durham UK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationName").value("Durham UK"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast/history returns 400 when 'from' is after 'to'")
    void getHistory_fromAfterTo_returns400() throws Exception {
        mockMvc.perform(get("/api/forecast/history")
                        .param("from", "2026-02-01")
                        .param("to", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run as ADMIN with no body runs both models for all configured locations")
    void runForecast_asAdmin_noBody_runsBothModels() throws Exception {
        ForecastEvaluationEntity sonnetEntity = buildEntity("Durham UK", LocalDate.of(2026, 2, 20));
        ForecastEvaluationEntity haikuEntity = buildHaikuEntity("Durham UK", LocalDate.of(2026, 2, 20));
        when(forecastService.runForecasts(
                        anyString(), anyDouble(), anyDouble(), any(), any(LocalDate.class), any(),
                        any(), eq(EvaluationModel.SONNET), any()))
                .thenReturn(List.of(sonnetEntity));
        when(forecastService.runForecasts(
                        anyString(), anyDouble(), anyDouble(), any(), any(LocalDate.class), any(),
                        any(), eq(EvaluationModel.HAIKU), any()))
                .thenReturn(List.of(haikuEntity));

        mockMvc.perform(post("/api/forecast/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/forecast/run as non-admin returns 403")
    void runForecast_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/forecast/run"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run returns 400 when the specified location is not configured")
    void runForecast_unknownLocation_returns400() throws Exception {
        mockMvc.perform(post("/api/forecast/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"location\":\"Unknown City\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run with multiple dates runs both models for each date")
    void runForecast_multipleDates_runsBothModelsForEachDate() throws Exception {
        ForecastEvaluationEntity day1Sonnet = buildEntity("Durham UK", LocalDate.of(2026, 3, 1));
        ForecastEvaluationEntity day1Haiku = buildHaikuEntity("Durham UK", LocalDate.of(2026, 3, 1));
        ForecastEvaluationEntity day2Sonnet = buildEntity("Durham UK", LocalDate.of(2026, 3, 2));
        ForecastEvaluationEntity day2Haiku = buildHaikuEntity("Durham UK", LocalDate.of(2026, 3, 2));
        when(forecastService.runForecasts(
                        anyString(), anyDouble(), anyDouble(), any(),
                        eq(LocalDate.of(2026, 3, 1)), any(), any(), eq(EvaluationModel.SONNET), any()))
                .thenReturn(List.of(day1Sonnet));
        when(forecastService.runForecasts(
                        anyString(), anyDouble(), anyDouble(), any(),
                        eq(LocalDate.of(2026, 3, 1)), any(), any(), eq(EvaluationModel.HAIKU), any()))
                .thenReturn(List.of(day1Haiku));
        when(forecastService.runForecasts(
                        anyString(), anyDouble(), anyDouble(), any(),
                        eq(LocalDate.of(2026, 3, 2)), any(), any(), eq(EvaluationModel.SONNET), any()))
                .thenReturn(List.of(day2Sonnet));
        when(forecastService.runForecasts(
                        anyString(), anyDouble(), anyDouble(), any(),
                        eq(LocalDate.of(2026, 3, 2)), any(), any(), eq(EvaluationModel.HAIKU), any()))
                .thenReturn(List.of(day2Haiku));

        mockMvc.perform(post("/api/forecast/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dates\":[\"2026-03-01\",\"2026-03-02\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast/compare returns 200 with evaluations for valid params")
    void getCompare_validParams_returnsEvaluations() throws Exception {
        ForecastEvaluationEntity entity = buildEntity("Durham UK", LocalDate.of(2026, 2, 28));
        when(forecastEvaluationRepository
                .findByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                        eq("Durham UK"), eq(LocalDate.of(2026, 2, 28)), eq(TargetType.SUNSET)))
                .thenReturn(List.of(entity));

        mockMvc.perform(get("/api/forecast/compare")
                        .param("location", "Durham UK")
                        .param("date", "2026-02-28")
                        .param("targetType", "SUNSET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationName").value("Durham UK"))
                .andExpect(jsonPath("$[0].fierySkyPotential").value(72))
                .andExpect(jsonPath("$[0].goldenHourPotential").value(80));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast/compare returns 400 when required params are missing")
    void getCompare_missingParams_returns400() throws Exception {
        mockMvc.perform(get("/api/forecast/compare")
                        .param("location", "Durham UK"))
                .andExpect(status().isBadRequest());
    }

    private ForecastEvaluationEntity buildEntity(String locationName, LocalDate targetDate) {
        return ForecastEvaluationEntity.builder()
                .id(1L)
                .locationName(locationName)
                .locationLat(BigDecimal.valueOf(54.7753))
                .locationLon(BigDecimal.valueOf(-1.5849))
                .targetDate(targetDate)
                .targetType(TargetType.SUNSET)
                .forecastRunAt(LocalDateTime.of(2026, 2, 20, 12, 0))
                .daysAhead(0)
                .evaluationModel(EvaluationModel.SONNET)
                .fierySkyPotential(72)
                .goldenHourPotential(80)
                .summary("Good colour potential.")
                .solarEventTime(LocalDateTime.of(2026, 2, 20, 16, 45))
                .build();
    }

    private ForecastEvaluationEntity buildHaikuEntity(String locationName, LocalDate targetDate) {
        return ForecastEvaluationEntity.builder()
                .id(2L)
                .locationName(locationName)
                .locationLat(BigDecimal.valueOf(54.7753))
                .locationLon(BigDecimal.valueOf(-1.5849))
                .targetDate(targetDate)
                .targetType(TargetType.SUNSET)
                .forecastRunAt(LocalDateTime.of(2026, 2, 20, 12, 0))
                .daysAhead(0)
                .evaluationModel(EvaluationModel.HAIKU)
                .rating(4)
                .summary("Good colour potential.")
                .solarEventTime(LocalDateTime.of(2026, 2, 20, 16, 45))
                .build();
    }
}
