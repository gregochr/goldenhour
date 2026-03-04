package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.PromptTestResultEntity;
import com.gregochr.goldenhour.entity.PromptTestRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.service.GitInfoService;
import com.gregochr.goldenhour.service.PromptTestService;
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
 * Integration tests for {@link PromptTestController}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PromptTestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PromptTestService promptTestService;

    @MockitoBean
    private GitInfoService gitInfoService;

    // --- POST /api/prompt-test/run ---

    @Test
    @DisplayName("POST /api/prompt-test/run returns test run for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void runTest_returnsRunForAdmin() throws Exception {
        PromptTestRunEntity run = PromptTestRunEntity.builder()
                .id(1L)
                .startedAt(LocalDateTime.now())
                .targetDate(LocalDate.of(2026, 3, 1))
                .targetType(TargetType.SUNSET)
                .evaluationModel(EvaluationModel.HAIKU)
                .locationsCount(12)
                .succeeded(12)
                .failed(0)
                .totalCostPence(600)
                .gitCommitHash("abc1234")
                .gitBranch("main")
                .build();
        when(promptTestService.runTest(EvaluationModel.HAIKU, RunType.SHORT_TERM)).thenReturn(run);

        mockMvc.perform(post("/api/prompt-test/run?model=HAIKU&runType=SHORT_TERM")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationsCount").value(12))
                .andExpect(jsonPath("$.succeeded").value(12))
                .andExpect(jsonPath("$.evaluationModel").value("HAIKU"))
                .andExpect(jsonPath("$.gitCommitHash").value("abc1234"));
    }

    @Test
    @DisplayName("POST /api/prompt-test/run requires ADMIN role")
    @WithMockUser(roles = "PRO_USER")
    void runTest_requiresAdminRole() throws Exception {
        mockMvc.perform(post("/api/prompt-test/run?model=HAIKU&runType=SHORT_TERM")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/prompt-test/run without model param returns 400")
    @WithMockUser(roles = "ADMIN")
    void runTest_missingModelParam() throws Exception {
        mockMvc.perform(post("/api/prompt-test/run")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/prompt-test/run returns 401 when unauthenticated")
    void runTest_unauthenticated() throws Exception {
        mockMvc.perform(post("/api/prompt-test/run?model=HAIKU&runType=SHORT_TERM")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // --- POST /api/prompt-test/replay ---

    @Test
    @DisplayName("POST /api/prompt-test/replay returns test run for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void replayTest_returnsRunForAdmin() throws Exception {
        PromptTestRunEntity run = PromptTestRunEntity.builder()
                .id(2L)
                .startedAt(LocalDateTime.now())
                .targetDate(LocalDate.of(2026, 3, 1))
                .targetType(TargetType.SUNSET)
                .evaluationModel(EvaluationModel.SONNET)
                .locationsCount(12)
                .succeeded(12)
                .failed(0)
                .parentRunId(1L)
                .gitCommitHash("def5678")
                .build();
        when(promptTestService.replayTest(1L)).thenReturn(run);

        mockMvc.perform(post("/api/prompt-test/replay?parentRunId=1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.parentRunId").value(1))
                .andExpect(jsonPath("$.evaluationModel").value("SONNET"));
    }

    @Test
    @DisplayName("POST /api/prompt-test/replay requires ADMIN role")
    @WithMockUser(roles = "PRO_USER")
    void replayTest_requiresAdminRole() throws Exception {
        mockMvc.perform(post("/api/prompt-test/replay?parentRunId=1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/prompt-test/replay without parentRunId returns 400")
    @WithMockUser(roles = "ADMIN")
    void replayTest_missingParam() throws Exception {
        mockMvc.perform(post("/api/prompt-test/replay")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // --- GET /api/prompt-test/runs ---

    @Test
    @DisplayName("GET /api/prompt-test/runs returns recent runs for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void getRecentRuns_returnsRunsForAdmin() throws Exception {
        PromptTestRunEntity run = PromptTestRunEntity.builder()
                .id(1L)
                .startedAt(LocalDateTime.now())
                .targetDate(LocalDate.of(2026, 3, 1))
                .targetType(TargetType.SUNSET)
                .evaluationModel(EvaluationModel.HAIKU)
                .locationsCount(12)
                .succeeded(12)
                .failed(0)
                .build();
        when(promptTestService.getRecentRuns()).thenReturn(List.of(run));

        mockMvc.perform(get("/api/prompt-test/runs")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationsCount").value(12));
    }

    @Test
    @DisplayName("GET /api/prompt-test/runs requires ADMIN role")
    @WithMockUser(roles = "LITE_USER")
    void getRecentRuns_requiresAdminRole() throws Exception {
        mockMvc.perform(get("/api/prompt-test/runs")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/prompt-test/runs returns 401 when unauthenticated")
    void getRecentRuns_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/prompt-test/runs"))
                .andExpect(status().isUnauthorized());
    }

    // --- GET /api/prompt-test/results ---

    @Test
    @DisplayName("GET /api/prompt-test/results returns results for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void getResults_returnsResultsForAdmin() throws Exception {
        PromptTestResultEntity result = PromptTestResultEntity.builder()
                .id(1L)
                .testRunId(1L)
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
        when(promptTestService.getResults(1L)).thenReturn(List.of(result));

        mockMvc.perform(get("/api/prompt-test/results?testRunId=1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].evaluationModel").value("HAIKU"))
                .andExpect(jsonPath("$[0].fierySkyPotential").value(65));
    }

    @Test
    @DisplayName("GET /api/prompt-test/results requires ADMIN role")
    @WithMockUser(roles = "PRO_USER")
    void getResults_requiresAdminRole() throws Exception {
        mockMvc.perform(get("/api/prompt-test/results?testRunId=1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // --- GET /api/prompt-test/git-info ---

    @Test
    @DisplayName("GET /api/prompt-test/git-info returns git info for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void getGitInfo_returnsInfoForAdmin() throws Exception {
        when(gitInfoService.isAvailable()).thenReturn(true);
        when(gitInfoService.getCommitHash()).thenReturn("abc123456789");
        when(gitInfoService.getCommitAbbrev()).thenReturn("abc1234");
        when(gitInfoService.getCommitDate()).thenReturn(LocalDateTime.of(2026, 3, 1, 10, 0));
        when(gitInfoService.isDirty()).thenReturn(false);
        when(gitInfoService.getBranch()).thenReturn("main");

        mockMvc.perform(get("/api/prompt-test/git-info")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.commitAbbrev").value("abc1234"))
                .andExpect(jsonPath("$.branch").value("main"))
                .andExpect(jsonPath("$.dirty").value(false));
    }

    @Test
    @DisplayName("GET /api/prompt-test/git-info requires ADMIN role")
    @WithMockUser(roles = "PRO_USER")
    void getGitInfo_requiresAdminRole() throws Exception {
        mockMvc.perform(get("/api/prompt-test/git-info")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}
