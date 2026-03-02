package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ModelTestResultEntity;
import com.gregochr.goldenhour.entity.ModelTestRunEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.service.ModelTestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link ModelTestController}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModelTestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ModelTestService modelTestService;

    @Test
    @DisplayName("POST /api/model-test/run requires ADMIN role")
    @WithMockUser(roles = "PRO_USER")
    void runTest_requiresAdminRole() throws Exception {
        mockMvc.perform(post("/api/model-test/run")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/model-test/run returns test run for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void runTest_returnsRunForAdmin() throws Exception {
        ModelTestRunEntity run = ModelTestRunEntity.builder()
                .id(1L)
                .startedAt(LocalDateTime.now())
                .targetDate(LocalDate.of(2026, 3, 1))
                .targetType(TargetType.SUNSET)
                .regionsCount(2)
                .succeeded(6)
                .failed(0)
                .totalCostPence(300)
                .build();
        when(modelTestService.runTest()).thenReturn(run);

        mockMvc.perform(post("/api/model-test/run")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionsCount").value(2))
                .andExpect(jsonPath("$.succeeded").value(6))
                .andExpect(jsonPath("$.totalCostPence").value(300));
    }

    @Test
    @DisplayName("GET /api/model-test/runs returns recent runs for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void getRecentRuns_returnsRunsForAdmin() throws Exception {
        ModelTestRunEntity run = ModelTestRunEntity.builder()
                .id(1L)
                .startedAt(LocalDateTime.now())
                .targetDate(LocalDate.of(2026, 3, 1))
                .targetType(TargetType.SUNSET)
                .regionsCount(2)
                .succeeded(6)
                .failed(0)
                .build();
        when(modelTestService.getRecentRuns()).thenReturn(List.of(run));

        mockMvc.perform(get("/api/model-test/runs")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].regionsCount").value(2));
    }

    @Test
    @DisplayName("GET /api/model-test/runs requires ADMIN role")
    @WithMockUser(roles = "LITE_USER")
    void getRecentRuns_requiresAdminRole() throws Exception {
        mockMvc.perform(get("/api/model-test/runs")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/model-test/results returns results for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void getResults_returnsResultsForAdmin() throws Exception {
        ModelTestResultEntity result = ModelTestResultEntity.builder()
                .id(1L)
                .testRunId(1L)
                .regionId(1L)
                .regionName("North East")
                .locationId(1L)
                .locationName("Durham")
                .targetDate(LocalDate.of(2026, 3, 1))
                .targetType(TargetType.SUNSET)
                .evaluationModel(EvaluationModel.HAIKU)
                .rating(4)
                .fierySkyPotential(65)
                .goldenHourPotential(70)
                .succeeded(true)
                .createdAt(LocalDateTime.now())
                .build();
        when(modelTestService.getResults(1L)).thenReturn(List.of(result));

        mockMvc.perform(get("/api/model-test/results?testRunId=1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].evaluationModel").value("HAIKU"))
                .andExpect(jsonPath("$[0].fierySkyPotential").value(65));
    }

    @Test
    @DisplayName("GET /api/model-test/results requires ADMIN role")
    @WithMockUser(roles = "PRO_USER")
    void getResults_requiresAdminRole() throws Exception {
        mockMvc.perform(get("/api/model-test/results?testRunId=1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // --- run-location endpoint tests ---

    @Test
    @DisplayName("POST /api/model-test/run-location returns test run for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void runTestForLocation_returnsRunForAdmin() throws Exception {
        ModelTestRunEntity run = ModelTestRunEntity.builder()
                .id(1L)
                .startedAt(LocalDateTime.now())
                .targetDate(LocalDate.of(2026, 3, 1))
                .targetType(TargetType.SUNSET)
                .regionsCount(1)
                .succeeded(3)
                .failed(0)
                .totalCostPence(150)
                .build();
        when(modelTestService.runTestForLocation(1L)).thenReturn(run);

        mockMvc.perform(post("/api/model-test/run-location?locationId=1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionsCount").value(1))
                .andExpect(jsonPath("$.succeeded").value(3))
                .andExpect(jsonPath("$.totalCostPence").value(150));
    }

    @Test
    @DisplayName("POST /api/model-test/run-location requires ADMIN role")
    @WithMockUser(roles = "PRO_USER")
    void runTestForLocation_requiresAdminRole() throws Exception {
        mockMvc.perform(post("/api/model-test/run-location?locationId=1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/model-test/run-location without locationId returns 400")
    @WithMockUser(roles = "ADMIN")
    void runTestForLocation_missingLocationId() throws Exception {
        mockMvc.perform(post("/api/model-test/run-location")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // --- rerun endpoint tests ---

    @Test
    @DisplayName("POST /api/model-test/rerun returns test run for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void rerunTest_returnsRunForAdmin() throws Exception {
        ModelTestRunEntity run = ModelTestRunEntity.builder()
                .id(2L)
                .startedAt(LocalDateTime.now())
                .targetDate(LocalDate.of(2026, 3, 1))
                .targetType(TargetType.SUNSET)
                .regionsCount(1)
                .succeeded(3)
                .failed(0)
                .totalCostPence(150)
                .build();
        when(modelTestService.rerunTest(1L)).thenReturn(run);

        mockMvc.perform(post("/api/model-test/rerun?testRunId=1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.succeeded").value(3));
    }

    @Test
    @DisplayName("POST /api/model-test/rerun requires ADMIN role")
    @WithMockUser(roles = "PRO_USER")
    void rerunTest_requiresAdminRole() throws Exception {
        mockMvc.perform(post("/api/model-test/rerun?testRunId=1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}
