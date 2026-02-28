package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.service.ForecastCommandExecutor;
import com.gregochr.goldenhour.service.ForecastCommandFactory;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ScheduledForecastService;
import org.junit.jupiter.api.BeforeEach;
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

    @MockBean
    private ForecastCommandFactory commandFactory;

    @MockBean
    private ForecastCommandExecutor commandExecutor;

    @MockBean
    private LocationService locationService;

    @MockBean
    private ScheduledForecastService scheduledForecastService;

    @BeforeEach
    void setUp() {
        LocationEntity durham = LocationEntity.builder()
                .id(1L).name("Durham UK").lat(54.7753).lon(-1.5849).build();
        when(locationService.findAllEnabled()).thenReturn(List.of(durham));
        when(commandFactory.create(any(), any(boolean.class)))
                .thenReturn(new com.gregochr.goldenhour.service.ForecastCommand(
                        com.gregochr.goldenhour.entity.RunType.SHORT_TERM,
                        List.of(), null, null, true));
        when(commandFactory.create(any(), any(boolean.class), any(), any()))
                .thenReturn(new com.gregochr.goldenhour.service.ForecastCommand(
                        com.gregochr.goldenhour.entity.RunType.SHORT_TERM,
                        List.of(), null, null, true));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast returns 200 with evaluations for all configured locations")
    void getForecasts_returnsEvaluationsForConfiguredLocations() throws Exception {
        ForecastEvaluationEntity entity = buildEntity("Durham UK", LocalDate.of(2026, 2, 20));
        when(forecastEvaluationRepository
                .findByLocationNameAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                        eq("Durham UK"), any(LocalDate.class), any(LocalDate.class)))
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
    @DisplayName("POST /api/forecast/run as ADMIN returns 202 Accepted")
    void runForecast_asAdmin_noBody_returns202() throws Exception {
        mockMvc.perform(post("/api/forecast/run"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Forecast run started"))
                .andExpect(jsonPath("$.runType").value("SHORT_TERM"));
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
    @DisplayName("POST /api/forecast/run with multiple dates returns 202 Accepted")
    void runForecast_multipleDates_returns202() throws Exception {
        mockMvc.perform(post("/api/forecast/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dates\":[\"2026-03-01\",\"2026-03-02\"]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Forecast run started"));
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

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run/very-short-term as ADMIN returns 202 Accepted")
    void runVeryShortTermForecast_asAdmin_returns202() throws Exception {
        mockMvc.perform(post("/api/forecast/run/very-short-term"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Forecast run started"))
                .andExpect(jsonPath("$.runType").value("VERY_SHORT_TERM"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/forecast/run/very-short-term as non-admin returns 403")
    void runVeryShortTermForecast_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/forecast/run/very-short-term"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run/short-term as ADMIN returns 202 Accepted")
    void runShortTermForecast_asAdmin_returns202() throws Exception {
        mockMvc.perform(post("/api/forecast/run/short-term"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Forecast run started"))
                .andExpect(jsonPath("$.runType").value("SHORT_TERM"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/forecast/run/short-term as non-admin returns 403")
    void runShortTermForecast_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/forecast/run/short-term"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run/long-term as ADMIN returns 202 Accepted")
    void runLongTermForecast_asAdmin_returns202() throws Exception {
        mockMvc.perform(post("/api/forecast/run/long-term"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Forecast run started"))
                .andExpect(jsonPath("$.runType").value("LONG_TERM"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/forecast/run/long-term as non-admin returns 403")
    void runLongTermForecast_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/forecast/run/long-term"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast/history returns 200 for all locations when no location filter is given")
    void getHistory_validRange_noLocation_returnsEvaluationsForAllLocations() throws Exception {
        ForecastEvaluationEntity entity = buildEntity("Durham UK", LocalDate.of(2026, 1, 15));
        when(forecastEvaluationRepository
                .findByLocationNameAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                        eq("Durham UK"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(entity));

        mockMvc.perform(get("/api/forecast/history")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationName").value("Durham UK"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run with maxDays returns 202 Accepted")
    void runForecast_withMaxDays_returns202() throws Exception {
        mockMvc.perform(post("/api/forecast/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dates\":[\"2026-03-01\",\"2026-03-02\",\"2026-03-03\"]}")
                        .param("maxDays", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Forecast run started"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run with maxLocations returns 202 Accepted")
    void runForecast_withMaxLocations_returns202() throws Exception {
        mockMvc.perform(post("/api/forecast/run")
                        .param("maxLocations", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Forecast run started"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run/tide as ADMIN returns 202 Accepted")
    void refreshTideData_asAdmin_returns202() throws Exception {
        mockMvc.perform(post("/api/forecast/run/tide"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Tide refresh started"))
                .andExpect(jsonPath("$.runType").value("TIDE"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/forecast/run/tide as non-admin returns 403")
    void refreshTideData_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/forecast/run/tide"))
                .andExpect(status().isForbidden());
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

}
