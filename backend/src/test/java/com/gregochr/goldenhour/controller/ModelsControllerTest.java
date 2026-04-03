package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.OptimisationStrategyEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.service.JwtService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.OptimisationStrategyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link ModelsController} — validates per-run-type model selection
 * and optimisation strategy endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModelsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private ModelSelectionService modelSelectionService;

    @MockitoBean
    private OptimisationStrategyService optimisationStrategyService;

    private Map<RunType, EvaluationModel> buildDefaultConfigs() {
        Map<RunType, EvaluationModel> configs = new EnumMap<>(RunType.class);
        configs.put(RunType.VERY_SHORT_TERM, EvaluationModel.HAIKU);
        configs.put(RunType.SHORT_TERM, EvaluationModel.HAIKU);
        configs.put(RunType.LONG_TERM, EvaluationModel.HAIKU);
        configs.put(RunType.BRIEFING_BEST_BET, EvaluationModel.HAIKU);
        configs.put(RunType.AURORA_EVALUATION, EvaluationModel.HAIKU);
        return configs;
    }

    @Test
    @DisplayName("GET /api/models returns available models, configs, and optimisation strategies")
    void getAvailableModels_returnsConfigsMap() throws Exception {
        String token = jwtService.generateAccessToken("user", UserRole.LITE_USER);
        when(modelSelectionService.getAllConfigs()).thenReturn(buildDefaultConfigs());
        when(optimisationStrategyService.getAllConfigs()).thenReturn(new EnumMap<>(RunType.class));

        mockMvc.perform(get("/api/models")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").isArray())
                .andExpect(content().string(containsString("HAIKU")))
                .andExpect(content().string(containsString("SONNET")))
                .andExpect(content().string(containsString("OPUS")))
                .andExpect(jsonPath("$.configs.VERY_SHORT_TERM").value("HAIKU"))
                .andExpect(jsonPath("$.configs.SHORT_TERM").value("HAIKU"))
                .andExpect(jsonPath("$.configs.LONG_TERM").value("HAIKU"))
                .andExpect(jsonPath("$.configs.BRIEFING_BEST_BET").value("HAIKU"))
                .andExpect(jsonPath("$.configs.AURORA_EVALUATION").value("HAIKU"))
                .andExpect(jsonPath("$.optimisationStrategies").exists());

        verify(modelSelectionService).getAllConfigs();
        verify(optimisationStrategyService).getAllConfigs();
    }

    @Test
    @DisplayName("GET /api/models returns model versions in available array")
    void getAvailableModels_includesVersions() throws Exception {
        String token = jwtService.generateAccessToken("user", UserRole.LITE_USER);
        when(modelSelectionService.getAllConfigs()).thenReturn(buildDefaultConfigs());
        when(optimisationStrategyService.getAllConfigs()).thenReturn(new EnumMap<>(RunType.class));

        mockMvc.perform(get("/api/models")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available[0].name").value("HAIKU"))
                .andExpect(jsonPath("$.available[0].version").value("4.5"))
                .andExpect(jsonPath("$.available[2].name").value("OPUS"))
                .andExpect(jsonPath("$.available[2].version").value("4.6"));
    }

    @Test
    @DisplayName("GET /api/models does not include WILDLIFE in available models")
    void getAvailableModels_excludesWildlife() throws Exception {
        String token = jwtService.generateAccessToken("user", UserRole.LITE_USER);
        when(modelSelectionService.getAllConfigs()).thenReturn(buildDefaultConfigs());
        when(optimisationStrategyService.getAllConfigs()).thenReturn(new EnumMap<>(RunType.class));

        String body = mockMvc.perform(get("/api/models")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("WILDLIFE");
    }

    @Test
    @DisplayName("PUT /api/models/active with ADMIN role and runType succeeds")
    void setActiveModel_adminUser_withRunType_succeeds() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        when(modelSelectionService.setActiveModel(RunType.VERY_SHORT_TERM, EvaluationModel.OPUS))
                .thenReturn(EvaluationModel.OPUS);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"VERY_SHORT_TERM\",\"model\":\"OPUS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runType").value("VERY_SHORT_TERM"))
                .andExpect(jsonPath("$.active").value("OPUS"));

        verify(modelSelectionService).setActiveModel(RunType.VERY_SHORT_TERM, EvaluationModel.OPUS);
    }

    @Test
    @DisplayName("PUT /api/models/active switches SHORT_TERM to SONNET")
    void setActiveModel_switchesShortTermToSonnet() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        when(modelSelectionService.setActiveModel(RunType.SHORT_TERM, EvaluationModel.SONNET))
                .thenReturn(EvaluationModel.SONNET);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"SHORT_TERM\",\"model\":\"SONNET\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runType").value("SHORT_TERM"))
                .andExpect(jsonPath("$.active").value("SONNET"));
    }

    @Test
    @DisplayName("PUT /api/models/active without ADMIN role returns 403")
    void setActiveModel_nonAdminUser_forbidden() throws Exception {
        String userToken = jwtService.generateAccessToken("user", UserRole.LITE_USER);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + userToken)
                .contentType("application/json")
                .content("{\"runType\":\"SHORT_TERM\",\"model\":\"SONNET\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/models/active without token returns 401")
    void setActiveModel_noToken_unauthorized() throws Exception {
        mockMvc.perform(put("/api/models/active")
                .contentType("application/json")
                .content("{\"runType\":\"SHORT_TERM\",\"model\":\"SONNET\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /api/models/active with invalid model returns 400")
    void setActiveModel_invalidModel_badRequest() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"SHORT_TERM\",\"model\":\"INVALID_MODEL\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/models/active switches BRIEFING_BEST_BET to SONNET")
    void setActiveModel_switchesBriefingBestBetToSonnet() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        when(modelSelectionService.setActiveModel(RunType.BRIEFING_BEST_BET, EvaluationModel.SONNET))
                .thenReturn(EvaluationModel.SONNET);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"BRIEFING_BEST_BET\",\"model\":\"SONNET\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runType").value("BRIEFING_BEST_BET"))
                .andExpect(jsonPath("$.active").value("SONNET"));

        verify(modelSelectionService).setActiveModel(RunType.BRIEFING_BEST_BET, EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("PUT /api/models/active switches AURORA_EVALUATION to OPUS")
    void setActiveModel_switchesAuroraEvaluationToOpus() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        when(modelSelectionService.setActiveModel(RunType.AURORA_EVALUATION, EvaluationModel.OPUS))
                .thenReturn(EvaluationModel.OPUS);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"AURORA_EVALUATION\",\"model\":\"OPUS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runType").value("AURORA_EVALUATION"))
                .andExpect(jsonPath("$.active").value("OPUS"));

        verify(modelSelectionService).setActiveModel(RunType.AURORA_EVALUATION, EvaluationModel.OPUS);
    }

    // -------------------------------------------------------------------------
    // Optimisation strategy endpoints
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PUT /api/models/optimisation with ADMIN enables strategy")
    void updateOptimisation_admin_succeeds() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        var updated = OptimisationStrategyEntity.builder()
                .runType(RunType.VERY_SHORT_TERM)
                .strategyType(OptimisationStrategyType.SKIP_LOW_RATED)
                .enabled(true)
                .paramValue(4)
                .updatedAt(LocalDateTime.now())
                .build();
        when(optimisationStrategyService.updateStrategy(
                eq(RunType.VERY_SHORT_TERM), eq(OptimisationStrategyType.SKIP_LOW_RATED),
                eq(true), eq(4)))
                .thenReturn(updated);

        mockMvc.perform(put("/api/models/optimisation")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"VERY_SHORT_TERM\","
                        + "\"strategyType\":\"SKIP_LOW_RATED\","
                        + "\"enabled\":true,\"paramValue\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runType").value("VERY_SHORT_TERM"))
                .andExpect(jsonPath("$.strategyType").value("SKIP_LOW_RATED"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.paramValue").value(4));
    }

    @Test
    @DisplayName("PUT /api/models/optimisation without ADMIN returns 403")
    void updateOptimisation_nonAdmin_forbidden() throws Exception {
        String userToken = jwtService.generateAccessToken("user", UserRole.LITE_USER);

        mockMvc.perform(put("/api/models/optimisation")
                .header("Authorization", "Bearer " + userToken)
                .contentType("application/json")
                .content("{\"runType\":\"SHORT_TERM\",\"strategyType\":\"SKIP_LOW_RATED\",\"enabled\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/models/optimisation without token returns 401")
    void updateOptimisation_noToken_unauthorized() throws Exception {
        mockMvc.perform(put("/api/models/optimisation")
                .contentType("application/json")
                .content("{\"runType\":\"SHORT_TERM\",\"strategyType\":\"SKIP_LOW_RATED\",\"enabled\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /api/models/optimisation with conflict returns 400")
    void updateOptimisation_conflict_returnsBadRequest() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        when(optimisationStrategyService.updateStrategy(any(), any(), eq(true), any()))
                .thenThrow(new IllegalArgumentException("EVALUATE_ALL conflicts with skip strategies"));

        mockMvc.perform(put("/api/models/optimisation")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"SHORT_TERM\",\"strategyType\":\"EVALUATE_ALL\",\"enabled\":true}"))
                .andExpect(status().isBadRequest());
    }
}
