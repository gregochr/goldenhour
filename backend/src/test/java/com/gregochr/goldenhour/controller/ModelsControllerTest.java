package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link ModelsController} — validates per-run-type model selection
 * and the absence of the retired optimisation endpoint.
 */
class ModelsControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    /**
     * The tokens these tests mint name accounts; the JWT filter authenticates a token only against the
     * account row it names (login-registration plan §2 #13), so the rows must exist.
     */
    @BeforeEach
    void accountsTheTokensNameExist() {
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(account(2L, "user", UserRole.LITE_USER)));
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(account(1L, "admin", UserRole.ADMIN)));
    }

    private static AppUserEntity account(long id, String username, UserRole role) {
        return AppUserEntity.builder().id(id).username(username).password("hash").role(role).enabled(true)
                .createdAt(LocalDateTime.now()).build();
    }

    private Map<RunType, EvaluationModel> buildDefaultConfigs() {
        Map<RunType, EvaluationModel> configs = new EnumMap<>(RunType.class);
        configs.put(RunType.BRIEFING_BEST_BET, EvaluationModel.HAIKU);
        configs.put(RunType.AURORA_EVALUATION, EvaluationModel.HAIKU);
        configs.put(RunType.BATCH_NEAR_TERM, EvaluationModel.HAIKU);
        configs.put(RunType.BATCH_FAR_TERM, EvaluationModel.HAIKU);
        return configs;
    }

    private Map<RunType, Boolean> buildDefaultEtConfigs() {
        Map<RunType, Boolean> configs = new EnumMap<>(RunType.class);
        for (RunType rt : RunType.values()) {
            configs.put(rt, false);
        }
        return configs;
    }

    @Test
    @DisplayName("GET /api/models returns available models and configs, and no optimisation strategies")
    void getAvailableModels_returnsConfigsMap() throws Exception {
        String token = jwtService.generateAccessToken("user", UserRole.LITE_USER);
        when(modelSelectionService.getAllConfigs()).thenReturn(buildDefaultConfigs());
        when(modelSelectionService.getAllExtendedThinkingConfigs()).thenReturn(buildDefaultEtConfigs());

        mockMvc.perform(get("/api/models")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").isArray())
                .andExpect(content().string(containsString("HAIKU")))
                .andExpect(content().string(containsString("SONNET")))
                .andExpect(content().string(containsString("OPUS")))
                .andExpect(jsonPath("$.configs.BATCH_NEAR_TERM").value("HAIKU"))
                .andExpect(jsonPath("$.configs.BATCH_FAR_TERM").value("HAIKU"))
                .andExpect(jsonPath("$.configs.BRIEFING_BEST_BET").value("HAIKU"))
                .andExpect(jsonPath("$.configs.AURORA_EVALUATION").value("HAIKU"))
                .andExpect(jsonPath("$.extendedThinkingConfigs.BRIEFING_BEST_BET").value(false))
                .andExpect(jsonPath("$.optimisationStrategies").doesNotExist());

        verify(modelSelectionService).getAllConfigs();
        verify(modelSelectionService).getAllExtendedThinkingConfigs();
    }

    @Test
    @DisplayName("GET /api/models returns model versions in available array")
    void getAvailableModels_includesVersions() throws Exception {
        String token = jwtService.generateAccessToken("user", UserRole.LITE_USER);
        when(modelSelectionService.getAllConfigs()).thenReturn(buildDefaultConfigs());
        when(modelSelectionService.getAllExtendedThinkingConfigs()).thenReturn(buildDefaultEtConfigs());

        mockMvc.perform(get("/api/models")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available[0].name").value("HAIKU"))
                .andExpect(jsonPath("$.available[0].version").value("4.5"))
                .andExpect(jsonPath("$.available[1].name").value("HAIKU_55"))
                .andExpect(jsonPath("$.available[1].version").value("5.5"))
                .andExpect(jsonPath("$.available[3].name").value("SONNET_55"))
                .andExpect(jsonPath("$.available[3].version").value("5.5"))
                .andExpect(jsonPath("$.available[4].name").value("OPUS"))
                .andExpect(jsonPath("$.available[4].version").value("4.6"));
    }

    @Test
    @DisplayName("GET /api/models carries extendedThinkingConfigs.BRIEFING_BEST_BET=true when set")
    void getAvailableModels_carriesExtendedThinkingTrueForBriefing() throws Exception {
        String token = jwtService.generateAccessToken("user", UserRole.LITE_USER);
        Map<RunType, Boolean> etConfigs = new EnumMap<>(RunType.class);
        for (RunType rt : RunType.values()) {
            etConfigs.put(rt, false);
        }
        etConfigs.put(RunType.BRIEFING_BEST_BET, true);
        when(modelSelectionService.getAllConfigs()).thenReturn(buildDefaultConfigs());
        when(modelSelectionService.getAllExtendedThinkingConfigs()).thenReturn(etConfigs);

        mockMvc.perform(get("/api/models")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extendedThinkingConfigs.BRIEFING_BEST_BET").value(true))
                .andExpect(jsonPath("$.extendedThinkingConfigs.AURORA_EVALUATION").value(false));

        verify(modelSelectionService).getAllExtendedThinkingConfigs();
    }

    @Test
    @DisplayName("GET /api/models does not include WILDLIFE in available models")
    void getAvailableModels_excludesWildlife() throws Exception {
        String token = jwtService.generateAccessToken("user", UserRole.LITE_USER);
        when(modelSelectionService.getAllConfigs()).thenReturn(buildDefaultConfigs());
        when(modelSelectionService.getAllExtendedThinkingConfigs()).thenReturn(buildDefaultEtConfigs());

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
        when(modelSelectionService.setActiveModel(RunType.BATCH_FAR_TERM, EvaluationModel.OPUS))
                .thenReturn(EvaluationModel.OPUS);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"BATCH_FAR_TERM\",\"model\":\"OPUS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runType").value("BATCH_FAR_TERM"))
                .andExpect(jsonPath("$.active").value("OPUS"));

        verify(modelSelectionService).setActiveModel(RunType.BATCH_FAR_TERM, EvaluationModel.OPUS);
    }

    @Test
    @DisplayName("PUT /api/models/active switches BATCH_NEAR_TERM to SONNET")
    void setActiveModel_switchesBatchNearTermToSonnet() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        when(modelSelectionService.setActiveModel(RunType.BATCH_NEAR_TERM, EvaluationModel.SONNET))
                .thenReturn(EvaluationModel.SONNET);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"BATCH_NEAR_TERM\",\"model\":\"SONNET\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runType").value("BATCH_NEAR_TERM"))
                .andExpect(jsonPath("$.active").value("SONNET"));
    }

    @Test
    @DisplayName("PUT /api/models/active accepts BLUEBELL_GLOSS, a live run type the screen now lists")
    void setActiveModel_bluebellGloss_succeeds() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        when(modelSelectionService.setActiveModel(RunType.BLUEBELL_GLOSS, EvaluationModel.SONNET))
                .thenReturn(EvaluationModel.SONNET);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"BLUEBELL_GLOSS\",\"model\":\"SONNET\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runType").value("BLUEBELL_GLOSS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"VERY_SHORT_TERM", "SHORT_TERM", "LONG_TERM", "WEATHER", "TIDE"})
    @DisplayName("PUT /api/models/active refuses a run type the screen does not manage with 400")
    void setActiveModel_nonConfigurableRunType_badRequest(String runType) throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"" + runType + "\",\"model\":\"SONNET\"}"))
                .andExpect(status().isBadRequest());

        verify(modelSelectionService, never()).setActiveModel(any(RunType.class), any(EvaluationModel.class));
    }

    @Test
    @DisplayName("PUT /api/models/active without ADMIN role returns 403")
    void setActiveModel_nonAdminUser_forbidden() throws Exception {
        String userToken = jwtService.generateAccessToken("user", UserRole.LITE_USER);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + userToken)
                .contentType("application/json")
                .content("{\"runType\":\"BATCH_NEAR_TERM\",\"model\":\"SONNET\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/models/active without token returns 401")
    void setActiveModel_noToken_unauthorized() throws Exception {
        mockMvc.perform(put("/api/models/active")
                .contentType("application/json")
                .content("{\"runType\":\"BATCH_NEAR_TERM\",\"model\":\"SONNET\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /api/models/active with invalid model returns 400")
    void setActiveModel_invalidModel_badRequest() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"BATCH_NEAR_TERM\",\"model\":\"INVALID_MODEL\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/models/active with invalid runType returns 400")
    void setActiveModel_invalidRunType_badRequest() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"NOT_A_RUN_TYPE\",\"model\":\"HAIKU\"}"))
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
    // The optimisation endpoint is retired
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PUT /api/models/optimisation is no longer mapped, even for an ADMIN")
    void updateOptimisation_isNoLongerMapped() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(put("/api/models/optimisation")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(NoResourceFoundException.class));

        verify(optimisationStrategyService, never()).updateStrategy(any(), any(), anyBoolean(), any());
    }

    // ── Extended thinking endpoint ──

    @Test
    @DisplayName("PUT /api/models/extended-thinking enables ET for BRIEFING_BEST_BET")
    void setExtendedThinking_admin_enablesBriefingBestBet() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        when(modelSelectionService.setExtendedThinking(RunType.BRIEFING_BEST_BET, true))
                .thenReturn(true);

        mockMvc.perform(put("/api/models/extended-thinking")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"BRIEFING_BEST_BET\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runType").value("BRIEFING_BEST_BET"))
                .andExpect(jsonPath("$.extendedThinking").value(true));

        verify(modelSelectionService).setExtendedThinking(RunType.BRIEFING_BEST_BET, true);
    }

    @Test
    @DisplayName("PUT /api/models/extended-thinking disables ET for BRIEFING_BEST_BET")
    void setExtendedThinking_admin_disablesBriefingBestBet() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        when(modelSelectionService.setExtendedThinking(RunType.BRIEFING_BEST_BET, false))
                .thenReturn(false);

        mockMvc.perform(put("/api/models/extended-thinking")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"BRIEFING_BEST_BET\",\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extendedThinking").value(false));

        verify(modelSelectionService).setExtendedThinking(RunType.BRIEFING_BEST_BET, false);
    }

    @Test
    @DisplayName("PUT /api/models/extended-thinking without ADMIN returns 403")
    void setExtendedThinking_nonAdmin_forbidden() throws Exception {
        String userToken = jwtService.generateAccessToken("user", UserRole.LITE_USER);

        mockMvc.perform(put("/api/models/extended-thinking")
                .header("Authorization", "Bearer " + userToken)
                .contentType("application/json")
                .content("{\"runType\":\"BRIEFING_BEST_BET\",\"enabled\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/models/extended-thinking without token returns 401")
    void setExtendedThinking_noToken_unauthorized() throws Exception {
        mockMvc.perform(put("/api/models/extended-thinking")
                .contentType("application/json")
                .content("{\"runType\":\"BRIEFING_BEST_BET\",\"enabled\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /api/models/extended-thinking with invalid runType returns 400")
    void setExtendedThinking_invalidRunType_badRequest() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(put("/api/models/extended-thinking")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"NOT_A_RUN_TYPE\",\"enabled\":true}"))
                .andExpect(status().isBadRequest());
    }
}
